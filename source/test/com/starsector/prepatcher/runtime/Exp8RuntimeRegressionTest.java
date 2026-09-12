package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherHooks;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.campaign.econ.reach.ReachEconomy;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import org.lwjgl.util.vector.Vector2f;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;

/** Runtime semantics checks for the exp8 economy/combat/particle/index patches. */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class Exp8RuntimeRegressionTest {
  private Exp8RuntimeRegressionTest() {}

  public static void main(String[] args) throws Exception {
    PrepatcherConfig enabled = config(true);
    installConfig(enabled);
    try {
      assertShipScratchSemantics();
      assertParticleCleanupParity();
      assertParticleExceptionalParity();
      assertEconomyFingerprint();
      assertEconomyGenerationOwnership();
      assertCommRelaySpatialIndex();
      assertDisabledFallbacks();
    } finally {
      drainScratchScopes();
      installConfig(null);
    }
    System.out.println("OK exp8-runtime ship-scratch type/order/isolation/reentrancy/reuse"
        + " particle-cleanup identity/equality/order/exception-parity"
        + " economy-fingerprint identity/order/id/location/weak-cycle/generation-owner"
        + " comm-relay-index conservative/order/bounded-position-audit/failed-build-ttl" + " disabled-fallbacks");
  }

  private static void assertShipScratchSemantics() {
    Object a = new Object();
    Object b = new Object();
    ArrayList<Object> source = new ArrayList<>(List.of(a, b));
    ArrayList first;
    ArrayList second;
    HashSet firstSet;
    HashSet secondSet;

    StarsectorPrepatcherHooks.beginScratchScope();
    try {
      first = StarsectorPrepatcherHooks.borrowShipList();
      second = StarsectorPrepatcherHooks.borrowShipList();
      require(first.getClass() == ArrayList.class && second.getClass() == ArrayList.class,
          "ship list scratch changed the concrete ArrayList type");
      require(first != second, "simultaneously live ship list slots aliased");
      first.add(a);
      second.add(b);

      ArrayList snapshot = StarsectorPrepatcherHooks.borrowShipListSnapshot(source);
      require(snapshot.getClass() == ArrayList.class && snapshot.equals(List.of(a, b)),
          "ship command snapshot changed type, order, or contents");
      source.clear();
      require(snapshot.equals(List.of(a, b)), "ship command snapshot was not eager/isolated");

      firstSet = StarsectorPrepatcherHooks.borrowShipSet();
      secondSet = StarsectorPrepatcherHooks.borrowShipSet();
      require(firstSet.getClass() == HashSet.class && secondSet.getClass() == HashSet.class,
          "ship set scratch changed the concrete HashSet type");
      require(firstSet != secondSet, "simultaneously live ship set slots aliased");
      firstSet.add(a);
      secondSet.add(b);

      StarsectorPrepatcherHooks.beginScratchScope();
      try {
        ArrayList nested = StarsectorPrepatcherHooks.borrowShipList();
        require(nested != first && nested != second, "nested ship scratch scope aliased its active outer frame");
        nested.add(new Object());
      } finally {
        StarsectorPrepatcherHooks.endScratchScope();
      }
      require(first.equals(List.of(a)) && second.equals(List.of(b)), "nested ship scratch scope corrupted outer state");
    } finally {
      StarsectorPrepatcherHooks.endScratchScope();
    }
    require(first.isEmpty() && second.isEmpty() && firstSet.isEmpty() && secondSet.isEmpty(),
        "ship scratch retained frame objects after scope exit");

    StarsectorPrepatcherHooks.beginScratchScope();
    try {
      ArrayList reused = StarsectorPrepatcherHooks.borrowShipList();
      require(reused == first && reused.isEmpty(), "ship scratch was not reused and cleared on the next frame");
    } finally {
      StarsectorPrepatcherHooks.endScratchScope();
    }
  }

  private static void assertParticleCleanupParity() {
    ArrayList<Object> values = new ArrayList<>();
    for (int i = 0; i < 12; i++) values.add(new Object());
    ArrayList<Object> expired = new ArrayList<>(List.of(values.get(1), values.get(3), values.get(6), values.get(10)));
    LinkedList<Object> vanilla = new LinkedList<>(values);
    LinkedList<Object> patched = new LinkedList<>(values);
    boolean vanillaChanged = vanilla.removeAll(expired);

    StarsectorPrepatcherHooks.beginScratchScope();
    try {
      ArrayList pooled = StarsectorPrepatcherHooks.borrowParticleRemovalList();
      require(pooled.getClass() == ArrayList.class, "particle expiry scratch changed concrete ArrayList type");
      pooled.addAll(expired);
      boolean patchedChanged = StarsectorPrepatcherHooks.removeExpiredParticles(patched, pooled);
      require(patchedChanged == vanillaChanged && patched.equals(vanilla),
          "identity particle cleanup changed survivors, order, or boolean result");
    } finally {
      StarsectorPrepatcherHooks.endScratchScope();
    }

    ArrayList<EqualValue> equalValues = new ArrayList<>();
    for (int i = 0; i < 10; i++) equalValues.add(new EqualValue(i % 5));
    ArrayList<EqualValue> equalExpired =
        new ArrayList<>(List.of(new EqualValue(0), new EqualValue(1), new EqualValue(3), new EqualValue(8)));
    LinkedList<EqualValue> equalityVanilla = new LinkedList<>(equalValues);
    LinkedList<EqualValue> equalityPatched = new LinkedList<>(equalValues);
    boolean equalityChanged = equalityVanilla.removeAll(equalExpired);
    StarsectorPrepatcherHooks.beginScratchScope();
    try {
      boolean patchedChanged = StarsectorPrepatcherHooks.removeExpiredParticles(equalityPatched, equalExpired);
      require(patchedChanged == equalityChanged && equalityPatched.equals(equalityVanilla),
          "custom-equality particle fallback diverged from LinkedList.removeAll");
    } finally {
      StarsectorPrepatcherHooks.endScratchScope();
    }
  }

  private static void assertParticleExceptionalParity() {
    ArrayList<Object> values = new ArrayList<>();
    for (int i = 0; i < 8; i++) values.add(new Object());
    ArrayList<Object> expired = new ArrayList<>(values.subList(0, 4));
    ExplodingLinkedList<Object> vanilla = new ExplodingLinkedList<>(values, 2);
    ExplodingLinkedList<Object> patched = new ExplodingLinkedList<>(values, 2);
    boolean vanillaThrew = false;
    boolean patchedThrew = false;
    try {
      vanilla.removeAll(expired);
    } catch (ParticleMutationFailure expected) {
      vanillaThrew = true;
    }
    StarsectorPrepatcherHooks.beginScratchScope();
    try {
      try {
        StarsectorPrepatcherHooks.removeExpiredParticles(patched, expired);
      } catch (ParticleMutationFailure expected) {
        patchedThrew = true;
      }
    } finally {
      StarsectorPrepatcherHooks.endScratchScope();
    }
    require(vanillaThrew && patchedThrew, "particle cleanup did not preserve mutation exception propagation");
    require(patched.equals(vanilla), "particle cleanup retried after partial mutation or changed exception boundary");
  }

  private static void assertEconomyFingerprint() throws Exception {
    MutableLocation firstLocation = new MutableLocation("alpha");
    MutableLocation secondLocation = new MutableLocation("beta");
    MutableMarket first = new MutableMarket("m1", firstLocation.proxy);
    MutableMarket second = new MutableMarket("m2", secondLocation.proxy);
    ArrayList<MarketAPI> markets = new ArrayList<>(List.of(first.proxy, second.proxy));

    Class<?> stateClass = Class.forName("com.fs.starfarer.api.StarsectorPrepatcherHooks$EconomyLocationState");
    Method capture = stateClass.getDeclaredMethod("capture", List.class);
    Method matches = stateClass.getDeclaredMethod("matches", List.class);
    capture.setAccessible(true);
    matches.setAccessible(true);
    Object state = capture.invoke(null, markets);
    Field marketRefs = stateClass.getDeclaredField("markets");
    Field locationRefs = stateClass.getDeclaredField("locations");
    marketRefs.setAccessible(true);
    locationRefs.setAccessible(true);
    Object[] weakMarkets = (Object[]) marketRefs.get(state);
    Object[] weakLocations = (Object[]) locationRefs.get(state);
    require(weakMarkets[0] instanceof WeakReference && ((WeakReference<?>) weakMarkets[0]).get() == first.proxy,
        "economy fingerprint strongly retained a market");
    require(
        weakLocations[0] instanceof WeakReference && ((WeakReference<?>) weakLocations[0]).get() == firstLocation.proxy,
        "economy fingerprint strongly retained a location");
    require((Boolean) matches.invoke(state, markets), "economy fingerprint rejected an unchanged ordered market list");

    ArrayList<MarketAPI> reordered = new ArrayList<>(List.of(second.proxy, first.proxy));
    require(!(Boolean) matches.invoke(state, reordered), "economy fingerprint ignored market order/identity changes");

    first.id = "m1-renamed";
    require(!(Boolean) matches.invoke(state, markets), "economy fingerprint ignored a market id mutation");
    first.id = "m1";

    first.location = secondLocation.proxy;
    require(!(Boolean) matches.invoke(state, markets), "economy fingerprint ignored containing-location identity");
    first.location = firstLocation.proxy;

    firstLocation.id = "alpha-renamed";
    require(!(Boolean) matches.invoke(state, markets), "economy fingerprint ignored containing-location id mutation");
    firstLocation.id = "alpha";
    require((Boolean) matches.invoke(state, markets),
        "economy fingerprint did not recover after restoring exact state");

    EconomyCycleProbe probe = createEconomyCycleProbe(capture);
    awaitCollected(probe.economy, "weak economy cache value retained its ReachEconomy key through a market");
    require(probe.cache.isEmpty(), "weak economy cache did not expunge its collected ReachEconomy key");
  }

  private static void assertEconomyGenerationOwnership() throws Exception {
    MutableLocation location = new MutableLocation("owner-location");
    MutableMarket market = new MutableMarket("owner-market", location.proxy);
    CountingReachEconomy oldEconomy = new CountingReachEconomy(List.of(market.proxy));
    CountingReachEconomy newEconomy = new CountingReachEconomy(List.of(market.proxy));
    Object oldEngine = new Object();
    activateEconomyOwner(oldEngine, oldEconomy);

    StarsectorPrepatcherHooks.updateEconomyLocationMapIfNeeded(oldEconomy);
    StarsectorPrepatcherHooks.updateEconomyLocationMapIfNeeded(oldEconomy);
    require(oldEconomy.dirtyCalls == 1 && oldEconomy.updateCalls == 2,
        "active economy fingerprint did not skip only the redundant dirty mark");
    require(economyStates().containsKey(oldEconomy), "active ReachEconomy was not cached");

    Object newEngine = new Object();
    activateEconomyOwner(newEngine, newEconomy);
    require(economyStates().isEmpty(), "economy cache was not detached at the generation boundary");
    StarsectorPrepatcherHooks.updateEconomyLocationMapIfNeeded(oldEconomy);
    require(oldEconomy.dirtyCalls == 2 && oldEconomy.updateCalls == 3,
        "stale ReachEconomy did not take the exact vanilla fallback");
    require(!economyStates().containsKey(oldEconomy), "stale ReachEconomy published into the new campaign generation");

    StarsectorPrepatcherHooks.updateEconomyLocationMapIfNeeded(newEconomy);
    require(economyStates().containsKey(newEconomy), "current ReachEconomy was rejected after an engine switch");
    long reset = StarsectorPrepatcherHooks.beginCampaignEngineChange(null);
    StarsectorPrepatcherHooks.completeCampaignEngineChange(null, reset);
  }

  private static void assertCommRelaySpatialIndex() throws Exception {
    ArrayList<MutableSystem> mutable = new ArrayList<>();
    ArrayList<StarSystemAPI> systems = new ArrayList<>();
    for (int i = 0; i < 128; i++) {
      MutableSystem system = new MutableSystem("s" + i, new Vector2f(i * 5f, 0f));
      mutable.add(system);
      systems.add(system.proxy);
    }

    Class<?> indexClass = Class.forName("com.fs.starfarer.api.StarsectorPrepatcherHooks$CommRelaySystemIndex");
    Method build = indexClass.getDeclaredMethod("build", List.class, long.class, float.class);
    Method collect = indexClass.getDeclaredMethod("collect", float.class, float.class, float.class, List.class);
    Method matches = indexClass.getDeclaredMethod("matches", List.class, long.class, int.class, float.class);
    build.setAccessible(true);
    collect.setAccessible(true);
    matches.setAccessible(true);
    long builtAt = System.nanoTime();
    float radius = 10f;
    Object index = build.invoke(null, systems, builtAt, radius);
    Field failed = indexClass.getDeclaredField("failed");
    failed.setAccessible(true);
    require(!(Boolean) failed.get(index), "comm-relay spatial index failed to build");
    require((Boolean) matches.invoke(index, systems, builtAt + 1_000_000L, 250, radius),
        "fresh comm-relay index did not match its source");

    mutable.get(100).location.x = 250f;
    require((Boolean) matches.invoke(index, systems, builtAt + 2_000_000L, 250, radius),
        "comm-relay index performed a full position audit inside TTL");
    require(!(Boolean) matches.invoke(index, systems, builtAt + 251_000_000L, 250, radius),
        "comm-relay index failed to detect a moved system at the TTL audit");
    Object movedIndex = build.invoke(null, systems, builtAt + 251_000_000L, radius);
    ArrayList<Object> movedEntries = new ArrayList<>();
    collect.invoke(movedIndex, 250f, 0f, radius, movedEntries);
    Field movedSystem =
        Class.forName("com.fs.starfarer.api.StarsectorPrepatcherHooks$CommRelaySystemEntry").getDeclaredField("system");
    movedSystem.setAccessible(true);
    boolean movedFound = false;
    for (Object entry : movedEntries) {
      if (movedSystem.get(entry) == systems.get(100)) movedFound = true;
    }
    require(movedFound, "rebuilt comm-relay index omitted a system that moved into range");
    mutable.get(100).location.x = 500f;

    ArrayList<Object> entries = new ArrayList<>();
    collect.invoke(index, 250f, 0f, radius, entries);
    Field comparatorField = StarsectorPrepatcherHooks.class.getDeclaredField("COMM_RELAY_ENTRY_ORDER");
    comparatorField.setAccessible(true);
    entries.sort((Comparator) comparatorField.get(null));
    Class<?> entryClass = Class.forName("com.fs.starfarer.api.StarsectorPrepatcherHooks$CommRelaySystemEntry");
    Field ordinal = entryClass.getDeclaredField("ordinal");
    Field systemField = entryClass.getDeclaredField("system");
    ordinal.setAccessible(true);
    systemField.setAccessible(true);
    int previousOrdinal = -1;
    HashSet<Object> candidates = new HashSet<>();
    for (Object entry : entries) {
      int current = ordinal.getInt(entry);
      require(current > previousOrdinal, "comm-relay candidates were not restored to original system order");
      previousOrdinal = current;
      candidates.add(systemField.get(entry));
    }
    require(entries.size() < systems.size(), "comm-relay index did not narrow a spatially local query");
    for (int i = 0; i < systems.size(); i++) {
      float distance = Math.abs(mutable.get(i).location.x - 250f);
      if (distance <= radius) {
        require(candidates.contains(systems.get(i)), "comm-relay spatial index omitted an exactly in-range system");
      }
    }

    mutable.get(64).location.x += 1f;
    require(!(Boolean) matches.invoke(index, systems, builtAt + 300_000_000L, 250, radius),
        "comm-relay TTL validation ignored a system-location mutation");
    mutable.get(64).location.x -= 1f;

    ArrayList<StarSystemAPI> reordered = new ArrayList<>(systems);
    StarSystemAPI left = reordered.get(60);
    reordered.set(60, reordered.get(61));
    reordered.set(61, left);
    require(!(Boolean) matches.invoke(index, reordered, builtAt + 300_000_000L, 250, radius),
        "comm-relay TTL validation ignored a middle-order mutation");

    ArrayList<Object> malformed = new ArrayList<>(systems);
    malformed.set(64, new Object());
    Object failedIndex = build.invoke(null, malformed, builtAt, radius);
    require((Boolean) failed.get(failedIndex), "malformed comm-relay source did not produce a failed index");
    require((Boolean) matches.invoke(failedIndex, malformed, builtAt + 1_000_000L, 250, radius),
        "failed comm-relay build was not cached inside its retry TTL");
    require(!(Boolean) matches.invoke(failedIndex, malformed, builtAt + 300_000_000L, 250, radius),
        "failed comm-relay build remained cached beyond its retry TTL");
    Field failedEntries = indexClass.getDeclaredField("entries");
    Field failedFirst = indexClass.getDeclaredField("first");
    Field failedLast = indexClass.getDeclaredField("last");
    failedEntries.setAccessible(true);
    failedFirst.setAccessible(true);
    failedLast.setAccessible(true);
    require(((Object[]) failedEntries.get(failedIndex)).length == 0 && failedFirst.get(failedIndex) == null
        && failedLast.get(failedIndex) == null, "cached failed comm-relay build retained campaign systems");
  }

  private static void assertDisabledFallbacks() throws Exception {
    installConfig(config(false));
    ArrayList<Object> source = new ArrayList<>(List.of(new Object()));
    ArrayList first = StarsectorPrepatcherHooks.borrowShipList();
    ArrayList second = StarsectorPrepatcherHooks.borrowShipList();
    ArrayList snapshot = StarsectorPrepatcherHooks.borrowShipListSnapshot(source);
    HashSet firstSet = StarsectorPrepatcherHooks.borrowShipSet();
    HashSet secondSet = StarsectorPrepatcherHooks.borrowShipSet();
    ArrayList particle = StarsectorPrepatcherHooks.borrowParticleRemovalList();
    require(first != second && firstSet != secondSet,
        "disabled exp8 scratch unexpectedly pooled vanilla fallback collections");
    require(snapshot.equals(source) && snapshot != source && particle.isEmpty(),
        "disabled exp8 scratch did not preserve vanilla constructors");
  }

  private static PrepatcherConfig config(boolean enabled) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("patch.shipAdvanceScratch", Boolean.toString(enabled));
    properties.setProperty("patch.particleCleanup", Boolean.toString(enabled));
    properties.setProperty("patch.economyLocationCache", Boolean.toString(enabled));
    properties.setProperty("patch.economyPersistentSnapshots", Boolean.toString(enabled));
    properties.setProperty("patch.commRelaySystemIndex", Boolean.toString(enabled));
    properties.setProperty("logging.statsIntervalSeconds", "0");
    Constructor<PrepatcherConfig> constructor = PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
    constructor.setAccessible(true);
    PrepatcherConfig config = constructor.newInstance(properties);
    require(config.shipAdvanceScratch == enabled && config.particleCleanup == enabled
        && config.economyLocationCache == enabled && config.economyPersistentSnapshots == enabled
        && config.commRelaySystemIndex == enabled, "test configuration did not select the requested exp8 mode");
    return config;
  }

  private static void installConfig(PrepatcherConfig config) throws Exception {
    Field field = StarsectorPrepatcherHooks.class.getDeclaredField("config");
    field.setAccessible(true);
    field.set(null, config);
  }

  @SuppressWarnings("unchecked")
  private static Map<ReachEconomy, Object> economyStates() throws Exception {
    Field field = StarsectorPrepatcherHooks.class.getDeclaredField("ECONOMY_LOCATION_STATES");
    field.setAccessible(true);
    return (Map<ReachEconomy, Object>) field.get(null);
  }

  private static void activateEconomyOwner(Object engine, ReachEconomy economy) throws Exception {
    long transition = StarsectorPrepatcherHooks.beginCampaignEngineChange(engine);
    require(transition >= 0L, "test engine switch did not open a cache generation");
    StarsectorPrepatcherHooks.completeCampaignEngineChange(engine, transition);
    Field owner = StarsectorPrepatcherHooks.class.getDeclaredField("activeReachEconomy");
    owner.setAccessible(true);
    owner.set(null, new WeakReference<>(economy));
  }

  private static EconomyCycleProbe createEconomyCycleProbe(Method capture) throws Exception {
    ReachEconomy economy = new ReachEconomy();
    OwnerMarket market = new OwnerMarket(economy);
    Object state = capture.invoke(null, List.of(market.proxy));
    WeakHashMap<ReachEconomy, Object> cache = new WeakHashMap<>();
    cache.put(economy, state);
    return new EconomyCycleProbe(cache, new WeakReference<>(economy));
  }

  private static void awaitCollected(WeakReference<?> reference, String message) throws Exception {
    for (int attempt = 0; attempt < 80 && reference.get() != null; attempt++) {
      System.gc();
      System.runFinalization();
      byte[][] pressure = new byte[8][];
      for (int i = 0; i < pressure.length; i++) pressure[i] = new byte[128 * 1024];
      Thread.sleep(10L);
    }
    require(reference.get() == null, message);
  }

  private static void drainScratchScopes() {
    for (int i = 0; i < 16; i++) StarsectorPrepatcherHooks.endScratchScope();
  }

  private static Object primitiveDefault(Class<?> type) {
    if (type == boolean.class) return false;
    if (type == byte.class) return (byte) 0;
    if (type == short.class) return (short) 0;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == float.class) return 0f;
    if (type == double.class) return 0d;
    if (type == char.class) return '\0';
    return null;
  }

  private static Object objectMethod(Object proxy, Method method, Object[] args, String label) {
    return switch (method.getName()) {
      case "toString" -> label;
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == args[0];
      default -> null;
    };
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static final class MutableLocation implements InvocationHandler {
    String id;
    final LocationAPI proxy;

    MutableLocation(String id) {
      this.id = id;
      proxy = (LocationAPI) Proxy.newProxyInstance(LocationAPI.class.getClassLoader(),
          new Class<?>[]{LocationAPI.class}, this);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      if (method.getDeclaringClass() == Object.class) {
        return objectMethod(proxy, method, args, "Location[" + id + "]");
      }
      if (method.getName().equals("getId")) return id;
      return primitiveDefault(method.getReturnType());
    }
  }

  private static final class MutableMarket implements InvocationHandler {
    String id;
    LocationAPI location;
    final MarketAPI proxy;

    MutableMarket(String id, LocationAPI location) {
      this.id = id;
      this.location = location;
      proxy =
          (MarketAPI) Proxy.newProxyInstance(MarketAPI.class.getClassLoader(), new Class<?>[]{MarketAPI.class}, this);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      if (method.getDeclaringClass() == Object.class) {
        return objectMethod(proxy, method, args, "Market[" + id + "]");
      }
      return switch (method.getName()) {
        case "getId" -> id;
        case "getContainingLocation" -> location;
        default -> primitiveDefault(method.getReturnType());
      };
    }
  }

  private static final class OwnerMarket implements InvocationHandler {
    final Object owner;
    final MarketAPI proxy;

    OwnerMarket(Object owner) {
      this.owner = owner;
      proxy =
          (MarketAPI) Proxy.newProxyInstance(MarketAPI.class.getClassLoader(), new Class<?>[]{MarketAPI.class}, this);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      if (method.getDeclaringClass() == Object.class) { return objectMethod(proxy, method, args, "OwnerMarket"); }
      return switch (method.getName()) {
        case "getId" -> "owner-market";
        case "getContainingLocation" -> null;
        // This branch makes the intended value -> market -> owner key
        // retention chain explicit to the GC regression.
        case "getPlugin" -> owner;
        default -> primitiveDefault(method.getReturnType());
      };
    }
  }

  private static final class EconomyCycleProbe {
    final WeakHashMap<ReachEconomy, Object> cache;
    final WeakReference<ReachEconomy> economy;

    EconomyCycleProbe(WeakHashMap<ReachEconomy, Object> cache, WeakReference<ReachEconomy> economy) {
      this.cache = cache;
      this.economy = economy;
    }
  }

  private static final class CountingReachEconomy extends ReachEconomy {
    final List<MarketAPI> markets;
    int dirtyCalls;
    int updateCalls;

    CountingReachEconomy(List<MarketAPI> markets) {
      this.markets = markets;
    }

    @Override
    public List<MarketAPI> getMarkets() { return markets; }

    @Override
    public void setLocationCacheNeedsUpdate(boolean value) {
      if (value) dirtyCalls++;
    }

    @Override
    public void updateLocationMap() {
      updateCalls++;
    }
  }

  private static final class MutableSystem implements InvocationHandler {
    final String id;
    final Vector2f location;
    final StarSystemAPI proxy;

    MutableSystem(String id, Vector2f location) {
      this.id = id;
      this.location = location;
      proxy = (StarSystemAPI) Proxy.newProxyInstance(StarSystemAPI.class.getClassLoader(),
          new Class<?>[]{StarSystemAPI.class}, this);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      if (method.getDeclaringClass() == Object.class) {
        return objectMethod(proxy, method, args, "System[" + id + "]");
      }
      return switch (method.getName()) {
        case "getId" -> id;
        case "getLocation" -> location;
        default -> primitiveDefault(method.getReturnType());
      };
    }
  }

  private static final class EqualValue {
    final int value;

    EqualValue(int value) {
      this.value = value;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof EqualValue equal && equal.value == value;
    }

    @Override
    public int hashCode() {
      return value;
    }
  }

  private static final class ParticleMutationFailure extends RuntimeException {}

  private static final class ExplodingLinkedList<E> extends LinkedList<E> {
    private final int failAtRemove;

    ExplodingLinkedList(Collection<? extends E> source, int failAtRemove) {
      super(source);
      this.failAtRemove = failAtRemove;
    }

    @Override
    public Iterator<E> iterator() {
      Iterator<E> delegate = super.iterator();
      return new Iterator<>() {
        int removes;

        @Override
        public boolean hasNext() {
          return delegate.hasNext();
        }

        @Override
        public E next() {
          return delegate.next();
        }

        @Override
        public void remove() {
          if (++removes == failAtRemove) throw new ParticleMutationFailure();
          delegate.remove();
        }
      };
    }
  }
}
