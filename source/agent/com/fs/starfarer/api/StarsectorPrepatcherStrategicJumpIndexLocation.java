package com.fs.starfarer.api;

import com.fs.starfarer.api.StarsectorPrepatcherStrategicJumpIndexData.IndexData;
import com.fs.starfarer.api.StarsectorPrepatcherStrategicJumpIndexData.PointRecord;
import com.fs.starfarer.api.StarsectorPrepatcherStrategicJumpIndexData.SegmentedIdentityMap;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.RandomAccess;

/**
 * Per-location index state machine and the resumable build, refresh, capture
 * and audit units it drives. Process-wide scheduling state, counters and the
 * destination owner cache live on StarsectorPrepatcherStrategicJumpIndex.
 */
final class StarsectorPrepatcherStrategicJumpIndexLocation {
  private StarsectorPrepatcherStrategicJumpIndexLocation() {}

  static final class LocationState {
    static final int BUILDING = 1;
    static final int REFRESHING = 2;
    static final int READY = 3;
    static final int FAILED = 4;

    final LocationAPI location;
    Class<?> jumpPointType;
    boolean active = true;
    boolean queued;
    boolean auditLinked;
    boolean evictableLinked;
    LocationState previous;
    LocationState next;
    LocationState auditPrevious;
    LocationState auditNext;
    LocationState evictablePrevious;
    LocationState evictableNext;
    int retryHeapIndex = -1;
    long retryOrder;
    long lastAccessNanos;
    long sourceEpoch;
    long pendingSinceNanos;
    long deferredLookupSinceNanos;
    long deferredPlanSinceNanos;
    List<?> observedSource;
    int observedSize;
    Object observedFirst;
    Object observedLast;
    boolean observedRandomAccess;
    boolean fullBuildRequested = true;
    int phase = BUILDING;
    long retryAtNanos;
    int consecutiveFailures;
    IndexData data;
    BuildTask build;
    PointRefreshTask refresh;
    final ArrayDeque<JumpPointAPI> dirtyQueue = new ArrayDeque<>();
    final IdentityHashMap<JumpPointAPI, Boolean> dirtySet = new IdentityHashMap<>();
    AuditTask audit;

    LocationState(LocationAPI location, Class<?> jumpPointType) {
      this.location = location;
      this.jumpPointType = jumpPointType;
    }

    String phaseName() {
      return switch (phase) {
        case BUILDING -> "BUILDING";
        case REFRESHING -> "REFRESHING";
        case READY -> "READY";
        case FAILED -> "FAILED_COOLDOWN";
        default -> "UNKNOWN";
      };
    }

    void noteDeferredLookup() {
      if (deferredLookupSinceNanos == 0L && StarsectorPrepatcherStrategicJumpIndex.lastNowNanos > 0L) {
        deferredLookupSinceNanos = StarsectorPrepatcherStrategicJumpIndex.lastNowNanos;
      }
    }

    void noteDeferredPlan() {
      if (deferredPlanSinceNanos == 0L && StarsectorPrepatcherStrategicJumpIndex.lastNowNanos > 0L) {
        deferredPlanSinceNanos = StarsectorPrepatcherStrategicJumpIndex.lastNowNanos;
      }
    }

    private void beginPending() {
      if (pendingSinceNanos == 0L && StarsectorPrepatcherStrategicJumpIndex.lastNowNanos > 0L) {
        pendingSinceNanos = StarsectorPrepatcherStrategicJumpIndex.lastNowNanos;
      }
    }

