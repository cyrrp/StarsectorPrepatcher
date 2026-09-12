package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherHooks;

import java.lang.management.ManagementFactory;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GC/lifecycle regression harness for the system-classloader runtime hooks.
 *
 * <p>The test deliberately populates the exact private cache structures shipped in
 * {@link StarsectorPrepatcherHooks}. It then exercises the public two-phase identity hooks and
 * verifies both structural cleanup and actual reachability of a shared sentinel.
 * No game objects are required: generic collection types are erased at runtime and
 * the private NebulaCache is instantiated reflectively with sentinel-backed lists.
 */
public final class LifecycleGcRegressionTest {
  private static final String BEGIN_METHOD = "beginCampaignEngineChange";
  private static final String COMPLETE_METHOD = "completeCampaignEngineChange";
  private static final List<String> MAP_CACHE_FIELDS = List.of("INTEL_CACHE", "LABEL_INDEXES", "INTEL_ENTITY_INDEXES",
      "HIT_CACHES", "SAMPLE_CLEAR_TIMES", "CAMPAIGN_LISTENER_STATES", "ROUTE_JUMP_INDEXES", "ROUTE_SYSTEM_INDEXES",
      "ECONOMY_LOCATION_STATES", "COMM_RELAY_SYSTEM_INDEXES");

  private LifecycleGcRegressionTest() {}

  public static void main(String[] args) throws Exception {
    rejectDisabledExplicitGc();
    Method begin = beginMethod();
    Method complete = completeMethod();
    assertExactPackagedStructures();
    assertGenerationActive(false, "initial state");
    long initialGeneration = generation();

    Object firstEngine = new Object();
    Object secondEngine = new Object();
    long firstTransition = begin(begin, firstEngine);
    require(firstTransition >= 0L, "first non-null engine did not open a transition");
    assertGenerationActive(false, "first transition before completion");
    assertGeneration(initialGeneration + 1L, "first non-null engine begin");
    complete(complete, firstEngine, firstTransition);
    assertGenerationActive(true, "first non-null engine");
    assertGeneration(initialGeneration + 1L, "first non-null engine");
    assertOutOfOrderCompletionFailsClosed(begin, complete, firstEngine);

    ReferenceQueue<Object> queue = new ReferenceQueue<>();
    WeakReference<Object> released = populateSwitchAndVerify(begin, complete, firstEngine, secondEngine, queue);
    awaitCollected(released, queue, "campaign cache sentinel survived engine identity change");

    ReferenceQueue<Object> nullQueue = new ReferenceQueue<>();
    WeakReference<Object> releasedOnReset = populateSwitchAndVerify(begin, complete, secondEngine, null, nullQueue);
    awaitCollected(releasedOnReset, nullQueue, "campaign cache sentinel survived resetInstance(null)");

    // Repeated notifications for the already-current identity, including null,
    // must be harmless and must not recreate cache state.
    long nullGeneration = generation();
    long repeatedNull = begin(begin, null);
    require(repeatedNull < 0L, "repeated null identity opened a transition");
    complete(complete, null, repeatedNull);
    assertCachesEmpty();
    assertGenerationActive(false, "repeated null identity");
    assertGeneration(nullGeneration, "repeated null identity");
    repeatedNull = begin(begin, null);
    require(repeatedNull < 0L, "second repeated null identity opened a transition");
    complete(complete, null, repeatedNull);
    assertCachesEmpty();
    assertGenerationActive(false, "second repeated null identity");
    assertGeneration(nullGeneration, "second repeated null identity");

    ReferenceQueue<Object> replacedEngineQueue = new ReferenceQueue<>();
    WeakReference<Object> replacedEngine = replaceEngineAndRelease(begin, complete, replacedEngineQueue);
    awaitCollected(replacedEngine, replacedEngineQueue, "lifecycle tracker retained the replaced engine argument");

    ReferenceQueue<Object> resetEngineQueue = new ReferenceQueue<>();
    WeakReference<Object> resetEngine = resetEngineAndRelease(begin, complete, resetEngineQueue);
    awaitCollected(resetEngine, resetEngineQueue, "lifecycle tracker retained the reset engine argument");
    assertOwnerDetachDoesNotWaitForCacheMonitors(begin, complete);

    System.out.println("OK lifecycle-gc exact-caches=" + MAP_CACHE_FIELDS.size()
        + " scratch-reentrant-detach/drain identity-idempotence two-phase-boundary" + " engine-change reset-null"
        + " generation-active-state generation-epoch lock-free-detach nebula-slot-detach" + " out-of-order-fail-closed"
        + " weak-cache-reachability weak-engine-arguments");
  }

