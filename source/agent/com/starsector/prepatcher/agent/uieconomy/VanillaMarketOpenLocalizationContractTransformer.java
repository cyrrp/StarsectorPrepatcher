package com.starsector.prepatcher.agent.uieconomy;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.Set;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.PrepatcherLog;
import com.starsector.prepatcher.agent.patch.StructuralMismatchException;

/**
 * Exact-current-game semantic gate for the synchronous vanilla live-market
 * localization. It changes no bytecode and publishes Economy/ReachEconomy
 * readiness independently so load order cannot create a partial activation.
 */
public final class VanillaMarketOpenLocalizationContractTransformer implements ClassFileTransformer {
  public static final String ECONOMY = VanillaMarketOpenEconomyContract.TARGET;
  public static final String REACH = VanillaMarketOpenReachContract.TARGET;
  public static final Set<String> TARGET_CLASSES = Set.of(ECONOMY, REACH);
  private static final String RUNTIME = "com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge";

  private final boolean enabled;
  private final ClassLoader runtimeLoader;
  private volatile boolean economyReady;
  private volatile boolean reachReady;

  public VanillaMarketOpenLocalizationContractTransformer(boolean enabled, ClassLoader runtimeLoader) {
    this.enabled = enabled;
    this.runtimeLoader = runtimeLoader;
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    if (!enabled || (!ECONOMY.equals(className) && !REACH.equals(className))) { return null; }
    if (!runtimeVisibleFrom(loader)) {
      reject(className, "SKIPPED_LOADER", "target loader cannot see runtime bridge");
      return null;
    }
    try {
      ClassNode node = read(classfileBuffer);
      if (ECONOMY.equals(className)) {
        VanillaMarketOpenEconomyContract patch = new VanillaMarketOpenEconomyContract();
        Match<VanillaMarketOpenEconomyContract.Plan> match = patch.match(node);
        if (match.alreadyApplied || match.plan == null) { throw mismatch(match.reason); }
        patch.mutate(match.plan);
        patch.verify(match.plan, node);
        economyReady = true;
        if (!publishRuntime(true, true, null)) {
          record(className, "SKIPPED_RUNTIME");
          return null;
        }
      } else {
        VanillaMarketOpenReachContract patch = new VanillaMarketOpenReachContract();
        Match<VanillaMarketOpenReachContract.Plan> match = patch.match(node);
        if (match.alreadyApplied || match.plan == null) { throw mismatch(match.reason); }
        patch.mutate(match.plan);
        patch.verify(match.plan, node);
        reachReady = true;
        if (!publishRuntime(false, true, null)) {
          record(className, "SKIPPED_RUNTIME");
          return null;
        }
      }
      record(className, "READY");
      recordAggregate();
      PrepatcherLog.info("READY vanilla market-open localization contract: " + className);
    } catch (StructuralMismatchException mismatch) {
      reject(className, "SKIPPED_STRUCTURAL", mismatch.getMessage());
    } catch (Throwable failure) {
      publishRuntime(ECONOMY.equals(className), false, failure.getClass().getName());
      record(className, "SKIPPED_ERROR");
      recordAggregate();
      PrepatcherLog.error("SKIPPED_ERROR vanilla market-open localization " + "contract for " + className
          + "; original global steps remain active", failure);
    }
    return null;
  }

  private void reject(String className, String status, String reason) {
    if (ECONOMY.equals(className)) economyReady = false;
    else reachReady = false;
    publishRuntime(ECONOMY.equals(className), false, reason);
    record(className, status);
    recordAggregate();
    PrepatcherLog.warn(status + " vanilla market-open localization contract for " + className + ": " + reason
        + "; original global steps remain active");
  }

  private boolean publishRuntime(boolean economy, boolean operational, String reason) {
    if (runtimeLoader == null) return true;
    try {
      Class<?> bridge = Class.forName(RUNTIME, false, runtimeLoader);
      Method publish =
          bridge.getMethod(economy ? "setVanillaMarketOpenEconomyContract" : "setVanillaMarketOpenReachContract",
              boolean.class, String.class);
      publish.invoke(null, operational, reason);
      return true;
    } catch (Throwable failure) {
      PrepatcherLog
          .warn("Could not publish vanilla market-open localization " + "contract: " + failure.getClass().getName());
      return false;
    }
  }

  private void recordAggregate() {
    String status = economyReady && reachReady ? "READY" : "AWAITING";
    System.setProperty("starsector.prepatcher.vanillaMarketOpenLocalizationContract", status);
  }

  private static void record(String className, String status) {
    String suffix = ECONOMY.equals(className) ? "Economy" : "Reach";
    System.setProperty("starsector.prepatcher.vanillaMarketOpenLocalization" + suffix + "Contract", status);
  }

  private boolean runtimeVisibleFrom(ClassLoader loader) {
    if (runtimeLoader == null) return true;
    for (ClassLoader current = loader; current != null; current = current.getParent()) {
      if (current == runtimeLoader) return true;
    }
    return false;
  }

  private static ClassNode read(byte[] bytes) {
    ClassNode node = new ClassNode(Opcodes.ASM8);
    new ClassReader(bytes).accept(node, 0);
    return node;
  }
}