    void resolveDebt(long now) {
      if (pendingSinceNanos > 0L && now > pendingSinceNanos) {
        StarsectorPrepatcherStrategicJumpIndex.readyLatencyMaxNanos =
            Math.max(StarsectorPrepatcherStrategicJumpIndex.readyLatencyMaxNanos, now - pendingSinceNanos);
      }
      if (deferredLookupSinceNanos > 0L && now > deferredLookupSinceNanos) {
        StarsectorPrepatcherStrategicJumpIndex.deferredLookupMaxNanos =
            Math.max(StarsectorPrepatcherStrategicJumpIndex.deferredLookupMaxNanos, now - deferredLookupSinceNanos);
      }
      if (deferredPlanSinceNanos > 0L && now > deferredPlanSinceNanos) {
        StarsectorPrepatcherStrategicJumpIndex.deferredPlanMaxNanos =
            Math.max(StarsectorPrepatcherStrategicJumpIndex.deferredPlanMaxNanos, now - deferredPlanSinceNanos);
      }
      pendingSinceNanos = 0L;
      deferredLookupSinceNanos = 0L;
      deferredPlanSinceNanos = 0L;
    }

    void observeSource(List<?> live) {
      observedSource = live;
      observedSize = live.size();
      observedRandomAccess = live instanceof RandomAccess;
      observedFirst = observedSize == 0 ? null : StarsectorPrepatcherStrategicJumpIndex.safeGet(live, 0);
      observedLast = observedSize == 0 ? null
          : (observedRandomAccess ? StarsectorPrepatcherStrategicJumpIndex.safeGet(live, observedSize - 1) : null);
    }

    boolean fastSourceMatches(List<?> live) {
      if (observedSource != live || observedSize != live.size()) return false;
      if (observedSize == 0) return true;
      if (StarsectorPrepatcherStrategicJumpIndex.safeGet(live, 0) != observedFirst) return false;
      return !observedRandomAccess
          || StarsectorPrepatcherStrategicJumpIndex.safeGet(live, observedSize - 1) == observedLast;
    }

    void requestFullBuild() {
      StarsectorPrepatcherStrategicJumpIndex.unlinkAudit(this);
      StarsectorPrepatcherStrategicJumpIndex.unlinkEvictable(this);
      StarsectorPrepatcherStrategicJumpIndex.removeRetry(this);
      data = null;
      build = null;
      refresh = null;
      audit = null;
      dirtyQueue.clear();
      dirtySet.clear();
      fullBuildRequested = true;
      phase = BUILDING;
      retryAtNanos = 0L;
      beginPending();
      StarsectorPrepatcherStrategicJumpIndex.enqueue(this);
    }

    private void enterRefreshing() {
      StarsectorPrepatcherStrategicJumpIndex.unlinkAudit(this);
      StarsectorPrepatcherStrategicJumpIndex.unlinkEvictable(this);
      StarsectorPrepatcherStrategicJumpIndex.removeRetry(this);
      phase = REFRESHING;
      beginPending();
    }

    private void enterReady(long now) {
      StarsectorPrepatcherStrategicJumpIndex.removeRetry(this);
      StarsectorPrepatcherStrategicJumpIndex.unlinkAudit(this);
      StarsectorPrepatcherStrategicJumpIndex.unlinkEvictable(this);
      phase = READY;
      resolveDebt(now);
      StarsectorPrepatcherStrategicJumpIndex.scheduleAudit(this);
      StarsectorPrepatcherStrategicJumpIndex.linkEvictableTail(this);
    }

    void invalidateSource() {
      sourceEpoch++;
      observedSource = null;
      observedSize = -1;
      observedFirst = null;
      observedLast = null;
      observedRandomAccess = false;
      requestFullBuild();
    }

    void markPointDirty(JumpPointAPI point) {
      if (phase == FAILED) {
        sourceEpoch++;
        requestFullBuild();
        return;
      }
      if (dirtySet.put(point, Boolean.TRUE) == null) dirtyQueue.addLast(point);
      if (dirtySet.size() > StarsectorPrepatcherStrategicJumpIndex.MAX_PENDING_POINT_DELTAS) {
        // A post-load topology burst is cheaper and more compact as one
        // replacement snapshot than as an unbounded queue of point deltas.
        sourceEpoch++;
        requestFullBuild();
        return;
      }
      if (phase == READY) enterRefreshing();
      retryAtNanos = 0L;
      StarsectorPrepatcherStrategicJumpIndex.enqueue(this);
    }

