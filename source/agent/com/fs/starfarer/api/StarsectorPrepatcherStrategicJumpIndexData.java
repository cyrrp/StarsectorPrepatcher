package com.fs.starfarer.api;

import com.fs.starfarer.api.StarsectorPrepatcherStrategicJumpIndexLocation.LocationState;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;

import java.lang.ref.WeakReference;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.RandomAccess;
import java.util.TreeSet;

/**
 * Materialized per-location index snapshot and the bounded identity
 * containers it is built from. IndexData instances are owned by
 * StarsectorPrepatcherStrategicJumpIndexLocation.LocationState; the single
 * process-wide destination owner cache instance lives on
 * StarsectorPrepatcherStrategicJumpIndex.
 */
final class StarsectorPrepatcherStrategicJumpIndexData {
  private StarsectorPrepatcherStrategicJumpIndexData() {}

  static final class PointRecord {
    static final Object[] EMPTY_OBJECTS = new Object[0];
    static final LocationAPI[] EMPTY_LOCATIONS = new LocationAPI[0];

    final LocationState state;
    final JumpPointAPI point;
    final int ordinal;
    final int destinationCount;
    final Object[] snapshot;
    final LocationAPI[] locations;
    int locationCount;

    PointRecord(LocationState state, JumpPointAPI point, int ordinal, int destinationCount, Object[] snapshot,
        LocationAPI[] locations, int locationCount) {
      this.state = state;
      this.point = point;
      this.ordinal = ordinal;
      this.destinationCount = destinationCount;
      this.snapshot = snapshot;
      this.locations = locations;
      this.locationCount = locationCount;
    }

    boolean matches(int index, Object rawDestination) {
      if (!(rawDestination instanceof JumpPointAPI.JumpDestination destination)) return false;
      int offset = index * 3;
      if (snapshot[offset] != destination) return false;
      SectorEntityToken target = destination.getDestination();
      if (snapshot[offset + 1] != target) return false;
      LocationAPI location = target == null ? null : target.getContainingLocation();
      return snapshot[offset + 2] == location;
    }
  }

  static final class IndexData {
    private static final int MERGE_CACHE_SIZE = 8;

    final List<?> source;
    final int size;
    final Object first;
    final Object last;
    final boolean randomAccess;
    final PointRecordTable records;
    final SegmentedIdentityMap<JumpPointAPI, PointRecord> byPoint;
    final SegmentedIdentityMap<LocationAPI, CandidateBucket> byDestination;
    final LocationAPI[] mergeDirectKeys = new LocationAPI[MERGE_CACHE_SIZE];
    final LocationAPI[] mergeFallbackKeys = new LocationAPI[MERGE_CACHE_SIZE];
    final List<?>[] mergeValues = new List<?>[MERGE_CACHE_SIZE];
    int mergeCursor;

    IndexData(List<?> source, int size, Object first, Object last, boolean randomAccess) {
      this.source = source;
      this.size = size;
      this.first = first;
      this.last = last;
      this.randomAccess = randomAccess;
      this.records = new PointRecordTable();
      this.byPoint = new SegmentedIdentityMap<>(size);
      this.byDestination = new SegmentedIdentityMap<>(size);
    }

    void addContribution(PointRecord record, LocationAPI location) {
      CandidateBucket bucket = byDestination.get(location);
      if (bucket == null) {
        bucket = new CandidateBucket();
        byDestination.put(location, bucket);
      }
      bucket.add(record);
    }

    void removeContribution(PointRecord record, LocationAPI location) {
      CandidateBucket bucket = byDestination.get(location);
      if (bucket == null) return;
      bucket.remove(record);
      if (bucket.isEmpty()) byDestination.remove(location);
    }

    @SuppressWarnings("rawtypes")
    List candidates(LocationAPI requested, LocationAPI fallback, boolean includeFallback) {
      CandidateBucket direct = byDestination.get(requested);
      if (!includeFallback || requested == fallback) {
        return direct == null ? StarsectorPrepatcherStrategicJumpIndex.DEFERRED : direct;
      }
      CandidateBucket fallbackBucket = byDestination.get(fallback);
      if (direct == null)
        return fallbackBucket == null ? StarsectorPrepatcherStrategicJumpIndex.DEFERRED : fallbackBucket;
      if (fallbackBucket == null || fallbackBucket == direct) return direct;
      for (int i = 0; i < MERGE_CACHE_SIZE; i++) {
        if (mergeDirectKeys[i] == requested && mergeFallbackKeys[i] == fallback) { return (List) mergeValues[i]; }
      }
      MergedCandidateList merged = new MergedCandidateList(direct, fallbackBucket);
      int slot = mergeCursor++ & (MERGE_CACHE_SIZE - 1);
      mergeDirectKeys[slot] = requested;
      mergeFallbackKeys[slot] = fallback;
      mergeValues[slot] = merged;
      return merged;
    }

