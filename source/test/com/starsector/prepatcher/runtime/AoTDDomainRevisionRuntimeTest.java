package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherAotdContract;
import com.fs.starfarer.api.StarsectorPrepatcherAotdRuntimeState;
import com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.nio.file.Files;
import java.nio.file.Path;

/** Regression contract for domain-specific AoTD revision delivery. */
public final class AoTDDomainRevisionRuntimeTest {
  private static final int DIRTY_STRUCTURE = 1;
  private static final int DIRTY_ACCESSIBILITY = 1 << 8;
  private static final int DIRTY_TRADE = 1 << 9;

  private AoTDDomainRevisionRuntimeTest() {}

  public static void main(String[] args) throws Exception {
    Path configFile = Files.createTempFile("prepatcher-aotd-domain", ".properties");
    Files.writeString(configFile, "patch.aotdCleanDeficitPath=true\n");
    StarsectorPrepatcherRuntimeBridge.configure(PrepatcherConfig.load(configFile), configFile.getParent());
    StarsectorPrepatcherAotdContract.setAoTDEconomyRestoreCompletionContract(true, "runtime-test");
    StarsectorPrepatcherAotdContract.registerAoTDForkContract("aotd_theory_of_toolbox",
        StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_FORK_VERSION,
        StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_DECLARED_CAPABILITIES, ignored -> {}, (industry, ids) -> null,
        () -> {});

    Object market = new Object();
    require(StarsectorPrepatcherAotdRuntimeState.getAoTDMarketStructuralGeneration(market) == 0L,
        "unexpected initial structural generation");

    long tradeToken = StarsectorPrepatcherAotdRuntimeState.beforeAoTDMarketMutation(market, 1);
    StarsectorPrepatcherAotdRuntimeState.afterAoTDMarketMutation(tradeToken, market, DIRTY_TRADE | DIRTY_ACCESSIBILITY,
        1L);
    require(StarsectorPrepatcherAotdRuntimeState.getAoTDMarketStructuralGeneration(market) == 0L,
        "non-structural dirty mask advanced structural generation");

    long structureToken = StarsectorPrepatcherAotdRuntimeState.beforeAoTDMarketMutation(market, 1);
    StarsectorPrepatcherAotdRuntimeState.afterAoTDMarketMutation(structureToken, market, DIRTY_STRUCTURE, 2L);
    require(StarsectorPrepatcherAotdRuntimeState.getAoTDMarketStructuralGeneration(market) == 1L,
        "structural mutation did not advance structural generation");

    long outer = StarsectorPrepatcherAotdRuntimeState.beforeAoTDMarketMutation(market, 1);
    long nested = StarsectorPrepatcherAotdRuntimeState.beforeAoTDMarketMutation(market, 2);
    require(outer == nested, "nested boundary did not share token");
    StarsectorPrepatcherAotdRuntimeState.afterAoTDMarketMutation(nested, market, DIRTY_STRUCTURE, 3L);
    StarsectorPrepatcherAotdRuntimeState.afterAoTDMarketMutation(outer, market, DIRTY_TRADE, 4L);
    require(StarsectorPrepatcherAotdRuntimeState.getAoTDMarketStructuralGeneration(market) == 2L,
        "nested structural mutation did not advance exactly once");

    Files.deleteIfExists(configFile);
    System.out.println("AoTDDomainRevisionRuntimeTest: PASS");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