    PointRecord recordFor(JumpPointAPI point) {
      if (refresh != null && refresh.oldRecord.point == point) return refresh.oldRecord;
      if (build != null) {
        PointRecord record = build.data.byPoint.get(point);
        if (record != null) return record;
      }
      return data == null ? null : data.byPoint.get(point);
    }

    boolean hasWork() {
      if (!active || phase == FAILED) return false;
      return fullBuildRequested || build != null || refresh != null || !dirtyQueue.isEmpty();
    }

    boolean workOne(PrepatcherConfig config, long now) {
      if (!active) return false;
      if (phase == FAILED) {
        if (now < retryAtNanos) {
          StarsectorPrepatcherStrategicJumpIndex.scheduleRetry(this);
          return false;
        }
        StarsectorPrepatcherStrategicJumpIndex.RETRIES.increment();
        StarsectorPrepatcherStrategicJumpIndex.unlinkEvictable(this);
        phase = BUILDING;
        fullBuildRequested = true;
        beginPending();
      }
      if (fullBuildRequested || build != null) { return buildOne(config, now); }
      if (refresh != null || !dirtyQueue.isEmpty()) { return refreshOne(config, now); }
      if (data != null) enterReady(now);
      return false;
    }

    private boolean buildOne(PrepatcherConfig config, long now) {
      if (build == null) {
        List<?> live = observedSource;
        if (live == null) {
          try {
            live = location.getEntities(jumpPointType);
          } catch (Throwable failure) {
            fail(config, now);
            return false;
          }
          if (live == null) {
            fail(config, now);
            return false;
          }
          observeSource(live);
        }
        build = new BuildTask(this, live, sourceEpoch);
        fullBuildRequested = false;
        phase = BUILDING;
        StarsectorPrepatcherStrategicJumpIndex.BUILDS_STARTED.increment();
      }

      BuildResult result = build.step();
      if (result == BuildResult.MORE) return true;
      if (result == BuildResult.FAILED) {
        fail(config, now);
        return false;
      }
      if (result == BuildResult.RESTART || build.sourceEpoch != sourceEpoch || !fastSourceMatches(build.source)) {
        invalidateSource();
        StarsectorPrepatcherStrategicJumpIndex.BUILD_RESTARTS.increment();
        return true;
      }

      data = build.data;
      build = null;
      audit = null;
      consecutiveFailures = 0;
      StarsectorPrepatcherStrategicJumpIndex.BUILDS_COMPLETED.increment();
      if (dirtyQueue.isEmpty()) {
        enterReady(now);
        return false;
      }
      enterRefreshing();
      return true;
    }

    private boolean refreshOne(PrepatcherConfig config, long now) {
      if (data == null) {
        requestFullBuild();
        return true;
      }
      if (refresh == null) {
        JumpPointAPI point = null;
        while (!dirtyQueue.isEmpty() && point == null) {
          JumpPointAPI candidate = dirtyQueue.removeFirst();
          if (dirtySet.remove(candidate) != null) point = candidate;
        }
        if (point == null) {
          enterReady(now);
          return false;
        }
        PointRecord old = data.byPoint.get(point);
        if (old == null) {
          invalidateSource();
          return true;
        }
        refresh = new PointRefreshTask(this, data, old);
      }

      RefreshResult result = refresh.step();
      if (result == RefreshResult.MORE) return true;
      if (result == RefreshResult.FAILED) {
        refresh = null;
        fail(config, now);
        return false;
      }
      refresh = null;
      audit = null;
      StarsectorPrepatcherStrategicJumpIndex.POINT_REFRESHES.increment();
      if (!dirtyQueue.isEmpty()) return true;
      enterReady(now);
      return false;
    }