    void clearMergeCache() {
      for (int i = 0; i < MERGE_CACHE_SIZE; i++) {
        mergeDirectKeys[i] = null;
        mergeFallbackKeys[i] = null;
        mergeValues[i] = null;
      }
      mergeCursor = 0;
    }
  }

  static final class CandidateBucket extends AbstractList<SectorEntityToken> implements RandomAccess {
    private static final Comparator<PointRecord> BY_ORDINAL = Comparator.comparingInt(record -> record.ordinal);
    final TreeSet<PointRecord> records = new TreeSet<>(BY_ORDINAL);

    void add(PointRecord record) {
      if (!records.add(record)) {
        PointRecord existing = records.ceiling(record);
        if (existing == null || existing.ordinal != record.ordinal || existing.point != record.point) {
          throw new IllegalStateException("Duplicate strategic jump ordinal");
        }
      }
    }

    void remove(PointRecord record) {
      records.remove(record);
    }

    @Override
    public SectorEntityToken get(int index) {
      if (index < 0 || index >= records.size()) throw new IndexOutOfBoundsException(index);
      int current = 0;
      for (PointRecord record : records) {
        if (current++ == index) return (SectorEntityToken) record.point;
      }
      throw new IndexOutOfBoundsException(index);
    }

    @Override
    public int size() {
      return records.size();
    }

    @Override
    public Iterator<SectorEntityToken> iterator() {
      Iterator<PointRecord> delegate = records.iterator();
      return new Iterator<>() {
        @Override
        public boolean hasNext() {
          return delegate.hasNext();
        }
        @Override
        public SectorEntityToken next() {
          return (SectorEntityToken) delegate.next().point;
        }
      };
    }
  }

  static final class MergedCandidateList extends AbstractList<SectorEntityToken> implements RandomAccess {
    final CandidateBucket first;
    final CandidateBucket second;
    int cachedSize = -1;

    MergedCandidateList(CandidateBucket first, CandidateBucket second) {
      this.first = first;
      this.second = second;
    }

    @Override
    public SectorEntityToken get(int index) {
      if (index < 0 || index >= size()) throw new IndexOutOfBoundsException(index);
      Iterator<SectorEntityToken> iterator = iterator();
      for (int i = 0; i < index; i++) iterator.next();
      return iterator.next();
    }

    @Override
    public int size() {
      int result = cachedSize;
      if (result < 0) {
        result = mergedSize(first.records.iterator(), second.records.iterator());
        cachedSize = result;
      }
      return result;
    }

    @Override
    public Iterator<SectorEntityToken> iterator() {
      return new MergeIterator(first.records.iterator(), second.records.iterator());
    }

    private static int mergedSize(Iterator<PointRecord> first, Iterator<PointRecord> second) {
      PointRecord a = first.hasNext() ? first.next() : null;
      PointRecord b = second.hasNext() ? second.next() : null;
      int count = 0;
      while (a != null || b != null) {
        count++;
        if (b == null || (a != null && a.ordinal < b.ordinal)) {
          a = first.hasNext() ? first.next() : null;
        } else if (a == null || b.ordinal < a.ordinal) {
          b = second.hasNext() ? second.next() : null;
        } else {
          a = first.hasNext() ? first.next() : null;
          b = second.hasNext() ? second.next() : null;
        }
      }
      return count;
    }
  }

  static final class MergeIterator implements Iterator<SectorEntityToken> {
    final Iterator<PointRecord> first;
    final Iterator<PointRecord> second;
    PointRecord a;
    PointRecord b;

    MergeIterator(Iterator<PointRecord> first, Iterator<PointRecord> second) {
      this.first = first;
      this.second = second;
      a = first.hasNext() ? first.next() : null;
      b = second.hasNext() ? second.next() : null;
    }

    @Override
    public boolean hasNext() {
      return a != null || b != null;
    }

    @Override
    public SectorEntityToken next() {
      if (!hasNext()) throw new NoSuchElementException();
      PointRecord result;
      if (b == null || (a != null && a.ordinal < b.ordinal)) {
        result = a;
        a = first.hasNext() ? first.next() : null;
      } else if (a == null || b.ordinal < a.ordinal) {
        result = b;
        b = second.hasNext() ? second.next() : null;
      } else {
        result = a;
        a = first.hasNext() ? first.next() : null;
        b = second.hasNext() ? second.next() : null;
      }
      return (SectorEntityToken) result.point;
    }
  }

  static final class PointRecordTable {
    private static final int SHIFT = 6;
    private static final int CHUNK = 1 << SHIFT;
    private final ArrayList<PointRecord[]> chunks = new ArrayList<>();

