package com.fs.starfarer.api;

import static com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge.*;

import com.starsector.prepatcher.agent.PrepatcherConfig;
import com.starsector.prepatcher.agent.PrepatcherLog;

import com.fs.starfarer.api.campaign.econ.MarketAPI;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.BiFunction;

/** AoTD fork handshake, capability negotiation and loader-neutral callbacks. */
public final class StarsectorPrepatcherAotdContract {
  static final int AOTD_UI_ECONOMY_ACTION_MARKET_OPEN = 1;
  static final int AOTD_UI_ECONOMY_ACTION_CARGO = 2;
  static final int AOTD_UI_ECONOMY_ACTION_MARKET_MUTATION = 3;
  static final long AOTD_UI_CARGO_SYNTHETIC = 1L;
  static final long AOTD_UI_CARGO_LIVE_MARKET = 2L;
  private static final String AOTD_UI_ECONOMY_ACTION_METHOD = "dispatchPrepatcherUiEconomyStep";

  private static final String AOTD_MOD_ID = "aotd_theory_of_toolbox";
  private static final String AOTD_ECONOMY_CLASS = "data.kaysaar.aotd.tot.scripts.economy.AoTDEconomy";
  private static final String CORE_LIFECYCLE_CLASS = "com.fs.starfarer.api.impl.campaign.CoreLifecyclePluginImpl";
  private static final Object AOTD_CONTRACT_LOCK = new Object();
  private static final AtomicLong AOTD_DELIVERY_LISTENER_FAILURES = new AtomicLong();
  private static final AtomicLong AOTD_ECONOMY_RESTORE_SIGNALS = new AtomicLong();
  private static final AtomicLong AOTD_ECONOMY_RESTORE_COMPLETIONS = new AtomicLong();
  private static final AtomicLong AOTD_ECONOMY_RESTORE_FAILURES = new AtomicLong();
  private static final LongAdder AOTD_CONDITION_ONLY_DELIVERIES_IGNORED = new LongAdder();
  private static final LongAdder AOTD_OTHER_NON_ECONOMY_DELIVERIES_IGNORED = new LongAdder();

  private static volatile boolean aotdContractRegistered;
  private static volatile String aotdForkVersion = "";
  private static volatile long aotdDeclaredCapabilities;
  private static volatile long aotdNegotiatedCapabilities;
  private static volatile Consumer<Object> aotdDeliveryListener;
  private static volatile String aotdDeliveryListenerStatus = "unregistered";
  private static volatile BiFunction<Object, Object, Object> aotdDeficitResolver;
  private static volatile Runnable aotdEconomyRestoreListener;
  private static volatile String aotdEconomyRestoreListenerStatus = "unregistered";
  private static volatile boolean aotdCleanDeficitConfigured;
  private static volatile boolean aotdEconomyRestoreConfigured;
  private static volatile boolean aotdEconomyRestoreContractOperational;

  private StarsectorPrepatcherAotdContract() {}

  static void configure(PrepatcherConfig config) {
    aotdCleanDeficitConfigured = config.aotdCleanDeficitPath;
    aotdEconomyRestoreConfigured = config.aotdEconomyRestoreCoordination;
    aotdEconomyRestoreContractOperational = false;
    synchronized (AOTD_CONTRACT_LOCK) {
      aotdNegotiatedCapabilities &= ~AOTD_CAPABILITY_ECONOMY_RESTORE_COORDINATION;
      aotdEconomyRestoreListener = null;
      aotdEconomyRestoreListenerStatus =
          aotdEconomyRestoreConfigured ? "awaiting-structural-proof" : "disabled-by-config";
      publishAoTDNegotiatedCapabilitiesProperty();
    }
  }

  private static long supportedAoTDCapabilities() {
    long supported = AOTD_REQUIRED_CAPABILITIES & ~AOTD_CAPABILITY_ECONOMY_RESTORE_COORDINATION;
    if (aotdEconomyRestoreConfigured && aotdEconomyRestoreContractOperational) {
      supported |= AOTD_CAPABILITY_ECONOMY_RESTORE_COORDINATION;
    }
    if (StarsectorPrepatcherUiEconomyRuntime.isUiMarketMutationRefreshOperational()) {
      supported |= AOTD_CAPABILITY_UI_MARKET_MUTATION_REFRESH;
    }
    return supported;
  }