  private static void assertOutOfOrderCompletionFailsClosed(Method beginMethod, Method completeMethod,
      Object currentEngine) throws Exception {
    Object staleEngine = new Object();
    long older = begin(beginMethod, staleEngine);
    long newer = begin(beginMethod, currentEngine);
    require(older >= 0L && newer >= 0L && older != newer,
        "overlapping lifecycle transitions did not receive distinct tokens");

    complete(completeMethod, currentEngine, newer);
    assertGenerationActive(true, "newer transition completion");
    Object[] activeRoots = campaignCacheRoots();
    complete(completeMethod, staleEngine, older);
    assertGenerationActive(false, "stale transition completion");
    assertCampaignRootsDetached(activeRoots, "stale transition completion");
    assertCachesEmpty();

    // A normal subsequent transition restores every optimization; fail-closed
    // handling affects only the unsupported overlap itself.
    change(beginMethod, completeMethod, currentEngine);
    assertGenerationActive(true, "recovery after stale transition completion");
  }

  private static WeakReference<Object> populateSwitchAndVerify(Method beginMethod, Method completeMethod,
      Object currentEngine, Object nextEngine, ReferenceQueue<Object> queue) throws Exception {
    Object sentinel = new Object();
    Object oldScratch = populateEveryCampaignCache(sentinel);
    Object[] oldCacheRoots = campaignCacheRoots();
    WeakReference<Object> reference = new WeakReference<>(sentinel, queue);

    // Same identity is intentionally a no-op; clearing every frame would defeat
    // the caches and conceal a broken identity guard.
    long currentGeneration = generation();
    long noTransition = begin(beginMethod, currentEngine);
    require(noTransition < 0L, "unchanged engine identity opened a transition");
    complete(completeMethod, currentEngine, noTransition);
    assertGenerationActive(currentEngine != null, "unchanged engine identity");
    assertGeneration(currentGeneration, "unchanged engine identity");
    assertCampaignRootsUnchanged(oldCacheRoots, "unchanged engine identity");
    assertCachesPopulated(sentinel, oldScratch);

    long transition = begin(beginMethod, nextEngine);
    require(transition >= 0L, "changed engine identity did not open a transition");
    assertGenerationActive(false, "changed engine before completion");
    assertGeneration(currentGeneration + 1L, "changed engine identity");
    assertCampaignRootsDetached(oldCacheRoots, "changed engine identity");
    assertCachesEmpty();
    assertScratchWasDetachedAndDrains(oldScratch);
    complete(completeMethod, nextEngine, transition);
    assertGenerationActive(nextEngine != null, "changed engine identity");
    return reference;
  }

  private static WeakReference<Object> replaceEngineAndRelease(Method beginMethod, Method completeMethod,
      ReferenceQueue<Object> queue) throws Exception {
    Object engine = new Object();
    change(beginMethod, completeMethod, engine);
    assertGenerationActive(true, "replace source engine");
    WeakReference<Object> reference = new WeakReference<>(engine, queue);
    change(beginMethod, completeMethod, new Object());
    assertGenerationActive(true, "replacement engine");
    return reference;
  }

