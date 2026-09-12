package com.fs.starfarer.api;

import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.nio.file.Path;

/** Loader-neutral initialization and native AoTD ABI boundary. */
public final class StarsectorPrepatcherRuntimeBridge {
  public static final long AOTD_CAPABILITY_CONTRACT_HANDSHAKE = 1L;
  public static final long AOTD_CAPABILITY_NATIVE_DELIVERY_EVENTS = 1L << 1;
  public static final long AOTD_CAPABILITY_NATIVE_MUTATION_BOUNDARIES = 1L << 2;
  public static final long AOTD_CAPABILITY_MARKET_GENERATIONS = 1L << 3;
  public static final long AOTD_CAPABILITY_CLEAN_DEFICIT_SEMANTICS = 1L << 4;
  public static final long AOTD_CAPABILITY_AUTHORITATIVE_MARKET_STATE = 1L << 5;
  public static final long AOTD_CAPABILITY_PURE_PRICE_OFFLOAD = 1L << 6;
  public static final long AOTD_CAPABILITY_GLOBAL_PHASE_COORDINATION = 1L << 7;
  public static final long AOTD_CAPABILITY_RUNTIME_EPOCH_COORDINATION = 1L << 8;
  public static final long AOTD_CAPABILITY_UI_ECONOMY_DISPATCH = 1L << 9;
  public static final long AOTD_CAPABILITY_UI_MARKET_MUTATION_REFRESH = 1L << 10;
  public static final long AOTD_CAPABILITY_ECONOMY_RESTORE_COORDINATION = 1L << 11;

  public static final int MUTATION_REASON_IMMIGRATION_POLICY = 1 << 4;
  public static final int MUTATION_REASON_STOCKPILE_POLICY = 1 << 5;
  public static final int MUTATION_REASON_STABILIZATION = 1 << 6;
  public static final int MUTATION_REASON_FREE_PORT = 1 << 7;
  public static final int MUTATION_REASON_ADMIN_ASSIGNMENT = 1 << 8;
  public static final int MUTATION_REASON_INDUSTRY_QUEUE = 1 << 9;
  public static final int MUTATION_REASON_INDUSTRY_STRUCTURE = 1 << 10;
  public static final int MUTATION_REASON_INDUSTRY_MODIFIER = 1 << 11;
  public static final int MUTATION_REASON_CUSTOM_INDUSTRY_OPTION = 1 << 12;
  public static final int MUTATION_REASON_TRADE_TRANSACTION = 1 << 13;

  public static final int REFRESH_SCOPE_LOCAL_STATS = 1;
  public static final int REFRESH_SCOPE_LOCAL_COMMODITIES = 1 << 1;
  public static final int REFRESH_SCOPE_LOCAL_PRICE_STOCKPILE = 1 << 2;
  public static final int REFRESH_SCOPE_IMMIGRATION = 1 << 3;
  public static final int REFRESH_SCOPE_ACCESSIBILITY = 1 << 4;
  public static final int REFRESH_SCOPE_INDUSTRY_STATE = 1 << 5;
  public static final int REFRESH_SCOPE_LISTENER_BOUNDARY = 1 << 6;
  public static final int REFRESH_SCOPE_AFFECTED_GLOBAL_COMMODITIES = 1 << 7;
  public static final int REFRESH_SCOPE_GLOBAL_TOPOLOGY = 1 << 8;

  public static final String AOTD_CURRENT_FORK_VERSION = "1.0.14-spp13";
  public static final long AOTD_REQUIRED_CAPABILITIES = AOTD_CAPABILITY_CONTRACT_HANDSHAKE
      | AOTD_CAPABILITY_NATIVE_DELIVERY_EVENTS | AOTD_CAPABILITY_NATIVE_MUTATION_BOUNDARIES
      | AOTD_CAPABILITY_MARKET_GENERATIONS | AOTD_CAPABILITY_CLEAN_DEFICIT_SEMANTICS
      | AOTD_CAPABILITY_AUTHORITATIVE_MARKET_STATE | AOTD_CAPABILITY_PURE_PRICE_OFFLOAD
      | AOTD_CAPABILITY_GLOBAL_PHASE_COORDINATION | AOTD_CAPABILITY_RUNTIME_EPOCH_COORDINATION
      | AOTD_CAPABILITY_UI_ECONOMY_DISPATCH | AOTD_CAPABILITY_ECONOMY_RESTORE_COORDINATION;
  public static final long AOTD_CURRENT_DECLARED_CAPABILITIES =
      AOTD_REQUIRED_CAPABILITIES | AOTD_CAPABILITY_UI_MARKET_MUTATION_REFRESH;

  private StarsectorPrepatcherRuntimeBridge() {}

  public static void configure(Object rawConfig, Path modRoot) {
    if (!(rawConfig instanceof PrepatcherConfig config)) {
      String actual = rawConfig == null ? "null" : rawConfig.getClass().getName();
      throw new IllegalArgumentException("Unexpected prepatcher configuration type: " + actual);
    }
    StarsectorPrepatcherAotdContract.configure(config);
    StarsectorPrepatcherUiEconomyRuntime.configure(config);
    StarsectorPrepatcherHooks.configure(config, modRoot);
    StarsectorPrepatcherCoreWorldsRuntime.configure(config);
    StarsectorPrepatcherHyperspaceHooks.configure(config);
    StarsectorPrepatcherPresentationHooks.configure(config);
  }

  /** Loader-neutral registration endpoint used by the mod call-site transformer. */
  public static void registerDirectMarketCallSite(long siteId, String metadata) {
    StarsectorPrepatcherHooks.registerDirectMarketCallSite(siteId, metadata);
  }

  /** Writes pending-vs-delivered scheduler state without forcing a flush. */
  public static String dumpMarketSchedulerBaseline(String reason) {
    return StarsectorPrepatcherHooks.dumpMarketSchedulerBaseline(reason);
  }
}