  /** Registers the fork contract and its loader-neutral callbacks. */
  public static long registerAoTDForkContract(String modId, String forkVersion, long declaredCapabilities,
      Consumer<Object> deliveryListener, BiFunction<Object, Object, Object> deficitResolver,
      Runnable economyRestoreListener) {
    String rejection = null;
    if (!AOTD_MOD_ID.equals(modId)) rejection = "mod-id-mismatch";
    else if (!AOTD_CURRENT_FORK_VERSION.equals(forkVersion)) {
      rejection = "fork-version-mismatch";
    } else if (declaredCapabilities != AOTD_CURRENT_DECLARED_CAPABILITIES) {
      rejection = "declared-capabilities-mismatch";
    } else if (deliveryListener == null) rejection = "delivery-listener-missing";
    else if (!aotdCleanDeficitConfigured) rejection = "clean-deficit-disabled";
    else if (deficitResolver == null) rejection = "deficit-resolver-missing";
    else if (economyRestoreListener == null) {
      rejection = "economy-restore-listener-missing";
    } else if (deliveryListener.getClass().getClassLoader() != deficitResolver.getClass().getClassLoader()
        || deliveryListener.getClass().getClassLoader() != economyRestoreListener.getClass().getClassLoader()) {
          rejection = "callback-loader-mismatch";
        } else
      if (deliveryListener.getClass().getClassLoader() == null) {
        rejection = "callback-loader-bootstrap";
      }
    if (rejection != null) { return rejectAoTDForkContract(rejection, modId, forkVersion, declaredCapabilities); }
    ensureAoTDEconomyRestoreCompletionProof();
    long negotiated = supportedAoTDCapabilities();
    if ((negotiated & AOTD_REQUIRED_CAPABILITIES) != AOTD_REQUIRED_CAPABILITIES) {
      return rejectAoTDForkContract("required-runtime-capability-unavailable", modId, forkVersion,
          declaredCapabilities);
    }
    synchronized (AOTD_CONTRACT_LOCK) {
      if (aotdContractRegistered
          && (!aotdForkVersion.equals(forkVersion) || aotdDeclaredCapabilities != declaredCapabilities)) {
        System.setProperty("starsector.prepatcher.aotdContract", "conflicting-registration");
        PrepatcherLog.warn("Conflicting AoTD fork contract registration: existing=" + aotdForkVersion + "/0x"
            + Long.toHexString(aotdDeclaredCapabilities) + ", incoming=" + forkVersion + "/0x"
            + Long.toHexString(declaredCapabilities));
        return 0L;
      }
      if (aotdContractRegistered && aotdDeficitResolver != null
          && aotdDeficitResolver.getClass().getClassLoader() != deficitResolver.getClass().getClassLoader()) {
        System.setProperty("starsector.prepatcher.aotdContract", "conflicting-loader-registration");
        PrepatcherLog.warn("Conflicting AoTD fork loader registration: existing="
            + describeLoader(aotdDeficitResolver.getClass().getClassLoader()) + ", incoming="
            + describeLoader(deficitResolver.getClass().getClassLoader()));
        return 0L;
      }
      aotdContractRegistered = true;
      aotdForkVersion = forkVersion;
      aotdDeclaredCapabilities = declaredCapabilities;
      aotdNegotiatedCapabilities = negotiated;
      aotdDeliveryListener = deliveryListener;
      aotdDeliveryListenerStatus = deliveryListener == null ? "not-negotiated" : "active";
      aotdDeficitResolver = deficitResolver;
      aotdEconomyRestoreListener = economyRestoreListener;
      aotdEconomyRestoreListenerStatus = "active";
    }
    System.setProperty("starsector.prepatcher.aotdContract", "active");
    System.setProperty("starsector.prepatcher.aotdForkVersion", forkVersion);
    System.setProperty("starsector.prepatcher.aotdDeclaredCapabilities", "0x" + Long.toHexString(declaredCapabilities));
    System.setProperty("starsector.prepatcher.aotdNegotiatedCapabilities", "0x" + Long.toHexString(negotiated));
    PrepatcherLog.info("AoTD fork contract active: fork=" + forkVersion + ", declared=0x"
        + Long.toHexString(declaredCapabilities) + ", negotiated=0x" + Long.toHexString(negotiated));
    return negotiated;
  }