  private static WeakReference<Object> resetEngineAndRelease(Method beginMethod, Method completeMethod,
      ReferenceQueue<Object> queue) throws Exception {
    Object engine = new Object();
    change(beginMethod, completeMethod, engine);
    assertGenerationActive(true, "reset source engine");
    WeakReference<Object> reference = new WeakReference<>(engine, queue);
    change(beginMethod, completeMethod, null);
    assertGenerationActive(false, "reset null engine");
    return reference;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Object populateEveryCampaignCache(Object sentinel) throws Exception {
    for (String name : MAP_CACHE_FIELDS) {
      Map map = (Map) staticField(name).get(null);
      synchronized (map) {
        map.clear();
        map.put(sentinel, sentinel);
      }
    }

    Object nebulaSlot = staticField("nebulaCacheSlot").get(null);
    Field nebulaValue = instanceField(nebulaSlot.getClass(), "value");
    Class<?> cacheType = nebulaValue.getType();
    Constructor<?> constructor =
        Arrays.stream(cacheType.getDeclaredConstructors()).filter(candidate -> candidate.getParameterCount() == 7)
            .findFirst().orElseThrow(() -> new AssertionError("NebulaCache constructor shape changed"));
    constructor.setAccessible(true);
    Object cache =
        constructor.newInstance(new WeakReference<>(sentinel), 1, System.nanoTime(), new ArrayList<>(List.of(sentinel)),
            new long[]{1L}, new ArrayList<>(List.of(sentinel)), new ArrayList<>(List.of(sentinel)));
    nebulaValue.set(nebulaSlot, cache);

    ThreadLocal threadLocal = scratchThreadLocal();
    Object scratch = threadLocal.get();
    Field framesField = instanceField(scratch.getClass(), "frames");
    List frames = (List) framesField.get(scratch);
    frames.clear();

    Method frameAt = scratch.getClass().getDeclaredMethod("frameAt", int.class);
    frameAt.setAccessible(true);
    Object frame = frameAt.invoke(scratch, 0);
    instanceField(scratch.getClass(), "scopeDepth").setInt(scratch, 1);
    addToCollection(frame, "entityList", sentinel);
    addToCollection(frame, "hitList", sentinel);
    addToCollection(frame, "classSet", sentinel);
    putInMap(frame, "identityMembership", sentinel);
    addToCollection(frame, "equalityMembership", sentinel);
    addToCollection(frame, "containsSet", sentinel);
    instanceField(frame.getClass(), "containsSource").set(frame, List.of(sentinel));
    instanceField(frame.getClass(), "containsSourceSize").setInt(frame, 1);
    addToCollection(frame, "labelCandidates", sentinel);
    return scratch;
  }

  @SuppressWarnings("rawtypes")
  private static void assertCachesPopulated(Object sentinel, Object expectedScratch) throws Exception {
    for (String name : MAP_CACHE_FIELDS) {
      Map map = (Map) staticField(name).get(null);
      synchronized (map) {
        require(map.size() == 1 && map.get(sentinel) == sentinel,
            name + " was cleared for an unchanged engine identity");
      }
    }
    Object nebulaSlot = staticField("nebulaCacheSlot").get(null);
    require(instanceField(nebulaSlot.getClass(), "value").get(nebulaSlot) != null,
        "nebulaCache was cleared for an unchanged engine identity");
    require(scratchThreadLocal().get() == expectedScratch, "SCRATCH was removed for an unchanged engine identity");
  }

  @SuppressWarnings("rawtypes")
  private static void assertCachesEmpty() throws Exception {
    for (String name : MAP_CACHE_FIELDS) {
      Map map = (Map) staticField(name).get(null);
      synchronized (map) {
        require(map.isEmpty(), name + " retained campaign state after lifecycle reset");
      }
    }
    Object nebulaSlot = staticField("nebulaCacheSlot").get(null);
    require(instanceField(nebulaSlot.getClass(), "value").get(nebulaSlot) == null,
        "nebulaCache retained campaign state after lifecycle reset");
  }

  private static Object[] campaignCacheRoots() throws Exception {
    Object[] roots = new Object[MAP_CACHE_FIELDS.size() + 2];
    for (int i = 0; i < MAP_CACHE_FIELDS.size(); i++) {
      roots[i] = staticField(MAP_CACHE_FIELDS.get(i)).get(null);
    }
    roots[MAP_CACHE_FIELDS.size()] = staticField("MARKET_SCHEDULER_STATES").get(null);
    roots[MAP_CACHE_FIELDS.size() + 1] = staticField("nebulaCacheSlot").get(null);
    return roots;
  }

  private static void assertCampaignRootsUnchanged(Object[] previous, String context) throws Exception {
    Object[] current = campaignCacheRoots();
    for (int i = 0; i < current.length; i++) {
      require(current[i] == previous[i], campaignRootName(i) + " root changed during " + context);
    }
  }

  private static void assertCampaignRootsDetached(Object[] previous, String context) throws Exception {
    Object[] current = campaignCacheRoots();
    for (int i = 0; i < current.length; i++) {
      require(current[i] != previous[i], campaignRootName(i) + " root was not detached during " + context);
    }
  }

  private static String campaignRootName(int index) {
    if (index < MAP_CACHE_FIELDS.size()) return MAP_CACHE_FIELDS.get(index);
    return index == MAP_CACHE_FIELDS.size() ? "MARKET_SCHEDULER_STATES" : "nebulaCacheSlot";
  }

  @SuppressWarnings("rawtypes")
  private static void assertScratchWasDetachedAndDrains(Object oldScratch) throws Exception {
    Object replacement = scratchThreadLocal().get();
    require(replacement != oldScratch, "campaign change did not detach the active Scratch container");
    require(instanceField(replacement.getClass(), "scopeDepth").getInt(replacement) == 1,
        "replacement Scratch did not preserve the interrupted lease depth");
    require(instanceField(replacement.getClass(), "removeWhenIdle").getBoolean(replacement),
        "replacement Scratch was not marked for removal after lease drain");
    Collection<?> frames = (Collection<?>) instanceField(replacement.getClass(), "frames").get(replacement);
    require(frames.isEmpty(), "replacement Scratch inherited campaign references from the detached container");

    // Reproduce the original leak: a hook called after the re-entrant reset
    // writes into the replacement frame, then the transformed method's old
    // finally hook must still clear and remove that frame.
    Object postResetSentinel = new Object();
    Method frameAt = replacement.getClass().getDeclaredMethod("frameAt", int.class);
    frameAt.setAccessible(true);
    Object frame = frameAt.invoke(replacement, 0);
    addToCollection(frame, "entityList", postResetSentinel);
    StarsectorPrepatcherHooks.endScratchScope();
    require(((Collection) instanceField(frame.getClass(), "entityList").get(frame)).isEmpty(),
        "post-reset campaign reference survived the interrupted scope's finally hook");
    Object afterDrain = scratchThreadLocal().get();
    require(afterDrain != replacement, "replacement Scratch remained installed after its interrupted leases drained");
    require(instanceField(afterDrain.getClass(), "scopeDepth").getInt(afterDrain) == 0,
        "fresh post-drain Scratch unexpectedly has an open lease");
  }

  private static void assertExactPackagedStructures() throws Exception {
    Set<String> actualMapFields = new HashSet<>();
    for (Class<?> owner : runtimeClasses()) {
      for (Field field : owner.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers()) && Map.class.isAssignableFrom(field.getType())) {
          require(Modifier.isVolatile(field.getModifiers()),
              field.getName() + " must be volatile for lock-free lifecycle detachment");
          actualMapFields.add(field.getName());
        }
      }
    }
    require(actualMapFields.equals(new HashSet<>(MAP_CACHE_FIELDS)),
        "static Map cache inventory changed; expected=" + MAP_CACHE_FIELDS + " actual=" + actualMapFields);
    Field schedulerStates = staticField("MARKET_SCHEDULER_STATES");
    require(
        Modifier.isVolatile(schedulerStates.getModifiers())
            && schedulerStates.getType().getName().endsWith("$WeakIdentityMap"),
        "MARKET_SCHEDULER_STATES is not a volatile weak-identity root");

