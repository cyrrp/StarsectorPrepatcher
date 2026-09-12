package com.fs.starfarer.api;

import com.starsector.prepatcher.agent.PrepatcherConfig;
import org.lwjgl.util.vector.Vector2f;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.RandomAccess;
import java.util.Set;

/** Per-thread scratch pools and snapshot helpers for hot game loops. */
final class StarsectorPrepatcherScratch {

  static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
  static final int SCRATCH_RETAINED_FRAME_FLOOR = 4;
  static volatile ScratchGaugeSnapshot SCRATCH_GAUGES = ScratchGaugeSnapshot.EMPTY;
  static void trimCurrentScratch(PrepatcherConfig c, long now) {
    if (!c.scratchTrimEnabled) {
      publishScratchGauges(SCRATCH.get());
      return;
    }
    Scratch scratch = SCRATCH.get();
    for (int i = scratch.scopeDepth; i < scratch.frames.size(); i++) {
      ScratchFrame frame = scratch.frames.get(i);
      if (frame.shouldTrim(c, now)) {
        countScratchTrimKinds(frame, c);
        scratch.frames.set(i, new ScratchFrame());
        StarsectorPrepatcherMetrics.SCRATCH_FRAME_REPLACEMENTS.increment();
      }
    }
    if (scratch.scopeDepth == 0 && scratch.frames.size() > SCRATCH_RETAINED_FRAME_FLOOR
        && scratch.trailingFramesLastUseNanos != Long.MIN_VALUE
        && now - scratch.trailingFramesLastUseNanos >= StarsectorPrepatcherCampaignCaches
            .millisToNanos(c.scratchTrimGraceMs)) {
      int removed = scratch.frames.size() - SCRATCH_RETAINED_FRAME_FLOOR;
      scratch.frames.subList(SCRATCH_RETAINED_FRAME_FLOOR, scratch.frames.size()).clear();
      scratch.trailingFramesLastUseNanos = Long.MIN_VALUE;
      StarsectorPrepatcherMetrics.SCRATCH_FRAME_REPLACEMENTS.add(removed);
    }
    publishScratchGauges(scratch);
  }
  static void countScratchTrimKinds(ScratchFrame frame, PrepatcherConfig c) {
    frame.refreshHighWater();
    if (frame.listHighWater > c.scratchTrimMaxListCapacity) {
      StarsectorPrepatcherMetrics.SCRATCH_LIST_TRIMS.increment();
    }
    if (frame.setHighWater > c.scratchTrimMaxSetEntries) {
      StarsectorPrepatcherMetrics.SCRATCH_SET_TRIMS.increment();
    }
    if (frame.identityHighWater > c.scratchTrimMaxIdentityEntries) {
      StarsectorPrepatcherMetrics.SCRATCH_IDENTITY_TRIMS.increment();
    }
  }
  static void publishScratchGauges(Scratch scratch) {
    long listCapacity = 0L;
    long setCapacity = 0L;
    long identityCapacity = 0L;
    int pooledLists = 0;
    int pooledSets = 0;
    for (ScratchFrame frame : scratch.frames) {
      listCapacity += frame.retainedListCapacityEstimate();
      setCapacity += frame.retainedSetCapacityEstimate();
      identityCapacity += frame.identityHighWater;
      pooledLists += frame.pooledLists.size();
      pooledSets += frame.pooledSets.size();
    }
    SCRATCH_GAUGES = new ScratchGaugeSnapshot(scratch.frames.size(), pooledLists, pooledSets, listCapacity, setCapacity,
        identityCapacity);
  }
  // ---------------------------------------------------------------------
  // Map reconciliation and scratch collection reuse
  // ---------------------------------------------------------------------