  /**
   * Resolves the only ordering race in the required spp13 handshake. Loading without
   * initialization is enough to run the already-installed exact transformer; it neither
   * executes CoreLifecycle code nor retains a fork loader. The structural transformer publishes
   * the operational gate synchronously before Class.forName returns.
   */
  private static void ensureAoTDEconomyRestoreCompletionProof() {
    if (!aotdEconomyRestoreConfigured || aotdEconomyRestoreContractOperational) return;
    try {
      ClassLoader loader = StarsectorPrepatcherAotdContract.class.getClassLoader();
      Class.forName(CORE_LIFECYCLE_CLASS, false, loader);
    } catch (Throwable failure) {
      // Registration below observes the still-closed gate and fails closed. The exact
      // transformer owns detailed structural diagnostics when class definition succeeds.
      PrepatcherLog.warn("Could not establish AoTD economy-restore structural proof: " + failure.getClass().getName());
    }
  }

  private static long rejectAoTDForkContract(String reason, String modId, String forkVersion,
      long declaredCapabilities) {
    System.setProperty("starsector.prepatcher.aotdContract", "rejected-" + reason);
    System.setProperty("starsector.prepatcher.aotdForkVersion", forkVersion == null ? "null" : forkVersion);
    System.setProperty("starsector.prepatcher.aotdDeclaredCapabilities", "0x" + Long.toHexString(declaredCapabilities));
    PrepatcherLog.warn("Rejected AoTD Scheduler Fork contract: reason=" + reason + "; expectedModId=" + AOTD_MOD_ID
        + "; expectedFork=" + AOTD_CURRENT_FORK_VERSION + "; expectedDeclared=0x"
        + Long.toHexString(AOTD_CURRENT_DECLARED_CAPABILITIES) + "; actualModId=" + modId + "; actualFork="
        + forkVersion + "; actualDeclared=0x" + Long.toHexString(declaredCapabilities));
    return 0L;
  }

  public static long getAoTDNegotiatedCapabilities() { return aotdNegotiatedCapabilities; }

  public static String getAoTDForkContractStatus() {
    if (!aotdContractRegistered) return "unregistered";
    int markets = StarsectorPrepatcherAotdRuntimeState.trackedMarketCount();
    return "active; fork=" + aotdForkVersion + "; declared=0x" + Long.toHexString(aotdDeclaredCapabilities)
        + "; negotiated=0x" + Long.toHexString(aotdNegotiatedCapabilities) + "; trackedMarkets=" + markets
        + "; deficitResolver=" + (aotdDeficitResolver != null) + "; deliveryListener=" + aotdDeliveryListenerStatus
        + "; callbackFailures=" + AOTD_DELIVERY_LISTENER_FAILURES.get() + "; economyRestoreListener="
        + aotdEconomyRestoreListenerStatus + "; economyRestoreSignals=" + AOTD_ECONOMY_RESTORE_SIGNALS.get()
        + "; economyRestoreCompletions=" + AOTD_ECONOMY_RESTORE_COMPLETIONS.get() + "; economyRestoreFailures="
        + AOTD_ECONOMY_RESTORE_FAILURES.get() + "; conditionOnlyDeliveriesIgnored="
        + AOTD_CONDITION_ONLY_DELIVERIES_IGNORED.sum() + "; otherNonEconomyDeliveriesIgnored="
        + AOTD_OTHER_NON_ECONOMY_DELIVERIES_IGNORED.sum() + "; staleMarketBoundaryCloses="
        + StarsectorPrepatcherAotdRuntimeState.staleMarketBoundaryCloses() + "; duplicateMarketBoundaryCloses="
        + StarsectorPrepatcherAotdRuntimeState.duplicateMarketBoundaryCloses() + "; marketBoundaryTokenMismatches="
        + StarsectorPrepatcherAotdRuntimeState.marketBoundaryTokenMismatches() + "; unknownMarketBoundaryCloses="
        + StarsectorPrepatcherAotdRuntimeState.unknownMarketBoundaryCloses() + "; uiEconomyDispatchOperational="
        + StarsectorPrepatcherUiEconomyRuntime.isUiEconomyDispatchOperational() + "; detachedCargoVanillaContract="
        + StarsectorPrepatcherUiEconomyRuntime.isVanillaDetachedCargoEconomyContractOperational()
        + "; detachedCargoAoTDDispatches=" + StarsectorPrepatcherUiEconomyRuntime.detachedCargoAoTDDispatches()
        + "; lootTransferAoTDDispatches=" + StarsectorPrepatcherUiEconomyRuntime.lootTransferAoTDDispatches()
        + "; detachedCargoVanillaStepsSkipped="
        + StarsectorPrepatcherUiEconomyRuntime.getDetachedCargoVanillaStepsSkipped()
        + "; detachedCargoUnknownEconomyFallbacks="
        + StarsectorPrepatcherUiEconomyRuntime.getDetachedCargoUnknownEconomyFallbacks();
  }

