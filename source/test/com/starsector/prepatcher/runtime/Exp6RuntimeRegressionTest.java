package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherHooks;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.lang.management.ManagementFactory;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;

/** Runtime checks for exp6 allocation patches and the exp7 scratch-cleanup regression fix. */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class Exp6RuntimeRegressionTest {
  private Exp6RuntimeRegressionTest() {}

  public static void main(String[] args) throws Exception {
    rejectDisabledExplicitGc();
    PrepatcherConfig enabled = config(true);
    installConfig(enabled);
    try {
      assertIdentityMembershipCleanup();
      assertSnapshotIsolationAndReuse();
      assertSnapshotReentrancy();
      assertSnapshotFallback();
      installConfig(enabled);
      assertMemoryIteratorMatrix();
      assertSnapshotReachability();
      assertExceptionalSnapshotReachability();
      assertEmptyIteratorReachability();
    } finally {
      drainScratchScopes();
      installConfig(null);
    }

    System.out.println("OK exp6-runtime snapshot-isolation snapshot-reuse snapshot-reentrancy"
        + " snapshot-fallback snapshot-no-retention empty-snapshot-singleton" + " exceptional-snapshot-no-retention"
        + " memory-iterator-empty-singleton memory-iterator-order"
        + " memory-iterator-remove memory-iterator-no-retention"
        + " empty-identity-clear-elision retain-empty-clear-elision"
        + " identity-normal-cleanup identity-exceptional-cleanup" + " identity-nested-cleanup");
  }

  private static void assertIdentityMembershipCleanup() throws Exception {
    assertEmptyScopeDoesNotClearIdentityMap();
    assertEmptyRetainDoesNotClearIdentityMap();
    assertNormalRetainClearsIdentityMap();
    assertExceptionalRetainClearsIdentityMap();
    assertNestedIdentityCleanup();
  }

  private static void assertEmptyScopeDoesNotClearIdentityMap() throws Exception {
    Iterator<?> iterator;
    StarsectorPrepatcherHooks.beginScratchScope();
    try {
      IdentityHashMap<Object, Boolean> membership = scratchIdentityMembership(0);
      require(membership.isEmpty(), "identity membership was not empty before empty scope test");
      iterator = membership.keySet().iterator();
    } finally {
      StarsectorPrepatcherHooks.endScratchScope();
    }
    assertUnmodifiedEmptyIterator(iterator, "empty scratch scope invoked IdentityHashMap.clear()");
  }

  private static void assertEmptyRetainDoesNotClearIdentityMap() throws Exception {
    Iterator<?> iterator;
    Set<Object> target = new LinkedHashSet<>(List.of(new Object(), new Object()));
    StarsectorPrepatcherHooks.beginScratchScope();
    try {
      IdentityHashMap<Object, Boolean> membership = scratchIdentityMembership(0);
      require(membership.isEmpty(), "identity membership was not empty before empty retain test");
      iterator = membership.keySet().iterator();
      require(StarsectorPrepatcherHooks.retainAllFast(target, List.of(), null),
          "empty retain did not report removal of target values");
      require(target.isEmpty(), "empty retain left target values behind");
    } finally {
      StarsectorPrepatcherHooks.endScratchScope();
    }
    assertUnmodifiedEmptyIterator(iterator, "empty retain path invoked IdentityHashMap.clear()");
  }

  private static void assertNormalRetainClearsIdentityMap() throws Exception {
    Object kept = new Object();
    Object removed = new Object();
    Set<Object> target = new LinkedHashSet<>(List.of(kept, removed));

    StarsectorPrepatcherHooks.beginScratchScope();
    try {
      IdentityHashMap<Object, Boolean> membership = scratchIdentityMembership(0);
      require(StarsectorPrepatcherHooks.retainAllFast(target, List.of(kept), null),
          "normal retain did not report removal");
      require(target.equals(Set.of(kept)), "normal retain changed collection semantics");
      require(membership.isEmpty(), "normal retain left campaign values in identity membership");
    } finally {
      StarsectorPrepatcherHooks.endScratchScope();
    }
  }

  private static void assertExceptionalRetainClearsIdentityMap() throws Exception {
    Object value = new Object();
    IdentityHashMap<Object, Boolean> membership;

    StarsectorPrepatcherHooks.beginScratchScope();
    try {
      membership = scratchIdentityMembership(0);
      try {
        StarsectorPrepatcherHooks.retainAllFast(new LinkedHashSet<>(), new PartiallyReadableList(value), null);
        throw new AssertionError("partially readable keep list did not throw");
      } catch (RetainProbeFailure expected) {
        require(membership.containsKey(value), "exceptional retain did not populate the identity map before failing");
      }
    } finally {
      StarsectorPrepatcherHooks.endScratchScope();
    }
    require(membership.isEmpty(), "exceptional retain scope retained a campaign value");
  }

  private static void assertNestedIdentityCleanup() throws Exception {
    Object outerValue = new Object();
    IdentityHashMap<Object, Boolean> outer;
    Iterator<?> innerIterator;

    StarsectorPrepatcherHooks.beginScratchScope();
    try {
      outer = scratchIdentityMembership(0);
      outer.put(outerValue, Boolean.TRUE);
      StarsectorPrepatcherHooks.beginScratchScope();
      try {
        IdentityHashMap<Object, Boolean> inner = scratchIdentityMembership(1);
        require(inner.isEmpty(), "nested identity frame started populated");
        innerIterator = inner.keySet().iterator();
      } finally {
        StarsectorPrepatcherHooks.endScratchScope();
      }
      assertUnmodifiedEmptyIterator(innerIterator, "empty nested scope invoked IdentityHashMap.clear()");
      require(outer.containsKey(outerValue), "nested scope cleared the active outer identity frame");
    } finally {
      StarsectorPrepatcherHooks.endScratchScope();
    }
    require(outer.isEmpty(), "outer identity frame was not cleared on exit");
  }

  @SuppressWarnings("unchecked")
  private static IdentityHashMap<Object, Boolean> scratchIdentityMembership(int frameIndex) throws Exception {
    Field scratchField = Class.forName("com.fs.starfarer.api.StarsectorPrepatcherScratch").getDeclaredField("SCRATCH");
    scratchField.setAccessible(true);
    ThreadLocal<?> threadLocal = (ThreadLocal<?>) scratchField.get(null);
    Object scratch = threadLocal.get();
    Method frameAt = scratch.getClass().getDeclaredMethod("frameAt", int.class);
    frameAt.setAccessible(true);
    Object frame = frameAt.invoke(scratch, frameIndex);
    Field membership = frame.getClass().getDeclaredField("identityMembership");
    membership.setAccessible(true);
    return (IdentityHashMap<Object, Boolean>) membership.get(frame);
  }

  private static void assertUnmodifiedEmptyIterator(Iterator<?> iterator, String message) {
    try {
      iterator.next();
      throw new AssertionError("empty identity iterator unexpectedly returned a value");
    } catch (NoSuchElementException expected) {
      // IdentityHashMap.clear() increments modCount even when already empty.
      // NoSuchElementException proves that no such structural clear occurred.
    } catch (ConcurrentModificationException ex) {
      throw new AssertionError(message, ex);
    }
  }

  private static void assertSnapshotIsolationAndReuse() {
    Object firstValue = new Object();
    Object secondValue = new Object();
    ArrayList<Object> source = new ArrayList<>(List.of(firstValue, secondValue));
    List<?> first;
    List<?> second;
    List<?> entityScripts;

    StarsectorPrepatcherHooks.beginScratchScope();
    try {
      List<?> emptyScripts = StarsectorPrepatcherHooks.borrowEntityScriptSnapshot(List.of());
      require(emptyScripts == Collections.emptyList(),
          "empty entity-script snapshot did not return Collections.emptyList()");
      require(emptyScripts == StarsectorPrepatcherHooks.borrowEntityScriptSnapshot(List.of()),
          "empty entity-script snapshots did not share the allocation-free singleton");
      assertReadOnly(emptyScripts, "empty entity-script snapshot");

      first = StarsectorPrepatcherHooks.borrowLocationAdvanceSnapshot(source);
      require(first != source && first.equals(List.of(firstValue, secondValue)),
          "location snapshot did not capture the source contents");
      assertReadOnly(first, "location snapshot");

      source.clear();
      source.add(secondValue);
      require(first.equals(List.of(firstValue, secondValue)),
          "source mutation changed an already-borrowed location snapshot");

      second = StarsectorPrepatcherHooks.borrowPausedLocationSnapshot(source);
      entityScripts = StarsectorPrepatcherHooks.borrowEntityScriptSnapshot(source);
      require(first != second && first != entityScripts && second != entityScripts,
          "multiple live snapshots in one scope aliased each other");
      require(second.equals(List.of(secondValue)) && entityScripts.equals(List.of(secondValue)),
          "later snapshots did not preserve their own point-in-time contents");

      source.set(0, firstValue);
      require(second.equals(List.of(secondValue)) && entityScripts.equals(List.of(secondValue)),
          "source replacement leaked into a borrowed snapshot");
    } finally {
      StarsectorPrepatcherHooks.endScratchScope();
    }

    require(first.isEmpty() && second.isEmpty() && entityScripts.isEmpty(),
        "ending a scratch scope did not clear every live snapshot");

    StarsectorPrepatcherHooks.beginScratchScope();
    try {
      List<?> reused = StarsectorPrepatcherHooks.borrowLocationAdvanceSnapshot(List.of(secondValue));
      require(reused == first, "first snapshot slot was not reused by the next scope");
      require(reused.equals(List.of(secondValue)), "reused snapshot contains stale values");
    } finally {
      StarsectorPrepatcherHooks.endScratchScope();
    }
  }

  private static void assertSnapshotReentrancy() {
    Object outerValue = new Object();
    Object innerValue = new Object();

    StarsectorPrepatcherHooks.beginScratchScope();
    try {
      List<?> outer = StarsectorPrepatcherHooks.borrowLocationAdvanceSnapshot(List.of(outerValue));
      List<?> firstInner;
      StarsectorPrepatcherHooks.beginScratchScope();
      try {
        firstInner = StarsectorPrepatcherHooks.borrowEntityScriptSnapshot(List.of(innerValue));
        require(firstInner != outer, "nested scratch scope reused an active outer snapshot");
        require(firstInner.equals(List.of(innerValue)), "nested snapshot contents changed");
      } finally {
        StarsectorPrepatcherHooks.endScratchScope();
      }
      require(firstInner.isEmpty(), "nested scratch frame was not cleared on exit");
      require(outer.equals(List.of(outerValue)), "nested scope cleared its active outer frame");

      StarsectorPrepatcherHooks.beginScratchScope();
      try {
        List<?> reusedInner = StarsectorPrepatcherHooks.borrowEntityScriptSnapshot(List.of(innerValue));
        require(reusedInner == firstInner, "nested snapshot frame was not reused");
      } finally {
        StarsectorPrepatcherHooks.endScratchScope();
      }
      require(outer.equals(List.of(outerValue)), "reused nested frame corrupted outer state");
    } finally {
      StarsectorPrepatcherHooks.endScratchScope();
    }
  }

  private static void assertSnapshotFallback() throws Exception {
    installConfig(config(false));
    ArrayList<Object> source = new ArrayList<>(List.of(new Object()));
    List<?> first = StarsectorPrepatcherHooks.borrowLocationAdvanceSnapshot(source);
    List<?> second = StarsectorPrepatcherHooks.borrowLocationAdvanceSnapshot(source);
    List<?> scripts = StarsectorPrepatcherHooks.borrowEntityScriptSnapshot(source);
    require(first instanceof ArrayList<?> && second instanceof ArrayList<?> && scripts instanceof ArrayList<?>,
        "disabled snapshot reuse did not preserve vanilla ArrayList snapshots");
    require(first != second && first != scripts && second != scripts,
        "disabled snapshot reuse unexpectedly pooled fallback lists");
    source.clear();
    require(first.size() == 1 && second.size() == 1 && scripts.size() == 1,
        "disabled snapshot fallback was not isolated from its source");
  }

  private static void assertMemoryIteratorMatrix() {
    Iterator<?> canonicalEmpty = Collections.emptyIterator();
    Iterator<?> expireEmpty = StarsectorPrepatcherHooks.memoryExpireIterator(new ArrayList<>());
    Iterator<?> requireEmpty = StarsectorPrepatcherHooks.memoryRequireIterator(new ArrayList<>());
    require(expireEmpty == canonicalEmpty && requireEmpty == canonicalEmpty,
        "empty memory collections did not use the shared empty iterator");
    require(!expireEmpty.hasNext() && !requireEmpty.hasNext(),
        "empty memory iterator unexpectedly contained an element");

    ArrayList<String> expire = new ArrayList<>(List.of("first", "second"));
    Iterator expireIterator = StarsectorPrepatcherHooks.memoryExpireIterator(expire);
    require(expireIterator.next().equals("first"), "expire iterator changed source order");
    expireIterator.remove();
    require(expire.equals(List.of("second")), "expire iterator did not delegate remove()");
    require(expireIterator.next().equals("second") && !expireIterator.hasNext(),
        "expire iterator did not visit every remaining source value");

    LinkedHashMap<String, String> requireMap = new LinkedHashMap<>();
    requireMap.put("one", "first");
    requireMap.put("two", "second");
    Collection<String> values = requireMap.values();
    Iterator requireIterator = StarsectorPrepatcherHooks.memoryRequireIterator(values);
    require(requireIterator.next().equals("first"), "require iterator changed values order");
    requireIterator.remove();
    require(!requireMap.containsKey("one") && requireMap.size() == 1,
        "require iterator did not preserve backing-map remove semantics");
    require(requireIterator.next().equals("second") && !requireIterator.hasNext(),
        "require iterator did not visit every remaining value");
  }

  private static void assertSnapshotReachability() throws Exception {
    ReferenceQueue<Object> valueQueue = new ReferenceQueue<>();
    ReferenceQueue<Collection<?>> sourceQueue = new ReferenceQueue<>();
    SnapshotProbe probe = populateSnapshots(valueQueue, sourceQueue);
    awaitCollected(probe.value(), valueQueue, "scratch snapshot retained a campaign value after scope exit");
    awaitCollected(probe.source(), sourceQueue, "scratch snapshot retained its source collection after scope exit");
  }

  private static SnapshotProbe populateSnapshots(ReferenceQueue<Object> valueQueue,
      ReferenceQueue<Collection<?>> sourceQueue) {
    Object value = new Object();
    ArrayList<Object> source = new ArrayList<>(List.of(value));
    WeakReference<Object> valueReference = new WeakReference<>(value, valueQueue);
    WeakReference<Collection<?>> sourceReference = new WeakReference<>(source, sourceQueue);

    StarsectorPrepatcherHooks.beginScratchScope();
    try {
      require(StarsectorPrepatcherHooks.borrowLocationAdvanceSnapshot(source).get(0) == value,
          "location snapshot lost its source value");
      require(StarsectorPrepatcherHooks.borrowPausedLocationSnapshot(source).get(0) == value,
          "paused snapshot lost its source value");
      require(StarsectorPrepatcherHooks.borrowEntityScriptSnapshot(source).get(0) == value,
          "entity-script snapshot lost its source value");
    } finally {
      StarsectorPrepatcherHooks.endScratchScope();
    }
    source.clear();
    return new SnapshotProbe(valueReference, sourceReference);
  }

  private static void assertExceptionalSnapshotReachability() throws Exception {
    // Ensure slot zero owns a reusable array before exercising the supplied-array
    // failure path; an empty first use would give the custom collection a zero-length array.
    StarsectorPrepatcherHooks.beginScratchScope();
    try {
      StarsectorPrepatcherHooks.borrowLocationAdvanceSnapshot(List.of(new Object(), new Object(), new Object()));
    } finally {
      StarsectorPrepatcherHooks.endScratchScope();
    }

    ReferenceQueue<Object> queue = new ReferenceQueue<>();
    WeakReference<Object> reference = exceptionalSnapshot(queue);
    awaitCollected(reference, queue, "partially populated snapshot array retained a value after copy failure");
  }

  private static WeakReference<Object> exceptionalSnapshot(ReferenceQueue<Object> queue) {
    Object value = new Object();
    WeakReference<Object> reference = new WeakReference<>(value, queue);
    Collection<Object> source = new PartiallyWritingCollection(value);

    StarsectorPrepatcherHooks.beginScratchScope();
    try {
      StarsectorPrepatcherHooks.borrowLocationAdvanceSnapshot(source);
      throw new AssertionError("partially writing collection did not throw");
    } catch (SnapshotCopyFailure expected) {
      // The hook must propagate the original failure after clearing its backing array.
    } finally {
      StarsectorPrepatcherHooks.endScratchScope();
    }
    return reference;
  }

  private static void assertEmptyIteratorReachability() throws Exception {
    ReferenceQueue<Collection<?>> expireQueue = new ReferenceQueue<>();
    EmptyIteratorProbe expire = emptyExpireIterator(expireQueue);
    awaitCollected(expire.source(), expireQueue, "empty expire iterator retained its source collection");
    require(!expire.iterator().hasNext(), "collected expire source changed the empty iterator");

    ReferenceQueue<Collection<?>> requireQueue = new ReferenceQueue<>();
    EmptyIteratorProbe require = emptyRequireIterator(requireQueue);
    awaitCollected(require.source(), requireQueue, "empty require iterator retained its source collection");
    require(!require.iterator().hasNext(), "collected require source changed the empty iterator");
  }

  private static EmptyIteratorProbe emptyExpireIterator(ReferenceQueue<Collection<?>> queue) {
    ArrayList<Object> source = new ArrayList<>();
    WeakReference<Collection<?>> reference = new WeakReference<>(source, queue);
    return new EmptyIteratorProbe(reference, StarsectorPrepatcherHooks.memoryExpireIterator(source));
  }

  private static EmptyIteratorProbe emptyRequireIterator(ReferenceQueue<Collection<?>> queue) {
    ArrayList<Object> source = new ArrayList<>();
    WeakReference<Collection<?>> reference = new WeakReference<>(source, queue);
    return new EmptyIteratorProbe(reference, StarsectorPrepatcherHooks.memoryRequireIterator(source));
  }

  private static void assertReadOnly(List<?> snapshot, String label) {
    try {
      List raw = snapshot;
      raw.add(new Object());
      throw new AssertionError(label + " accepted mutation");
    } catch (UnsupportedOperationException expected) {
      // Required: pooled lists must never be changed by transformed vanilla loops.
    }
  }

  private static PrepatcherConfig config(boolean snapshotReuse) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("patch.campaignSnapshotReuse", Boolean.toString(snapshotReuse));
    properties.setProperty("patch.entityScriptSnapshotReuse", Boolean.toString(snapshotReuse));
    Constructor<PrepatcherConfig> constructor = PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
    constructor.setAccessible(true);
    PrepatcherConfig config = constructor.newInstance(properties);
    require(config.campaignSnapshotReuse == snapshotReuse && config.entityScriptSnapshotReuse == snapshotReuse,
        "test configuration did not select the requested snapshot mode");
    return config;
  }

  private static void installConfig(PrepatcherConfig config) throws Exception {
    Field field = StarsectorPrepatcherHooks.class.getDeclaredField("config");
    field.setAccessible(true);
    field.set(null, config);
  }

  private static void drainScratchScopes() {
    for (int i = 0; i < 8; i++) StarsectorPrepatcherHooks.endScratchScope();
  }

  private static void awaitCollected(WeakReference<?> reference, ReferenceQueue<?> queue, String message)
      throws Exception {
    boolean enqueued = false;
    for (int attempt = 0; attempt < 80; attempt++) {
      if (queue.poll() == reference) {
        enqueued = true;
        break;
      }
      System.gc();
      System.runFinalization();
      byte[][] pressure = new byte[8][];
      for (int i = 0; i < pressure.length; i++) pressure[i] = new byte[128 * 1024];
      Thread.sleep(10L);
    }
    if (!enqueued) enqueued = queue.poll() == reference;
    require(reference.get() == null && enqueued, message);
  }

  private static void rejectDisabledExplicitGc() {
    boolean disabled =
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream().anyMatch("-XX:+DisableExplicitGC"::equals);
    require(!disabled, "exp6 GC regression cannot run with -XX:+DisableExplicitGC");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private record SnapshotProbe(WeakReference<Object> value, WeakReference<Collection<?>> source) {
  }

  private record EmptyIteratorProbe(WeakReference<Collection<?>> source, Iterator<?> iterator) {
  }

  private static final class PartiallyWritingCollection extends AbstractCollection<Object> {
    private final Object value;

    private PartiallyWritingCollection(Object value) {
      this.value = value;
    }

    @Override
    public Iterator<Object> iterator() {
      return Collections.singleton(value).iterator();
    }

    @Override
    public int size() {
      return 1;
    }

    @Override
    public <T> T[] toArray(T[] supplied) {
      require(supplied.length > 0, "snapshot did not supply its warmed reusable backing array");
      supplied[0] = (T) value;
      throw new SnapshotCopyFailure();
    }
  }

  private static final class SnapshotCopyFailure extends RuntimeException {}

  private static final class PartiallyReadableList extends AbstractList<Object> {
    private final Object first;

    private PartiallyReadableList(Object first) {
      this.first = first;
    }

    @Override
    public Object get(int index) {
      if (index == 0) return first;
      throw new RetainProbeFailure();
    }

    @Override
    public int size() {
      return 2;
    }
  }

  private static final class RetainProbeFailure extends RuntimeException {}
}