    boolean auditOne() {
      if (!active || phase != READY || data == null) return false;
      if (audit == null) audit = new AuditTask(this, data);
      AuditResult result = audit.step();
      if (result == AuditResult.CYCLE_COMPLETE) {
        audit = null;
        return false;
      }
      if (result == AuditResult.SOURCE_CHANGED) {
        audit = null;
        invalidateSource();
        StarsectorPrepatcherStrategicJumpIndex.AUDIT_REPAIRS.increment();
        return false;
      }
      if (result == AuditResult.POINT_CHANGED) {
        JumpPointAPI point = audit.changedPoint;
        audit = null;
        markPointDirty(point);
        StarsectorPrepatcherStrategicJumpIndex.AUDIT_REPAIRS.increment();
        return false;
      }
      return true;
    }

    void fail(PrepatcherConfig config, long now) {
      StarsectorPrepatcherStrategicJumpIndex.unlinkAudit(this);
      StarsectorPrepatcherStrategicJumpIndex.unlinkEvictable(this);
      StarsectorPrepatcherStrategicJumpIndex.removeRetry(this);
      data = null;
      build = null;
      refresh = null;
      audit = null;
      fullBuildRequested = false;
      dirtyQueue.clear();
      dirtySet.clear();
      phase = FAILED;
      beginPending();
      consecutiveFailures = Math.min(16, consecutiveFailures + 1);
      long delay = (long) config.strategicJumpIndexFailureRetryMs * 1_000_000L;
      int shift = Math.min(4, consecutiveFailures - 1);
      delay = Math.min(delay << shift, 300_000_000_000L);
      retryAtNanos = now + Math.max(1L, delay);
      StarsectorPrepatcherStrategicJumpIndex.linkEvictableTail(this);
      StarsectorPrepatcherStrategicJumpIndex.scheduleRetry(this);
      StarsectorPrepatcherStrategicJumpIndex.FAILURES.increment();
    }
  }

  enum BuildResult {
    MORE, COMPLETE, RESTART, FAILED
  }
  enum RefreshResult {
    MORE, COMPLETE, FAILED
  }
  enum AuditResult {
    MORE, CYCLE_COMPLETE, SOURCE_CHANGED, POINT_CHANGED
  }

  static final class BuildTask {
    final LocationState state;
    final List<?> source;
    final int size;
    final boolean randomAccess;
    final Object first;
    final Object last;
    final long sourceEpoch;
    final IndexData data;
    Iterator<?> sourceIterator;
    int sourceIndex;
    PointCapture capture;
    PointRecord pendingRecord;
    int contributionIndex;
    int captureRestarts;

    BuildTask(LocationState state, List<?> source, long sourceEpoch) {
      this.state = state;
      this.source = source;
      this.size = source.size();
      this.randomAccess = source instanceof RandomAccess;
      this.first = size == 0 ? null : StarsectorPrepatcherStrategicJumpIndex.safeGet(source, 0);
      this.last = size == 0 || !randomAccess ? null : StarsectorPrepatcherStrategicJumpIndex.safeGet(source, size - 1);
      this.sourceEpoch = sourceEpoch;
      this.data = new IndexData(source, size, first, last, randomAccess);
      if (!randomAccess) sourceIterator = source.iterator();
    }

    BuildResult step() {
      try {
        if (pendingRecord != null) {
          if (contributionIndex < pendingRecord.locationCount) {
            data.addContribution(pendingRecord, pendingRecord.locations[contributionIndex++]);
            return BuildResult.MORE;
          }
          pendingRecord = null;
          sourceIndex++;
          return sourceIndex >= size ? BuildResult.COMPLETE : BuildResult.MORE;
        }
        if (capture != null) {
          if (!capture.complete()) {
            if (!capture.captureOne()) {
              capture = null;
              if (++captureRestarts > 16) return BuildResult.FAILED;
            }
            return BuildResult.MORE;
          }
          if (!capture.stable()) {
            capture = null;
            if (++captureRestarts > 16) return BuildResult.FAILED;
            return BuildResult.MORE;
          }
          PointRecord record = capture.finish();
          capture = null;
          captureRestarts = 0;
          if (data.byPoint.put(record.point, record) != null) { return BuildResult.FAILED; }
          data.records.set(record.ordinal, record);
          pendingRecord = record;
          contributionIndex = 0;
          return BuildResult.MORE;
        }
        if (sourceIndex >= size) return BuildResult.COMPLETE;
        if (source.size() != size) return BuildResult.RESTART;
        Object raw = randomAccess ? source.get(sourceIndex) : sourceIterator.next();
        if (!(raw instanceof JumpPointAPI point)) return BuildResult.FAILED;
        capture = new PointCapture(state, point, sourceIndex);
        return BuildResult.MORE;
      } catch (Throwable failure) {
        return BuildResult.FAILED;
      }
    }
  }