  /**
   * Publishes whether the exact current-game CoreLifecycle completion hook was proven and
   * installed. A failed structural contract can only remove the restore capability.
   */
  public static void setAoTDEconomyRestoreCompletionContract(boolean operational, String reason) {
    boolean ready = aotdEconomyRestoreConfigured && operational;
    aotdEconomyRestoreContractOperational = ready;
    if (ready) {
      synchronized (AOTD_CONTRACT_LOCK) {
        if (aotdEconomyRestoreListener == null) {
          aotdEconomyRestoreListenerStatus = "awaiting-registration";
        }
      }
    } else {
      synchronized (AOTD_CONTRACT_LOCK) {
        aotdNegotiatedCapabilities &= ~AOTD_CAPABILITY_ECONOMY_RESTORE_COORDINATION;
        aotdEconomyRestoreListener = null;
        aotdEconomyRestoreListenerStatus = operational ? "disabled-by-config" : "disabled-structural";
        publishAoTDNegotiatedCapabilitiesProperty();
      }
    }
    System.setProperty("starsector.prepatcher.aotdEconomyRestoreCompletion", ready ? "ready" : "disabled");
    if (!ready && reason != null && !reason.isBlank()) {
      PrepatcherLog.warn("AoTD economy-restore coordination disabled: " + reason);
    }
  }

  /**
   * Called once at the successful tail of CoreLifecyclePluginImpl.econPostSaveRestore().
   * This method intentionally performs no market scan and never lets a fork callback escape
   * into Starsector's save/load lifecycle.
   */
  public static void publishAoTDEconomyRestoreComplete() {
    try {
      publishAoTDEconomyRestoreCompleteImpl();
    } catch (Throwable ignored) {
      // This hook runs inside Starsector's save/load lifecycle. Even a secondary
      // diagnostics failure must not turn a completed restore into a failed save.
    }
  }

  private static void publishAoTDEconomyRestoreCompleteImpl() {
    Runnable listener;
    synchronized (AOTD_CONTRACT_LOCK) {
      if (!aotdContractRegistered || !aotdEconomyRestoreContractOperational
          || (aotdNegotiatedCapabilities & AOTD_CAPABILITY_ECONOMY_RESTORE_COORDINATION) == 0L) {
        return;
      }
      listener = aotdEconomyRestoreListener;
    }
    AOTD_ECONOMY_RESTORE_SIGNALS.incrementAndGet();
    if (listener == null) {
      long failures = AOTD_ECONOMY_RESTORE_FAILURES.incrementAndGet();
      disableAoTDEconomyRestoreListener(null, "missing-listener", failures, null);
      return;
    }
    try {
      listener.run();
      AOTD_ECONOMY_RESTORE_COMPLETIONS.incrementAndGet();
    } catch (LinkageError failure) {
      long failures = AOTD_ECONOMY_RESTORE_FAILURES.incrementAndGet();
      disableAoTDEconomyRestoreListener(listener, "linkage:" + failure.getClass().getName(), failures, failure);
    } catch (Throwable failure) {
      long failures = AOTD_ECONOMY_RESTORE_FAILURES.incrementAndGet();
      if (failures <= 4L || (failures & (failures - 1L)) == 0L) {
        PrepatcherLog.warn("AoTD economy-restore callback failed open (#" + failures + "): " + failure);
      }
    }
  }

  private static void disableAoTDEconomyRestoreListener(Runnable expected, String reason, long failures,
      LinkageError failure) {
    boolean disabled = false;
    synchronized (AOTD_CONTRACT_LOCK) {
      if (expected == null || aotdEconomyRestoreListener == expected) {
        aotdEconomyRestoreListener = null;
        aotdEconomyRestoreListenerStatus = "disabled-" + reason;
        aotdNegotiatedCapabilities &= ~AOTD_CAPABILITY_ECONOMY_RESTORE_COORDINATION;
        publishAoTDNegotiatedCapabilitiesProperty();
        disabled = true;
      }
    }
    if (!disabled) return;
    System.setProperty("starsector.prepatcher.aotdContract", "economy-restore-listener-disabled");
    if (failure == null) {
      PrepatcherLog.warn("AoTD economy-restore listener disabled (#" + failures + "): " + reason);
    } else {
      PrepatcherLog.error("AoTD economy-restore listener disabled after linkage failure (#" + failures
          + "); only economy-restore coordination was removed for this " + "session.", failure);
    }
  }