    Field scratch = staticField("SCRATCH");
    require(Modifier.isFinal(scratch.getModifiers()) && scratch.getType() == ThreadLocal.class,
        "SCRATCH is no longer a final ThreadLocal");
    Field nebulaSlot = staticField("nebulaCacheSlot");
    require(
        Modifier.isVolatile(nebulaSlot.getModifiers())
            && nebulaSlot.getType().getName().endsWith("StarsectorPrepatcherMapRuntime$NebulaCacheSlot"),
        "nebulaCacheSlot packaged shape changed");
    Field nebulaValue = instanceField(nebulaSlot.getType(), "value");
    require(
        Modifier.isVolatile(nebulaValue.getModifiers())
            && nebulaValue.getType().getName().endsWith("StarsectorPrepatcherMapRuntime$NebulaCache"),
        "NebulaCacheSlot.value packaged shape changed");

    Field generationActive = staticField("campaignCacheGenerationActive");
    require(Modifier.isVolatile(generationActive.getModifiers()) && generationActive.getType() == boolean.class,
        "campaignCacheGenerationActive must be a volatile boolean");
    Field generation = staticField("campaignCacheGeneration");
    require(Modifier.isVolatile(generation.getModifiers()) && generation.getType() == long.class,
        "campaignCacheGeneration must be a volatile long");
    require(
        staticField("activeCampaignEngine").getType() == WeakReference.class
            && staticField("pendingCampaignEngine").getType() == WeakReference.class
            && staticField("activeReachEconomy").getType() == WeakReference.class,
        "active/pending campaign owners must remain weak references");
  }

  private static void assertGenerationActive(boolean expected, String context) throws Exception {
    boolean actual = staticField("campaignCacheGenerationActive").getBoolean(null);
    require(actual == expected,
        "campaignCacheGenerationActive=" + actual + " during " + context + "; expected=" + expected);
  }

  private static long generation() throws Exception {
    return staticField("campaignCacheGeneration").getLong(null);
  }

  private static void assertGeneration(long expected, String context) throws Exception {
    long actual = generation();
    require(actual == expected, "campaignCacheGeneration=" + actual + " during " + context + "; expected=" + expected);
  }

  private static Method beginMethod() throws Exception {
    Method method = StarsectorPrepatcherHooks.class.getDeclaredMethod(BEGIN_METHOD, Object.class);
    int required = Modifier.PUBLIC | Modifier.STATIC;
    require((method.getModifiers() & required) == required && method.getReturnType() == long.class,
        BEGIN_METHOD + " must be public static long (Object)");
    return method;
  }

  private static Method completeMethod() throws Exception {
    Method method = StarsectorPrepatcherHooks.class.getDeclaredMethod(COMPLETE_METHOD, Object.class, long.class);
    int required = Modifier.PUBLIC | Modifier.STATIC;
    require((method.getModifiers() & required) == required && method.getReturnType() == void.class,
        COMPLETE_METHOD + " must be public static void (Object, long)");
    return method;
  }

  private static long begin(Method method, Object engine) throws Exception {
    try {
      return ((Long) method.invoke(null, engine)).longValue();
    } catch (ReflectiveOperationException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof Exception exception) throw exception;
      if (cause instanceof Error error) throw error;
      throw ex;
    }
  }

  private static void complete(Method method, Object engine, long transition) throws Exception {
    try {
      method.invoke(null, engine, Long.valueOf(transition));
    } catch (ReflectiveOperationException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof Exception exception) throw exception;
      if (cause instanceof Error error) throw error;
      throw ex;
    }
  }

  private static void change(Method beginMethod, Method completeMethod, Object engine) throws Exception {
    long transition = begin(beginMethod, engine);
    require(transition >= 0L, "engine identity change did not open a transition");
    assertGenerationActive(false, "engine transition before completion");
    complete(completeMethod, engine, transition);
  }

  private static void awaitCollected(WeakReference<Object> reference, ReferenceQueue<Object> queue, String message)
      throws Exception {
    boolean enqueued = false;
    for (int attempt = 0; attempt < 80; attempt++) {
      if (queue.poll() == reference) {
        enqueued = true;
        break;
      }
      System.gc();
      System.runFinalization();
      // Modest pressure helps collectors that treat System.gc() as advisory,
      // while keeping this harness safe under a small CI heap.
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
    require(!disabled, "GC lifecycle regression cannot run with -XX:+DisableExplicitGC");
  }

  @SuppressWarnings("rawtypes")
  private static ThreadLocal scratchThreadLocal() throws Exception {
    return (ThreadLocal) staticField("SCRATCH").get(null);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void addToCollection(Object owner, String name, Object value) throws Exception {
    ((Collection) instanceField(owner.getClass(), name).get(owner)).add(value);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void putInMap(Object owner, String name, Object value) throws Exception {
    ((Map) instanceField(owner.getClass(), name).get(owner)).put(value, Boolean.TRUE);
  }

  private static List<Class<?>> runtimeClasses() throws Exception {
    List<Class<?>> classes = new ArrayList<>(List.of(StarsectorPrepatcherHooks.class));
    for (String owner : List.of("CampaignCaches", "CampaignListeners", "CommodityTemporal", "EconomyRuntime",
        "IntelRuntime", "MapRuntime", "MarketScheduler", "Metrics", "RouteIndex", "Scratch", "StartupRuntime",
        "StrategicJumpHooks")) {
      classes.add(Class.forName("com.fs.starfarer.api.StarsectorPrepatcher" + owner));
    }
    return classes;
  }

  private static Field staticField(String name) throws Exception {
    String owner = switch (name) {
      case "INTEL_CACHE", "LABEL_INDEXES", "INTEL_ENTITY_INDEXES", "COMM_RELAY_SYSTEM_INDEXES" -> "IntelRuntime";
      case "HIT_CACHES", "SAMPLE_CLEAR_TIMES", "nebulaCacheSlot" -> "MapRuntime";
      case "CAMPAIGN_LISTENER_STATES" -> "CampaignListeners";
      case "ROUTE_JUMP_INDEXES", "ROUTE_SYSTEM_INDEXES" -> "RouteIndex";
      case "ECONOMY_LOCATION_STATES" -> "EconomyRuntime";
      case "MARKET_SCHEDULER_STATES" -> "MarketScheduler";
      case "SCRATCH" -> "Scratch";
      case "activeCampaignEngine", "activeReachEconomy", "pendingCampaignEngine", "campaignCacheGeneration",
          "campaignCacheGenerationActive" ->
        "CampaignCaches";
      default -> throw new NoSuchFieldException(name);
    };
    Field field = Class.forName("com.fs.starfarer.api.StarsectorPrepatcher" + owner).getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static void assertOwnerDetachDoesNotWaitForCacheMonitors(Method beginMethod, Method completeMethod)
      throws Exception {
    Object[] roots = campaignCacheRoots();
    var holding = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var held = workers.submit(() -> holdRoots(roots, 0, holding, release));
      require(holding.await(10, java.util.concurrent.TimeUnit.SECONDS), "cache monitor fixture did not start");
      Object engine = new Object();
      var reset = workers.submit(() -> {
        long transition = begin(beginMethod, engine);
        require(transition >= 0L, "owner detachment failed while cache monitors were held");
        assertGenerationActive(false, "owner reset before publication");
        assertCampaignRootsDetached(roots, "reset while old roots are locked");
        complete(completeMethod, engine, transition);
        return transition;
      });
      reset.get(10, java.util.concurrent.TimeUnit.SECONDS);
      require(!held.isDone(), "cache monitor holder ended before lifecycle reset completed");
      release.countDown();
      held.get(10, java.util.concurrent.TimeUnit.SECONDS);
      change(beginMethod, completeMethod, null);
    } finally {
      release.countDown();
      workers.shutdownNow();
      require(workers.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS),
          "lifecycle fixture workers did not stop");
    }
  }

  private static void holdRoots(Object[] roots, int index, java.util.concurrent.CountDownLatch holding,
      java.util.concurrent.CountDownLatch release) {
    if (index < roots.length) {
      synchronized (roots[index]) {
        holdRoots(roots, index + 1, holding, release);
      }
      return;
    }
    holding.countDown();
    try {
      require(release.await(30, java.util.concurrent.TimeUnit.SECONDS), "cache monitor fixture timed out");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  private static Field instanceField(Class<?> type, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