  static final class PointRefreshTask {
    private static final int CAPTURE = 1;
    private static final int REMOVE = 2;
    private static final int ADD = 3;
    private static final int COMMIT = 4;

    final LocationState state;
    final IndexData data;
    final PointRecord oldRecord;
    PointCapture capture;
    PointRecord newRecord;
    int phase = CAPTURE;
    int index;
    int captureRestarts;

    PointRefreshTask(LocationState state, IndexData data, PointRecord oldRecord) {
      this.state = state;
      this.data = data;
      this.oldRecord = oldRecord;
    }

    RefreshResult step() {
      try {
        if (phase == CAPTURE) {
          if (capture == null) {
            capture = new PointCapture(state, oldRecord.point, oldRecord.ordinal);
            return RefreshResult.MORE;
          }
          if (!capture.complete()) {
            if (!capture.captureOne()) {
              capture = null;
              if (++captureRestarts > 16) return RefreshResult.FAILED;
            }
            return RefreshResult.MORE;
          }
          if (!capture.stable()) {
            capture = null;
            if (++captureRestarts > 16) return RefreshResult.FAILED;
            return RefreshResult.MORE;
          }
          newRecord = capture.finish();
          capture = null;
          captureRestarts = 0;
          phase = REMOVE;
          index = 0;
          return RefreshResult.MORE;
        }
        if (phase == REMOVE) {
          if (index < oldRecord.locationCount) {
            data.removeContribution(oldRecord, oldRecord.locations[index++]);
            return RefreshResult.MORE;
          }
          phase = ADD;
          index = 0;
          return RefreshResult.MORE;
        }
        if (phase == ADD) {
          if (index < newRecord.locationCount) {
            data.addContribution(newRecord, newRecord.locations[index++]);
            return RefreshResult.MORE;
          }
          phase = COMMIT;
          return RefreshResult.MORE;
        }
        data.byPoint.put(newRecord.point, newRecord);
        data.records.set(newRecord.ordinal, newRecord);
        data.clearMergeCache();
        return RefreshResult.COMPLETE;
      } catch (Throwable failure) {
        return RefreshResult.FAILED;
      }
    }
  }

  static final class AuditTask {
    final LocationState state;
    final IndexData data;
    Iterator<?> sourceIterator;
    int ordinal;
    int destinationIndex = -1;
    Iterator<?> destinationIterator;
    PointRecord current;
    JumpPointAPI changedPoint;

    AuditTask(LocationState state, IndexData data) {
      this.state = state;
      this.data = data;
      if (!data.randomAccess) sourceIterator = data.source.iterator();
    }