  private static void publishAoTDNegotiatedCapabilitiesProperty() {
    System.setProperty("starsector.prepatcher.aotdNegotiatedCapabilities",
        "0x" + Long.toHexString(aotdNegotiatedCapabilities));
  }

  public static long getAoTDEconomyRestoreSignalCount() { return AOTD_ECONOMY_RESTORE_SIGNALS.get(); }

  public static long getAoTDEconomyRestoreCompletionCount() { return AOTD_ECONOMY_RESTORE_COMPLETIONS.get(); }

  public static long getAoTDEconomyRestoreFailureCount() { return AOTD_ECONOMY_RESTORE_FAILURES.get(); }

  /** Called by the clean BaseIndustry wrapper. Null means use preserved vanilla code. */
  public static Object resolveAoTDMaxDeficit(Object industry, String[] commodityIds) {
    if ((aotdNegotiatedCapabilities & AOTD_CAPABILITY_CLEAN_DEFICIT_SEMANTICS) == 0L) { return null; }
    BiFunction<Object, Object, Object> resolver = aotdDeficitResolver;
    if (resolver == null) {
      throw new IllegalStateException("AoTD clean deficit capability is active without a resolver");
    }
    Object result = resolver.apply(industry, commodityIds);
    if (result == null) { throw new IllegalStateException("AoTD deficit resolver returned null"); }
    return result;
  }

  /** Called by Hooks only after a real Market.advance callback returned. */
  public static void publishAoTDMarketTimeDelivered(Object market, float deliveredAmount, int origin) {
    if (market == null || !aotdContractRegistered) return;
    StarsectorPrepatcherAotdRuntimeState.recordMarketDelivery(market, deliveredAmount, origin);
    Consumer<Object> listener = aotdDeliveryListener;
    if ((aotdNegotiatedCapabilities & AOTD_CAPABILITY_NATIVE_DELIVERY_EVENTS) == 0L || listener == null) return;
    try {
      listener.accept(market);
    } catch (LinkageError failure) {
      long failures = AOTD_DELIVERY_LISTENER_FAILURES.incrementAndGet();
      boolean disabled = false;
      synchronized (AOTD_CONTRACT_LOCK) {
        if (aotdDeliveryListener == listener) {
          aotdDeliveryListener = null;
          aotdNegotiatedCapabilities &= ~AOTD_CAPABILITY_NATIVE_DELIVERY_EVENTS;
          aotdDeliveryListenerStatus = "disabled-linkage:" + failure.getClass().getName();
          disabled = true;
        }
      }
      if (disabled) {
        System.setProperty("starsector.prepatcher.aotdContract", "delivery-listener-disabled");
        System.setProperty("starsector.prepatcher.aotdNegotiatedCapabilities",
            "0x" + Long.toHexString(aotdNegotiatedCapabilities));
        PrepatcherLog.error("AoTD delivery listener disabled after linkage failure (#" + failures
            + "); native delivery events capability was removed for "
            + "this session. AoTD will observe the live capability mask and "
            + "fall back to generation resynchronization.", failure);
      }
    } catch (Throwable failure) {
      long failures = AOTD_DELIVERY_LISTENER_FAILURES.incrementAndGet();
      if (failures <= 4L || (failures & (failures - 1L)) == 0L) {
        PrepatcherLog.warn("AoTD delivery listener failed open (#" + failures + "): " + failure);
      }
    }
  }

  static boolean isExactAoTDEconomy(Object economy) {
    return economy != null && AOTD_ECONOMY_CLASS.equals(economy.getClass().getName())
        && isRegisteredAoTDForkLoader(economy.getClass().getClassLoader());
  }

  static boolean isOwnedAoTDEconomyHierarchy(Object economy) {
    if (economy == null) return false;
    for (Class<?> type = economy.getClass(); type != null; type = type.getSuperclass()) {
      if (AOTD_ECONOMY_CLASS.equals(type.getName())) { return isRegisteredAoTDForkLoader(type.getClassLoader()); }
    }
    return false;
  }