  /** Opens a re-entrancy-safe lease for pooled values used by one transformed method. */
  public static void beginScratchScope() {
    Scratch scratch = SCRATCH.get();
    int next = scratch.scopeDepth;
    scratch.frameAt(next);
    scratch.scopeDepth = next + 1;
    if (scratch.scopeDepth > scratch.maxDepthSinceIdle) {
      scratch.maxDepthSinceIdle = scratch.scopeDepth;
    }
  }
  /**
   * Closes the current lease. This hook is deliberately noexcept and idempotent:
   * it is also called by a generated catch-all handler.
   */
  public static void endScratchScope() {
    try {
      Scratch scratch = SCRATCH.get();
      if (scratch.scopeDepth <= 0) return;
      int index = --scratch.scopeDepth;
      ScratchFrame frame = scratch.frameAt(index);
      PrepatcherConfig c = StarsectorPrepatcherHooks.config;
      long now = c != null && c.scratchTrimEnabled ? StarsectorPrepatcherCampaignCaches.nanoTime() : 0L;
      frame.clear(c, now);
      if (c != null && c.scratchTrimEnabled && frame.shouldTrim(c, now)) {
        countScratchTrimKinds(frame, c);
        scratch.frames.set(index, new ScratchFrame());
        StarsectorPrepatcherMetrics.SCRATCH_FRAME_REPLACEMENTS.increment();
      }
      if (scratch.scopeDepth == 0) {
        if (c != null && c.scratchTrimEnabled && scratch.maxDepthSinceIdle > SCRATCH_RETAINED_FRAME_FLOOR) {
          scratch.trailingFramesLastUseNanos = now;
        }
        scratch.maxDepthSinceIdle = 0;
        if (scratch.removeWhenIdle) SCRATCH.remove();
      }
    } catch (Throwable ignored) {
      // Never mask an original return value or exception.
    }
  }
  /**
   * Replaces the exact H.renderStuff() reconciliation call.
   *
   * Vanilla invokes LinkedHashMap.keySet().retainAll(ArrayList), which scans the
   * ArrayList once for every icon key. The map keys are concrete campaign-entity
   * instances; all vanilla 0.98a-RC8 CampaignEntity implementations inherit
   * Object.equals/hashCode, so identity membership is the native domain semantic.
   *
   * A reusable IdentityHashMap avoids entity hashCode implementations for vanilla
   * entities and the explicit iterator avoids the generic AbstractCollection path.
   * Modded entity classes overriding equals/hashCode use a reusable HashSet so the
   * original Java collection semantics are retained.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static boolean retainAllFast(Set target, Collection keep, Object mapOwner) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.mapRenderStuff) { return target.retainAll(keep); }

    ScratchFrame scratch = SCRATCH.get().scopeFrame();
    IdentityHashMap<Object, Boolean> membership = scratch.identityMembership;
    clearIdentityMembership(membership);
    boolean customEquality = false;
    if (keep instanceof List list) {
      for (int i = 0, size = list.size(); i < size; i++) {
        Object entity = list.get(i);
        membership.put(entity, Boolean.TRUE);
        if (entity != null && CUSTOM_EQUALITY.get(entity.getClass())) customEquality = true;
      }
    } else {
      for (Object entity : keep) {
        membership.put(entity, Boolean.TRUE);
        if (entity != null && CUSTOM_EQUALITY.get(entity.getClass())) customEquality = true;
      }
    }

    scratch.observeIdentitySize(membership.size());

    if (!customEquality) {
      for (Object entity : target) {
        if (entity != null && CUSTOM_EQUALITY.get(entity.getClass())) {
          customEquality = true;
          break;
        }
      }
    }

    Set<Object> equalityMembership = null;
    if (customEquality) {
      // Preserve equals/hashCode semantics for modded entity classes that
      // override Object equality. Vanilla entities stay on the faster
      // identity path. LinkedHashMap keys already require a valid hashCode
      // contract, so HashSet membership matches normal Java collection rules.
      equalityMembership = scratch.equalityMembership;
      equalityMembership.clear();
      equalityMembership.addAll(keep);
      scratch.observeRetainedSet(equalityMembership);
      StarsectorPrepatcherMetrics.RETAIN_EQUALITY_FALLBACKS.increment();
    }

    StarsectorPrepatcherMetrics.RETAIN_CALLS.increment();
    StarsectorPrepatcherMetrics.RETAIN_UPPER_BOUND.add((long) target.size() * (long) keep.size());
    StarsectorPrepatcherMetrics.RETAIN_KEYS.add(target.size());

    boolean changed = false;
    long removed = 0L;
    try {
      Iterator iterator = target.iterator();
      while (iterator.hasNext()) {
        Object entity = iterator.next();
        boolean present = customEquality ? equalityMembership.contains(entity) : membership.containsKey(entity);
        if (!present) {
          iterator.remove();
          changed = true;
          removed++;
        }
      }
      if (removed != 0L) StarsectorPrepatcherMetrics.RETAIN_REMOVED.add(removed);
      return changed;
    } finally {
      clearIdentityMembership(membership);
      if (equalityMembership != null) equalityMembership.clear();
    }
  }
  /**
   * IdentityHashMap.clear() scans its full capacity even when size is zero.
   * Scratch frames use a deliberately large membership table, so an empty clear
   * at every scope exit is far more expensive than the constant-time size check.
   */
  static void clearIdentityMembership(IdentityHashMap<?, ?> membership) {
    if (!membership.isEmpty()) membership.clear();
  }
  static final ClassValue<Boolean> CUSTOM_EQUALITY = new ClassValue<>() {
    @Override
    protected Boolean computeValue(Class<?> type) {
      try {
        Method equals = type.getMethod("equals", Object.class);
        Method hashCode = type.getMethod("hashCode");
        return equals.getDeclaringClass() != Object.class || hashCode.getDeclaringClass() != Object.class;
      } catch (ReflectiveOperationException | SecurityException ex) {
        // Unknown/custom class shape: prefer collection semantics over the
        // identity optimization.
        return Boolean.TRUE;
      }
    }
  };
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static ArrayList borrowEntityList(Collection source) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.mapRenderStuff) return new ArrayList(source);
    ArrayList list = SCRATCH.get().scopeFrame().entityList;
    list.clear();
    list.addAll(source);
    SCRATCH.get().scopeFrame().observeRetainedList(list);
    return list;
  }
  /**
   * Reuses the defensive snapshots taken by BaseLocation.advance(). The
   * returned object is deliberately exposed only as List: transformed sites
   * are structurally proven to iterate it without mutation or escape.
   * Collection.toArray(T[]) preserves the vanilla point-in-time copy while
   * reusing the backing array after the first capacity warm-up.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static List borrowLocationAdvanceSnapshot(Collection source) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.campaignSnapshotReuse) return new ArrayList(source);
    return borrowCampaignSnapshot(source);
  }
  /** Same snapshot contract for BaseLocation.advanceEvenIfPaused(). */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static List borrowPausedLocationSnapshot(Collection source) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.campaignSnapshotReuse) return new ArrayList(source);
    return borrowCampaignSnapshot(source);
  }
  @SuppressWarnings({"rawtypes", "unchecked"})
  static List borrowCampaignSnapshot(Collection source) {
    ScratchFrame frame = SCRATCH.get().scopeFrame();
    SnapshotList snapshot = frame.campaignSnapshotAt(frame.campaignSnapshotIndex++);
    snapshot.copyFrom(source);
    frame.observeListSize(snapshot.size());
    return snapshot;
  }
  /**
   * Reuses BaseCampaignEntity.runScripts()' defensive copy. Empty script
   * lists use the JDK's allocation-free immutable empty list; non-empty lists
   * keep the same point-in-time iteration semantics as the vanilla copy.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static List borrowEntityScriptSnapshot(Collection source) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.entityScriptSnapshotReuse) return new ArrayList(source);
    if (source.isEmpty()) return Collections.emptyList();
    return borrowCampaignSnapshot(source);
  }
  /** Legacy helper; 0.7.1 uses an inline whole-method empty-work guard instead. */
  @SuppressWarnings("rawtypes")
  public static Iterator memoryExpireIterator(List source) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c != null && c.emptyMemoryAdvanceFastPath && source.isEmpty()) { return Collections.emptyIterator(); }
    return source.iterator();
  }
  /** Legacy helper; 0.7.1 uses an inline whole-method empty-work guard instead. */
  @SuppressWarnings("rawtypes")
  public static Iterator memoryRequireIterator(Collection source) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c != null && c.emptyMemoryAdvanceFastPath && source.isEmpty()) { return Collections.emptyIterator(); }
    return source.iterator();
  }
  /**
   * Exact List.add replacement used only at structurally proven scratch-local
   * mutation sites. The vanilla concrete collection type and boolean result are
   * preserved; peak observation is allocation-free and does not read the clock.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static boolean scratchListAdd(List receiver, Object value) {
    boolean changed = receiver.add(value);
    if (changed) observeScratchListPeak(receiver);
    return changed;
  }
  /** Exact Set.add replacement for structurally proven scratch-local sites. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static boolean scratchSetAdd(Set receiver, Object value) {
    boolean changed = receiver.add(value);
    if (changed) observeScratchSetPeak(receiver);
    return changed;
  }
  /** Exact Set.addAll replacement for structurally proven scratch-local sites. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static boolean scratchSetAddAll(Set receiver, Collection values) {
    boolean changed = receiver.addAll(values);
    if (changed) observeScratchSetPeak(receiver);
    return changed;
  }
  static void observeScratchListPeak(Collection<?> receiver) {
    try {
      Scratch scratch = SCRATCH.get();
      if (scratch.scopeDepth > 0) scratch.scopeFrame().observeRetainedList(receiver);
    } catch (Throwable ignored) {
      // Telemetry/trimming must never alter the original collection operation.
    }
  }
  static void observeScratchSetPeak(Collection<?> receiver) {
    try {
      Scratch scratch = SCRATCH.get();
      if (scratch.scopeDepth > 0) scratch.scopeFrame().observeRetainedSet(receiver);
    } catch (Throwable ignored) {
      // Telemetry/trimming must never alter the original collection operation.
    }
  }
  /** Three frame-local command/group lists in Ship.advance(). */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static ArrayList borrowShipList() {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.shipAdvanceScratch) return new ArrayList();
    StarsectorPrepatcherMetrics.SHIP_SCRATCH_LISTS.increment();
    return SCRATCH.get().scopeFrame().pooledList();
  }
  /** The eager command snapshot in Ship.advance(); listener snapshot remains vanilla. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static ArrayList borrowShipListSnapshot(Collection source) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.shipAdvanceScratch) return new ArrayList(source);
    ScratchFrame frame = SCRATCH.get().scopeFrame();
    ArrayList result = frame.pooledList();
    result.addAll(source);
    frame.observeRetainedList(result);
    StarsectorPrepatcherMetrics.SHIP_SCRATCH_LISTS.increment();
    return result;
  }
  /** Two frame-local sets in Ship.advance(). */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static HashSet borrowShipSet() {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.shipAdvanceScratch) return new HashSet();
    StarsectorPrepatcherMetrics.SHIP_SCRATCH_SETS.increment();
    return SCRATCH.get().scopeFrame().pooledSet();
  }
  /** Reuses DynamicParticleGroup.advance()'s deferred-removal list. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static ArrayList borrowParticleRemovalList() {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.particleCleanup) return new ArrayList();
    StarsectorPrepatcherMetrics.PARTICLE_GROUPS.increment();
    return SCRATCH.get().scopeFrame().pooledList();
  }
  /**
   * Linear identity cleanup for ordinary particle classes, with exact vanilla
   * removeAll fallback for any class that overrides equals/hashCode. The first
   * advance pass remains complete before any removal, preserving callback
   * visibility and survivor order.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static boolean removeExpiredParticles(LinkedList particles, Collection expired) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.particleCleanup || expired.size() < 4) { return particles.removeAll(expired); }
    StarsectorPrepatcherMetrics.PARTICLE_EXPIRED.add(expired.size());

    // All compatibility checks complete before the first mutation. If a
    // custom equals/hashCode implementation or malformed collection throws,
    // the untouched vanilla list still receives the exact removeAll call.
    try {
      for (Object value : expired) {
        if (value != null && CUSTOM_EQUALITY.get(value.getClass())) { return particles.removeAll(expired); }
      }
      for (Object value : particles) {
        if (value != null && CUSTOM_EQUALITY.get(value.getClass())) { return particles.removeAll(expired); }
      }
    } catch (Throwable ignored) {
      return particles.removeAll(expired);
    }

    IdentityHashMap<Object, Boolean> membership = SCRATCH.get().scopeFrame().identityMembership;
    clearIdentityMembership(membership);
    try {
      for (Object value : expired) membership.put(value, Boolean.TRUE);
      SCRATCH.get().scopeFrame().observeIdentitySize(membership.size());
      boolean changed = false;
      Iterator iterator = particles.iterator();
      while (iterator.hasNext()) {
        if (membership.containsKey(iterator.next())) {
          iterator.remove();
          changed = true;
        }
      }
      StarsectorPrepatcherMetrics.PARTICLE_LINEAR_REMOVALS.increment();
      return changed;
    } finally {
      clearIdentityMembership(membership);
    }
  }
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static HashSet borrowClassSet() {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.mapRenderStuff) return new HashSet();
    HashSet set = SCRATCH.get().scopeFrame().classSet;
    set.clear();
    return set;
  }
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static ArrayList borrowHitList(Collection source) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.mapHitTest) return new ArrayList(source);
    ScratchFrame frame = SCRATCH.get().scopeFrame();
    ArrayList list = frame.hitList;
    list.clear();
    list.addAll(source);
    frame.observeRetainedList(list);
    return list;
  }
  public static Vector2f borrowHitPoint(float x, float y) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.mapHitTest) return new Vector2f(x, y);
    Vector2f vector = SCRATCH.get().scopeFrame().hitPoint;
    vector.set(x, y);
    return vector;
  }
  public static Vector2f borrowArrowVector(float x, float y) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.intelArrowRendering) return new Vector2f(x, y);
    ScratchFrame scratch = SCRATCH.get().scopeFrame();
    Vector2f vector = scratch.arrowVectors[scratch.arrowVectorIndex++ & (scratch.arrowVectors.length - 1)];
    vector.set(x, y);
    return vector;
  }
  static final class Scratch {
    final ArrayList<ScratchFrame> frames = new ArrayList<>(2);
    int scopeDepth;
    int maxDepthSinceIdle;
    long trailingFramesLastUseNanos = Long.MIN_VALUE;
    boolean removeWhenIdle;

    ScratchFrame frameAt(int index) {
      while (frames.size() <= index) frames.add(new ScratchFrame());
      return frames.get(index);
    }

    ScratchFrame scopeFrame() {
      return frameAt(Math.max(0, scopeDepth - 1));
    }
  }
  static final class ScratchFrame {
    ArrayList<Object> entityList = new ArrayList<>(1024);
    int entityListHighWater = 1024;
    ArrayList<Object> hitList = new ArrayList<>(1024);
    int hitListHighWater = 1024;
    HashSet<Object> classSet = new HashSet<>(16);
    int classSetHighWater = 16;
    IdentityHashMap<Object, Boolean> identityMembership = new IdentityHashMap<>(2048);
    int identityMembershipHighWater = 2048;
    HashSet<Object> equalityMembership = new HashSet<>(2048);
    int equalityMembershipHighWater = 2048;
    HashSet<Object> containsSet = new HashSet<>(512);
    int containsSetHighWater = 512;
    Collection<?> containsSource;
    int containsSourceSize = -1;
    ArrayList<Object> labelCandidates = new ArrayList<>(256);
    int labelCandidatesHighWater = 256;
    final Vector2f hitPoint = new Vector2f();
    final Vector2f[] arrowVectors = new Vector2f[]{new Vector2f(), new Vector2f(), new Vector2f(), new Vector2f(),
        new Vector2f(), new Vector2f(), new Vector2f(), new Vector2f()};
    int arrowVectorIndex;
    ArrayList<SnapshotList> campaignSnapshots = new ArrayList<>(3);
    int campaignSnapshotIndex;
    ArrayList<ArrayList<Object>> pooledLists = new ArrayList<>(8);
    ArrayList<Integer> pooledListHighWaters = new ArrayList<>(8);
    int pooledListIndex;
    ArrayList<HashSet<Object>> pooledSets = new ArrayList<>(4);
    ArrayList<Integer> pooledSetHighWaters = new ArrayList<>(4);
    int pooledSetIndex;
    int listHighWater = 1024;
    int setHighWater = 2048;
    int identityHighWater = 2048;
    boolean listOversizedTouched;
    boolean setOversizedTouched;
    boolean identityOversizedTouched;
    long oversizedLastUseNanos = Long.MIN_VALUE;

    ArrayList<Object> pooledList() {
      PrepatcherConfig c = StarsectorPrepatcherHooks.config;
      int limit = c == null ? 32 : c.scratchTrimMaxPooledCollections;
      if (pooledListIndex >= limit) return new ArrayList<>();
      while (pooledLists.size() <= pooledListIndex) {
        pooledLists.add(new ArrayList<>());
        pooledListHighWaters.add(0);
      }
      ArrayList<Object> result = pooledLists.get(pooledListIndex++);
      if (!result.isEmpty()) result.clear();
      return result;
    }

    HashSet<Object> pooledSet() {
      PrepatcherConfig c = StarsectorPrepatcherHooks.config;
      int limit = c == null ? 32 : c.scratchTrimMaxPooledCollections;
      if (pooledSetIndex >= limit) return new HashSet<>();
      while (pooledSets.size() <= pooledSetIndex) {
        pooledSets.add(new HashSet<>());
        pooledSetHighWaters.add(0);
      }
      HashSet<Object> result = pooledSets.get(pooledSetIndex++);
      if (!result.isEmpty()) result.clear();
      return result;
    }

    SnapshotList campaignSnapshotAt(int index) {
      while (campaignSnapshots.size() <= index) {
        campaignSnapshots.add(new SnapshotList());
      }
      return campaignSnapshots.get(index);
    }

    void observeRetainedList(Collection<?> list) {
      if (list == null) return;
      int size = list.size();
      if (list == entityList) entityListHighWater = Math.max(entityListHighWater, size);
      else if (list == hitList) hitListHighWater = Math.max(hitListHighWater, size);
      else if (list == labelCandidates) {
        labelCandidatesHighWater = Math.max(labelCandidatesHighWater, size);
      } else {
        for (int i = 0; i < pooledLists.size(); i++) {
          if (list == pooledLists.get(i)) {
            pooledListHighWaters.set(i, Math.max(pooledListHighWaters.get(i), size));
            break;
          }
        }
      }
      observeListSize(size);
    }

    void observeRetainedSet(Collection<?> set) {
      if (set == null) return;
      int size = set.size();
      if (set == classSet) classSetHighWater = Math.max(classSetHighWater, size);
      else if (set == equalityMembership) {
        equalityMembershipHighWater = Math.max(equalityMembershipHighWater, size);
      } else if (set == containsSet) {
        containsSetHighWater = Math.max(containsSetHighWater, size);
      } else {
        for (int i = 0; i < pooledSets.size(); i++) {
          if (set == pooledSets.get(i)) {
            pooledSetHighWaters.set(i, Math.max(pooledSetHighWaters.get(i), size));
            break;
          }
        }
      }
      observeSetSize(size);
    }

    void observeListSize(int size) {
      if (size > listHighWater) listHighWater = size;
      PrepatcherConfig c = StarsectorPrepatcherHooks.config;
      if (c != null && size > c.scratchTrimMaxListCapacity) {
        listOversizedTouched = true;
      }
    }

    void observeSetSize(int size) {
      if (size > setHighWater) setHighWater = size;
      PrepatcherConfig c = StarsectorPrepatcherHooks.config;
      if (c != null && size > c.scratchTrimMaxSetEntries) {
        setOversizedTouched = true;
      }
    }

    void observeIdentitySize(int size) {
      identityMembershipHighWater = Math.max(identityMembershipHighWater, size);
      if (size > identityHighWater) identityHighWater = size;
      PrepatcherConfig c = StarsectorPrepatcherHooks.config;
      if (c != null && size > c.scratchTrimMaxIdentityEntries) {
        identityOversizedTouched = true;
      }
    }

    void refreshHighWater() {
      listHighWater = Math.max(entityListHighWater, hitListHighWater);
      listHighWater = Math.max(listHighWater, labelCandidatesHighWater);
      for (SnapshotList snapshot : campaignSnapshots) {
        listHighWater = Math.max(listHighWater, snapshot.retainedCapacity());
      }
      for (Integer peak : pooledListHighWaters) {
        listHighWater = Math.max(listHighWater, peak);
      }
      setHighWater = Math.max(classSetHighWater, equalityMembershipHighWater);
      setHighWater = Math.max(setHighWater, containsSetHighWater);
      for (Integer peak : pooledSetHighWaters) {
        setHighWater = Math.max(setHighWater, peak);
      }
      identityHighWater = Math.max(identityHighWater, identityMembershipHighWater);
    }

    private boolean consumeOversizedUse() {
      boolean touched = listOversizedTouched || setOversizedTouched || identityOversizedTouched;
      listOversizedTouched = false;
      setOversizedTouched = false;
      identityOversizedTouched = false;
      for (SnapshotList snapshot : campaignSnapshots) {
        touched |= snapshot.consumeOversizedTouch();
      }
      return touched;
    }

    boolean shouldTrim(PrepatcherConfig c, long now) {
      refreshHighWater();
      boolean oversized = listHighWater > c.scratchTrimMaxListCapacity || setHighWater > c.scratchTrimMaxSetEntries
          || identityHighWater > c.scratchTrimMaxIdentityEntries
          || pooledLists.size() > c.scratchTrimMaxPooledCollections
          || pooledSets.size() > c.scratchTrimMaxPooledCollections;
      if (!oversized || oversizedLastUseNanos == Long.MIN_VALUE) return false;
      return now - oversizedLastUseNanos >= StarsectorPrepatcherCampaignCaches.millisToNanos(c.scratchTrimGraceMs);
    }

    long retainedListCapacityEstimate() {
      long total = entityListHighWater + hitListHighWater + labelCandidatesHighWater;
      total += 3L + 8L + 4L; // known initial capacities of retained list containers
      for (SnapshotList snapshot : campaignSnapshots) total += snapshot.retainedCapacity();
      for (Integer peak : pooledListHighWaters) total += peak;
      return total;
    }

    long retainedSetCapacityEstimate() {
      long total = classSetHighWater + equalityMembershipHighWater + containsSetHighWater;
      for (Integer peak : pooledSetHighWaters) total += peak;
      return total;
    }

    void clear(PrepatcherConfig c, long now) {
      refreshHighWater();
      if (c != null && c.scratchTrimEnabled && consumeOversizedUse()) {
        oversizedLastUseNanos = now;
      }
      if (!entityList.isEmpty()) entityList.clear();
      if (!hitList.isEmpty()) hitList.clear();
      if (!classSet.isEmpty()) classSet.clear();
      clearIdentityMembership(identityMembership);
      if (!equalityMembership.isEmpty()) equalityMembership.clear();
      if (!containsSet.isEmpty()) containsSet.clear();
      containsSource = null;
      containsSourceSize = -1;
      if (!labelCandidates.isEmpty()) labelCandidates.clear();
      arrowVectorIndex = 0;
      for (int i = 0; i < campaignSnapshotIndex; i++) {
        campaignSnapshots.get(i).clearSnapshot();
      }
      campaignSnapshotIndex = 0;
      for (int i = 0; i < pooledListIndex && i < pooledLists.size(); i++) {
        ArrayList<Object> list = pooledLists.get(i);
        if (!list.isEmpty()) list.clear();
      }
      pooledListIndex = 0;
      for (int i = 0; i < pooledSetIndex && i < pooledSets.size(); i++) {
        HashSet<Object> set = pooledSets.get(i);
        if (!set.isEmpty()) set.clear();
      }
      pooledSetIndex = 0;
    }
  }
  record ScratchGaugeSnapshot(int frameCount, int pooledListCount, int pooledSetCount, long retainedListCapacity,
      long retainedSetCapacity, long retainedIdentityCapacity) {
    static final ScratchGaugeSnapshot EMPTY = new ScratchGaugeSnapshot(0, 0, 0, 0L, 0L, 0L);
  }
  /** Array-backed, reusable read-only snapshot used only by verified loops. */
  static final class SnapshotList extends AbstractList<Object> implements RandomAccess {
    private Object[] elements = new Object[0];
    private int size;
    private final ArrayList<SnapshotIterator> iterators = new ArrayList<>(2);
    private int iteratorIndex;
    private boolean oversizedTouched;

    void copyFrom(Collection<?> source) {
      int oldSize = size;
      try {
        int newSize = source.size();
        Object[] current = elements;
        Object[] copied = source.toArray(current);
        boolean reused = copied == current;
        int copiedSize = reused ? newSize : copied.length;
        if (reused && newSize < oldSize) {
          Arrays.fill(current, newSize, oldSize, null);
        }
        elements = copied;
        size = copiedSize;
        PrepatcherConfig c = StarsectorPrepatcherHooks.config;
        if (c != null && copiedSize > c.scratchTrimMaxListCapacity) {
          // Snapshot peak is observed before scope close even if later cleared.
          oversizedTouched = true;
        }
        iteratorIndex = 0;
        modCount++;
      } catch (Throwable ex) {
        // A custom Collection may fail after partially populating the
        // supplied array. Never let that exceptional path pin campaign
        // objects in the process-lifetime ThreadLocal.
        if (elements != null) Arrays.fill(elements, null);
        size = 0;
        iteratorIndex = 0;
        modCount++;
        throw propagate(ex);
      }
    }

    void clearSnapshot() {
      if (size > 0) Arrays.fill(elements, 0, size, null);
      size = 0;
      iteratorIndex = 0;
      modCount++;
    }

    int retainedCapacity() {
      return elements == null ? 0 : elements.length;
    }

    boolean consumeOversizedTouch() {
      boolean result = oversizedTouched;
      oversizedTouched = false;
      return result;
    }

    @Override
    public Object get(int index) {
      if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
      return elements[index];
    }

    @Override
    public int size() {
      return size;
    }

    @Override
    public Iterator<Object> iterator() {
      while (iterators.size() <= iteratorIndex) {
        iterators.add(new SnapshotIterator());
      }
      SnapshotIterator result = iterators.get(iteratorIndex++);
      result.reset();
      return result;
    }

    private final class SnapshotIterator implements Iterator<Object> {
      private int cursor;
      private int expectedModCount;

      void reset() {
        cursor = 0;
        expectedModCount = modCount;
      }

      @Override
      public boolean hasNext() {
        return cursor != size;
      }

      @Override
      public Object next() {
        if (expectedModCount != modCount) throw new ConcurrentModificationException();
        if (cursor >= size) throw new NoSuchElementException();
        return elements[cursor++];
      }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException propagate(Throwable ex) throws T {
      throw (T) ex;
    }
  }
}