    AuditResult step() {
      try {
        if (data.source != state.observedSource || data.source.size() != data.size) {
          return AuditResult.SOURCE_CHANGED;
        }
        if (ordinal >= data.size) return AuditResult.CYCLE_COMPLETE;
        current = data.records.get(ordinal);
        if (current == null) return AuditResult.SOURCE_CHANGED;

        if (destinationIndex < 0) {
          Object raw = data.randomAccess ? data.source.get(ordinal) : sourceIterator.next();
          if (raw != current.point) return AuditResult.SOURCE_CHANGED;
          List<JumpPointAPI.JumpDestination> live = current.point.getDestinations();
          if (live == null || live.size() != current.destinationCount) {
            changedPoint = current.point;
            return AuditResult.POINT_CHANGED;
          }
          destinationIterator = live instanceof RandomAccess ? null : live.iterator();
          destinationIndex = 0;
          if (current.destinationCount == 0) {
            ordinal++;
            destinationIndex = -1;
          }
          return AuditResult.MORE;
        }

        Object liveDestination;
        List<JumpPointAPI.JumpDestination> live = current.point.getDestinations();
        if (live == null || live.size() != current.destinationCount) {
          changedPoint = current.point;
          return AuditResult.POINT_CHANGED;
        }
        liveDestination = live instanceof RandomAccess ? live.get(destinationIndex) : destinationIterator.next();
        if (!current.matches(destinationIndex, liveDestination)) {
          changedPoint = current.point;
          return AuditResult.POINT_CHANGED;
        }
        destinationIndex++;
        if (destinationIndex >= current.destinationCount) {
          ordinal++;
          destinationIndex = -1;
          destinationIterator = null;
        }
        return AuditResult.MORE;
      } catch (Throwable failure) {
        if (destinationIndex >= 0 && current != null) changedPoint = current.point;
        return destinationIndex < 0 ? AuditResult.SOURCE_CHANGED : AuditResult.POINT_CHANGED;
      }
    }
  }

  static final class PointCapture {
    final LocationState state;
    final JumpPointAPI point;
    final int ordinal;
    final List<JumpPointAPI.JumpDestination> live;
    final boolean randomAccess;
    final int size;
    final Object[] snapshot;
    final LocationAPI[] locations;
    final SegmentedIdentityMap<LocationAPI, Boolean> seen;
    final PointRecord provisional;
    Iterator<JumpPointAPI.JumpDestination> iterator;
    int cursor;
    int locationCount;

    PointCapture(LocationState state, JumpPointAPI point, int ordinal) {
      this.state = state;
      this.point = point;
      this.ordinal = ordinal;
      this.live = point.getDestinations();
      if (live == null) throw new IllegalStateException("JumpPoint destination list is null");
      this.randomAccess = live instanceof RandomAccess;
      this.size = live.size();
      this.snapshot = size == 0 ? PointRecord.EMPTY_OBJECTS : new Object[size * 3];
      this.locations = size == 0 ? PointRecord.EMPTY_LOCATIONS : new LocationAPI[size];
      this.seen = size > 8 ? new SegmentedIdentityMap<>(size) : null;
      this.provisional = new PointRecord(state, point, ordinal, size, snapshot, locations, 0);
      if (!randomAccess) iterator = live.iterator();
    }

    boolean complete() {
      return cursor >= size;
    }

    boolean captureOne() {
      if (live.size() != size) return false;
      JumpPointAPI.JumpDestination destination;
      try {
        destination = randomAccess ? live.get(cursor) : iterator.next();
      } catch (IndexOutOfBoundsException | NoSuchElementException | java.util.ConcurrentModificationException changed) {
        return false;
      }
      if (destination == null) throw new IllegalStateException("Null JumpDestination");
      SectorEntityToken target = destination.getDestination();
      LocationAPI location = target == null ? null : target.getContainingLocation();
      int offset = cursor * 3;
      snapshot[offset] = destination;
      snapshot[offset + 1] = target;
      snapshot[offset + 2] = location;
      StarsectorPrepatcherStrategicJumpIndex.destinationOwners.put(destination, provisional);
      if (target != null && addUnique(location)) locations[locationCount++] = location;
      cursor++;
      return true;
    }

    boolean stable() {
      try {
        return live.size() == size;
      } catch (Throwable failure) {
        return false;
      }
    }

    PointRecord finish() {
      provisional.locationCount = locationCount;
      return provisional;
    }

    private boolean addUnique(LocationAPI location) {
      if (seen != null) return seen.put(location, Boolean.TRUE) == null;
      for (int i = 0; i < locationCount; i++) {
        if (locations[i] == location) return false;
      }
      return true;
    }
  }
}