    void set(int index, PointRecord record) {
      int chunkIndex = index >> SHIFT;
      while (chunks.size() <= chunkIndex) chunks.add(new PointRecord[CHUNK]);
      chunks.get(chunkIndex)[index & (CHUNK - 1)] = record;
    }

    PointRecord get(int index) {
      int chunkIndex = index >> SHIFT;
      if (chunkIndex >= chunks.size()) return null;
      return chunks.get(chunkIndex)[index & (CHUNK - 1)];
    }
  }

  /** Identity map sharded so no resize ever copies the complete location index. */
  static final class SegmentedIdentityMap<K, V> {
    private final IdentityHashMap<K, V>[] segments;
    private final int mask;

    @SuppressWarnings("unchecked")
    SegmentedIdentityMap(int expectedSize) {
      int count = 4;
      while (count < 128 && count * 16 < Math.max(1, expectedSize)) count <<= 1;
      segments = (IdentityHashMap<K, V>[]) new IdentityHashMap<?, ?>[count];
      mask = count - 1;
    }

    V get(K key) {
      IdentityHashMap<K, V> segment = segments[index(key)];
      return segment == null ? null : segment.get(key);
    }

    V put(K key, V value) {
      int index = index(key);
      IdentityHashMap<K, V> segment = segments[index];
      if (segment == null) segments[index] = segment = new IdentityHashMap<>(8);
      return segment.put(key, value);
    }

    V remove(K key) {
      IdentityHashMap<K, V> segment = segments[index(key)];
      return segment == null ? null : segment.remove(key);
    }

    private int index(K key) {
      int hash = System.identityHashCode(key);
      hash ^= hash >>> 16;
      return hash & mask;
    }
  }

  /**
   * Four-way set-associative weak identity cache. It is intentionally bounded:
   * missed setter events are repaired by the incremental audit, while entries
   * from evicted locations cannot accumulate for the lifetime of the campaign.
   */
  static final class WeakIdentityOwnerCache {
    private static final int WAYS = 4;
    private final WeakReference<JumpPointAPI.JumpDestination>[] keys;
    private final WeakReference<PointRecord>[] values;
    private final int[] stamps;
    private final byte[] nextWay;
    private final int setMask;
    private int epoch = 1;

    @SuppressWarnings("unchecked")
    WeakIdentityOwnerCache(int requestedCapacity) {
      int sets = 1;
      int requestedSets = Math.max(1, requestedCapacity / WAYS);
      while (sets < requestedSets) sets <<= 1;
      keys = (WeakReference<JumpPointAPI.JumpDestination>[]) new WeakReference<?>[sets * WAYS];
      values = (WeakReference<PointRecord>[]) new WeakReference<?>[sets * WAYS];
      stamps = new int[sets * WAYS];
      nextWay = new byte[sets];
      setMask = sets - 1;
    }

    PointRecord get(JumpPointAPI.JumpDestination key) {
      if (key == null) return null;
      int base = setBase(key);
      for (int way = 0; way < WAYS; way++) {
        int slot = base + way;
        if (stamps[slot] != epoch) continue;
        WeakReference<JumpPointAPI.JumpDestination> keyReference = keys[slot];
        if (keyReference == null || keyReference.get() != key) continue;
        WeakReference<PointRecord> valueReference = values[slot];
        return valueReference == null ? null : valueReference.get();
      }
      return null;
    }

    void put(JumpPointAPI.JumpDestination key, PointRecord value) {
      if (key == null || value == null) return;
      int base = setBase(key);
      int selected = -1;
      for (int way = 0; way < WAYS; way++) {
        int slot = base + way;
        if (stamps[slot] != epoch) {
          if (selected < 0) selected = slot;
          continue;
        }
        WeakReference<JumpPointAPI.JumpDestination> keyReference = keys[slot];
        JumpPointAPI.JumpDestination existing = keyReference == null ? null : keyReference.get();
        WeakReference<PointRecord> valueReference = values[slot];
        if (existing == key) {
          values[slot] = new WeakReference<>(value);
          return;
        }
        if (selected < 0 && (existing == null || valueReference == null || valueReference.get() == null)) {
          selected = slot;
        }
      }
      if (selected < 0) {
        int set = base / WAYS;
        selected = base + (nextWay[set]++ & (WAYS - 1));
      }
      keys[selected] = new WeakReference<>(key);
      values[selected] = new WeakReference<>(value);
      stamps[selected] = epoch;
    }

    void clear() {
      epoch++;
      if (epoch == 0) {
        Arrays.fill(stamps, 0);
        epoch = 1;
      }
    }

    private int setBase(Object key) {
      int hash = System.identityHashCode(key);
      hash ^= hash >>> 16;
      return (hash & setMask) * WAYS;
    }
  }
}
