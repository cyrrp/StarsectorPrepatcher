package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherHooks;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.campaign.econ.reach.ReachEconomy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

/** Runtime semantics for persistent Economy/Market copy-on-write snapshots. */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class PersistentEconomyRuntimeRegressionTest {
  private PersistentEconomyRuntimeRegressionTest() {}

  public static void main(String[] args) throws Exception {
    installConfig(config(true));
    try {
      assertFrameEpochAndAudit();
      assertTimedEpochAndAudit();
      assertSourceIdentityReplacement();
      assertOwnerLocalLocationState();
      assertCopyOnWriteReentrancy();
      assertNonRandomAccessAudit();
      assertForeignStateFailOpen();
      assertDisabledFallback();
    } finally {
      installConfig(null);
    }
    System.out.println("OK persistent-economy-runtime concrete-arraylist stable-identity"
        + " explicit-epoch immediate-rebuild direct-edit bounded-audit"
        + " source-identity immediate-rebuild owner-local-location-cache"
        + " copy-on-write reentrant-snapshot-isolation linked-list-audit"
        + " foreign-state fail-open disabled-fresh-fallback");
  }

  private static void assertFrameEpochAndAudit() {
    Object a = new Object();
    Object b = new Object();
    Object c = new Object();
    ArrayList<Object> source = new ArrayList<>(List.of(a, b));
    Object state = StarsectorPrepatcherHooks.newPersistentSnapshotState();

    ArrayList first = StarsectorPrepatcherHooks.borrowPersistentSnapshotFrames(state, source, 4, 1);
    require(first.getClass() == ArrayList.class && first.equals(List.of(a, b)),
        "initial frame snapshot changed type/order/content");
    require(StarsectorPrepatcherHooks.borrowPersistentSnapshotFrames(state, source, 4, 1) == first,
        "unchanged frame snapshot did not keep stable identity");

    source.add(c); // Direct live-list edit intentionally bypasses the epoch.
    require(StarsectorPrepatcherHooks.borrowPersistentSnapshotFrames(state, source, 4, 1) == first,
        "direct edit was audited earlier than its configured bound (1)");
    require(StarsectorPrepatcherHooks.borrowPersistentSnapshotFrames(state, source, 4, 1) == first,
        "direct edit was audited earlier than its configured bound (2)");
    ArrayList audited = StarsectorPrepatcherHooks.borrowPersistentSnapshotFrames(state, source, 4, 1);
    require(audited != first && audited.equals(List.of(a, b, c)),
        "frame audit did not rebuild after a direct live-list edit");
    require(first.equals(List.of(a, b)), "frame audit mutated an already published snapshot");

    long before = StarsectorPrepatcherHooks.persistentSnapshotEpoch(state);
    source.remove(a);
    StarsectorPrepatcherHooks.markPersistentSnapshotStructure(state);
    require(StarsectorPrepatcherHooks.persistentSnapshotEpoch(state) == before + 1L,
        "explicit structure mark did not increment the epoch exactly once");
    ArrayList marked = StarsectorPrepatcherHooks.borrowPersistentSnapshotFrames(state, source, 4, 1);
    require(marked != audited && marked.equals(List.of(b, c)), "explicit epoch did not rebuild on the next borrow");
    require(audited.equals(List.of(a, b, c)), "explicit epoch rebuild mutated the previous snapshot");
  }

  private static void assertTimedEpochAndAudit() {
    Object a = new Object();
    Object b = new Object();
    ArrayList<Object> source = new ArrayList<>(List.of(a));
    Object state = StarsectorPrepatcherHooks.newPersistentSnapshotState();

    ArrayList first = StarsectorPrepatcherHooks.borrowPersistentSnapshotTimed(state, source, 60_000, 0);
    source.add(b);
    require(StarsectorPrepatcherHooks.borrowPersistentSnapshotTimed(state, source, 60_000, 0) == first,
        "timed snapshot ignored its not-yet-expired audit interval");

    Object auditEveryBorrow = StarsectorPrepatcherHooks.newPersistentSnapshotState();
    ArrayList old =
        StarsectorPrepatcherHooks.borrowPersistentSnapshotTimed(auditEveryBorrow, new ArrayList<>(List.of(a)), 0, 0);
    ArrayList<Object> mutable = new ArrayList<>(List.of(a));
    Object immediateState = StarsectorPrepatcherHooks.newPersistentSnapshotState();
    ArrayList immediate = StarsectorPrepatcherHooks.borrowPersistentSnapshotTimed(immediateState, mutable, 0, 0);
    mutable.add(b);
    ArrayList rebuilt = StarsectorPrepatcherHooks.borrowPersistentSnapshotTimed(immediateState, mutable, 0, 0);
    require(rebuilt != immediate && rebuilt.equals(List.of(a, b)),
        "zero-ms timed audit did not detect a direct edit immediately");
    require(immediate.equals(List.of(a)), "timed audit mutated an already published snapshot");
    require(old.equals(List.of(a)), "independent timed state was corrupted");
  }

  private static void assertSourceIdentityReplacement() {
    Object a = new Object();
    Object b = new Object();
    ArrayList<Object> firstSource = new ArrayList<>(List.of(a, b));
    ArrayList<Object> replacement = new ArrayList<>(List.of(a, b));
    Object state = StarsectorPrepatcherHooks.newPersistentSnapshotState();
    ArrayList first = StarsectorPrepatcherHooks.borrowPersistentSnapshotTimed(state, firstSource, 60_000, 0);
    ArrayList second = StarsectorPrepatcherHooks.borrowPersistentSnapshotTimed(state, replacement, 60_000, 0);
    require(second != first && second.equals(first), "backing-list identity replacement was not detected immediately");
    require(first.equals(List.of(a, b)), "source replacement mutated the previously published snapshot");
  }

  private static void assertOwnerLocalLocationState() throws Exception {
    MutableLocation location = new MutableLocation("owner-local-location");
    MutableMarket market = new MutableMarket("owner-local-market", location.proxy);
    ArrayList<MarketAPI> firstList = new ArrayList<>(List.of(market.proxy));
    CountingReachEconomy economy = new CountingReachEconomy(firstList);
    Object state = StarsectorPrepatcherHooks.newPersistentSnapshotState();
    Object engine = new Object();
    activateEconomyOwner(engine, economy);
    try {
      StarsectorPrepatcherHooks.updateEconomyLocationMapIfNeededPersistent(economy, state);
      require(economy.dirtyCalls == 1 && economy.updateCalls == 1,
          "owner-local location state did not perform the initial vanilla rebuild");
      Object firstLocationState = privateField(state, "locationState");
      require(firstLocationState != null, "owner-local location fingerprint was not stored in Economy state");
      require(economyStates().isEmpty(), "persistent location path unexpectedly used the global WeakHashMap");

      // auditMs=0 in this test: an unchanged audit must only move the deadline,
      // not allocate and publish a replacement fingerprint.
      StarsectorPrepatcherHooks.updateEconomyLocationMapIfNeededPersistent(economy, state);
      require(economy.dirtyCalls == 1 && economy.updateCalls == 2, "unchanged owner-local audit dirtied ReachEconomy");
      require(privateField(state, "locationState") == firstLocationState,
          "unchanged owner-local audit rebuilt the location fingerprint");

      long epoch = StarsectorPrepatcherHooks.persistentSnapshotEpoch(state);
      economy.markets = new ArrayList<>(firstList);
      StarsectorPrepatcherHooks.updateEconomyLocationMapIfNeededPersistent(economy, state);
      require(economy.dirtyCalls == 2 && economy.updateCalls == 3,
          "backing-list replacement did not dirty ReachEconomy immediately");
      require(StarsectorPrepatcherHooks.persistentSnapshotEpoch(state) == epoch + 1L,
          "direct market-list replacement did not advance the shared epoch");
      require(privateField(state, "locationState") != firstLocationState,
          "changed owner-local location fingerprint was not replaced copy-on-write");
    } finally {
      long transition = StarsectorPrepatcherHooks.beginCampaignEngineChange(null);
      StarsectorPrepatcherHooks.completeCampaignEngineChange(null, transition);
    }
  }

  private static void assertCopyOnWriteReentrancy() {
    Object a = new Object();
    Object b = new Object();
    ArrayList<Object> source = new ArrayList<>(List.of(a));
    Object state = StarsectorPrepatcherHooks.newPersistentSnapshotState();
    ArrayList outer = StarsectorPrepatcherHooks.borrowPersistentSnapshotFrames(state, source, 120, 2);

    source.add(b);
    StarsectorPrepatcherHooks.markPersistentSnapshotStructure(state);
    ArrayList nested = StarsectorPrepatcherHooks.borrowPersistentSnapshotFrames(state, source, 120, 2);
    require(nested != outer && nested.equals(List.of(a, b)), "nested borrow did not publish a copy-on-write snapshot");
    require(outer.equals(List.of(a)), "nested rebuild invalidated the outer iterator snapshot");
    require(StarsectorPrepatcherHooks.borrowPersistentSnapshotFrames(state, source, 120, 2) == nested,
        "nested rebuilt snapshot did not become the stable current snapshot");
  }

  private static void assertNonRandomAccessAudit() {
    Object a = new Object();
    Object equalButDifferent = new EqualValue(7);
    LinkedList<Object> source = new LinkedList<>();
    source.add(a);
    source.add(equalButDifferent);
    Object state = StarsectorPrepatcherHooks.newPersistentSnapshotState();
    ArrayList first = StarsectorPrepatcherHooks.borrowPersistentSnapshotFrames(state, source, 1, 1);
    source.set(1, new EqualValue(7));
    ArrayList second = StarsectorPrepatcherHooks.borrowPersistentSnapshotFrames(state, source, 1, 1);
    require(second != first && second.get(1) == source.get(1),
        "non-RandomAccess audit used equals instead of identity/order");
    require(first.get(1) == equalButDifferent, "non-RandomAccess audit mutated the previous snapshot");
  }

  private static void assertForeignStateFailOpen() {
    ArrayList<Object> source = new ArrayList<>(List.of(new Object()));
    ArrayList first = StarsectorPrepatcherHooks.borrowPersistentSnapshotFrames(new Object(), source, 120, 1);
    ArrayList second = StarsectorPrepatcherHooks.borrowPersistentSnapshotFrames(new Object(), source, 120, 1);
    require(first != second && first.equals(source) && second.equals(source),
        "foreign/missing persistent state did not fail open to fresh vanilla copies");
    StarsectorPrepatcherHooks.markPersistentSnapshotStructure(null);
    StarsectorPrepatcherHooks.markPersistentSnapshotStructure(new Object());
  }

  private static void assertDisabledFallback() throws Exception {
    installConfig(config(false));
    ArrayList<Object> source = new ArrayList<>(List.of(new Object()));
    Object state = StarsectorPrepatcherHooks.newPersistentSnapshotState();
    ArrayList first = StarsectorPrepatcherHooks.borrowPersistentSnapshotFrames(state, source, 120, 1);
    ArrayList second = StarsectorPrepatcherHooks.borrowPersistentSnapshotFrames(state, source, 120, 1);
    require(first != second && first.equals(source) && second.equals(source),
        "disabled persistent snapshots did not preserve fresh-copy behavior");
    installConfig(config(true));
  }

  private static PrepatcherConfig config(boolean enabled) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("patch.economyPersistentSnapshots", Boolean.toString(enabled));
    properties.setProperty("patch.economyLocationCache", Boolean.toString(enabled));
    properties.setProperty("economy.structureAuditMs", "0");
    properties.setProperty("market.structureAuditFrames", "120");
    properties.setProperty("logging.statsIntervalSeconds", "0");
    Constructor<PrepatcherConfig> constructor = PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
    constructor.setAccessible(true);
    PrepatcherConfig config = constructor.newInstance(properties);
    require(config.economyPersistentSnapshots == enabled, "persistent snapshot test configuration was not applied");
    return config;
  }

  private static void installConfig(PrepatcherConfig config) throws Exception {
    Field field = StarsectorPrepatcherHooks.class.getDeclaredField("config");
    field.setAccessible(true);
    field.set(null, config);
  }

  @SuppressWarnings("unchecked")
  private static java.util.Map<ReachEconomy, Object> economyStates() throws Exception {
    Field field = StarsectorPrepatcherHooks.class.getDeclaredField("ECONOMY_LOCATION_STATES");
    field.setAccessible(true);
    return (java.util.Map<ReachEconomy, Object>) field.get(null);
  }

  private static void activateEconomyOwner(Object engine, ReachEconomy economy) throws Exception {
    long transition = StarsectorPrepatcherHooks.beginCampaignEngineChange(engine);
    require(transition >= 0L, "test engine switch did not open a cache generation");
    StarsectorPrepatcherHooks.completeCampaignEngineChange(engine, transition);
    Field owner = StarsectorPrepatcherHooks.class.getDeclaredField("activeReachEconomy");
    owner.setAccessible(true);
    owner.set(null, new java.lang.ref.WeakReference<>(economy));
  }

  private static Object privateField(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static Object objectMethod(Object proxy, Method method, Object[] args, String label) {
    return switch (method.getName()) {
      case "toString" -> label;
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == args[0];
      default -> null;
    };
  }

  private static Object primitiveDefault(Class<?> type) {
    if (!type.isPrimitive()) return null;
    if (type == boolean.class) return false;
    if (type == char.class) return '\0';
    if (type == byte.class) return (byte) 0;
    if (type == short.class) return (short) 0;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == float.class) return 0f;
    if (type == double.class) return 0d;
    return null;
  }

  private static final class MutableLocation implements InvocationHandler {
    final String id;
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
    final String id;
    final LocationAPI location;
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

  private static final class CountingReachEconomy extends ReachEconomy {
    List<MarketAPI> markets;
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

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static final class EqualValue {
    private final int value;
    EqualValue(int value) {
      this.value = value;
    }
    @Override
    public boolean equals(Object other) {
      return other instanceof EqualValue value && this.value == value.value;
    }
    @Override
    public int hashCode() {
      return value;
    }
  }
}