  /**
   * Proves ownership from the loader-local callbacks supplied by the exact
   * transformed SchedulerBridge. No Class or ClassLoader is cached here.
   */
  private static boolean isRegisteredAoTDForkLoader(ClassLoader candidate) {
    if (!aotdContractRegistered || candidate == null) return false;
    BiFunction<Object, Object, Object> resolver = aotdDeficitResolver;
    if (resolver == null || resolver.getClass().getClassLoader() != candidate) { return false; }
    Consumer<Object> listener = aotdDeliveryListener;
    return listener == null || listener.getClass().getClassLoader() == candidate;
  }

  private static String describeLoader(ClassLoader loader) {
    if (loader == null) return "bootstrap";
    return loader.getClass().getName() + '@' + Integer.toHexString(System.identityHashCode(loader));
  }

  static boolean invokeAoTDUiEconomyAction(Object economy, Object market, int action, long detail,
      String[] commodityIds) {
    if (!isExactAoTDEconomy(economy) || (aotdNegotiatedCapabilities & AOTD_CAPABILITY_UI_ECONOMY_DISPATCH) == 0L) {
      return false;
    }
    MarketAPI typedMarket = market instanceof MarketAPI ? (MarketAPI) market : null;
    if (typedMarket == null && !(action == AOTD_UI_ECONOMY_ACTION_CARGO && detail == AOTD_UI_CARGO_SYNTHETIC)) {
      return false;
    }
    if (action == AOTD_UI_ECONOMY_ACTION_MARKET_MUTATION
        && (aotdNegotiatedCapabilities & AOTD_CAPABILITY_UI_MARKET_MUTATION_REFRESH) == 0L) {
      return false;
    }
    try {
      Method method = economy.getClass().getMethod(AOTD_UI_ECONOMY_ACTION_METHOD, int.class, MarketAPI.class,
          long.class, String[].class);
      if (method.getDeclaringClass() != economy.getClass() || method.getReturnType() != boolean.class
          || !Modifier.isPublic(method.getModifiers()) || !Modifier.isFinal(method.getModifiers())) {
        return false;
      }
      String[] ids = commodityIds == null ? null : commodityIds.clone();
      return Boolean.TRUE
          .equals(method.invoke(economy, Integer.valueOf(action), typedMarket, Long.valueOf(detail), ids));
    } catch (ReflectiveOperationException | LinkageError | SecurityException failure) {
      StarsectorPrepatcherUiEconomyRuntime.logFailOpen(
          "Owned AoTD UI economy action failed closed to the " + "original global step: action=" + action, failure);
      return false;
    }
  }

  static long packAoTDUiMutationDetail(int reason, int scope) {
    return ((long) reason << 32) | (scope & 0xffffffffL);
  }

  /** Called by the MarketAPI-aware Hooks layer before the loader-neutral ABI. */
  public static void recordAoTDNonEconomyDelivery(boolean conditionOnly) {
    if (conditionOnly) AOTD_CONDITION_ONLY_DELIVERIES_IGNORED.increment();
    else AOTD_OTHER_NON_ECONOMY_DELIVERIES_IGNORED.increment();
  }

  static void requireAoTDCapability(long capability) {
    if ((aotdNegotiatedCapabilities & capability) != capability) {
      throw new IllegalStateException("AoTD capability was not negotiated: 0x" + Long.toHexString(capability));
    }
  }

  /** Fail-stop downgrade used by exact UI call-site transformers. */
  public static void disableAoTDUiEconomyDispatch(String reason) {
    StarsectorPrepatcherUiEconomyRuntime.disableUiEconomyDispatch();
    synchronized (AOTD_CONTRACT_LOCK) {
      aotdNegotiatedCapabilities &= ~(AOTD_CAPABILITY_UI_ECONOMY_DISPATCH | AOTD_CAPABILITY_UI_MARKET_MUTATION_REFRESH);
      System.setProperty("starsector.prepatcher.aotdNegotiatedCapabilities",
          "0x" + Long.toHexString(aotdNegotiatedCapabilities));
    }
    System.setProperty("starsector.prepatcher.aotdUiEconomyDispatch", "disabled");
    PrepatcherLog.warn("AoTD UI economy dispatch disabled: " + reason);
  }
}
