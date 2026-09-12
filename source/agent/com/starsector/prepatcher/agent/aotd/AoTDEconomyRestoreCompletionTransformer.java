package com.starsector.prepatcher.agent.aotd;

import com.starsector.prepatcher.agent.TransformDiagnostics;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.StructuralMismatchException;
import com.starsector.prepatcher.agent.PrepatcherLog;

/**
 * Publishes one loader-neutral signal after Starsector has restored every industry and reapplied
 * every market. The hook is deliberately outside both loops and therefore cannot expose a
 * partially restored market to the AoTD scheduler.
 */
public final class AoTDEconomyRestoreCompletionTransformer implements ClassFileTransformer {
  public static final String TARGET = EconomyRestoreCompletionSignal.TARGET;
  public static final String METHOD = EconomyRestoreCompletionSignal.METHOD;
  public static final String METHOD_DESC = EconomyRestoreCompletionSignal.METHOD_DESC;

  private static final String RUNTIME_BINARY = "com.fs.starfarer.api.StarsectorPrepatcherAotdContract";
  private static final String STATUS_PROPERTY = "starsector.prepatcher.aotdEconomyRestoreCompletionPatch";

  private final boolean enabled;
  private final ClassLoader runtimeLoader;

  public AoTDEconomyRestoreCompletionTransformer(boolean enabled, ClassLoader runtimeLoader) {
    this.enabled = enabled;
    this.runtimeLoader = runtimeLoader;
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    if (!TARGET.equals(className)) return null;
    if (!enabled) {
      record("DISABLED");
      publish(false, "disabled by patch.aotdEconomyRestoreCoordination");
      return null;
    }
    if (!runtimeVisibleFrom(loader)) {
      reject("SKIPPED_LOADER", "target loader cannot see runtime bridge");
      return null;
    }

    try {
      ClassNode node = new ClassNode();
      new ClassReader(classfileBuffer).accept(node, 0);
      EconomyRestoreCompletionSignal patch = new EconomyRestoreCompletionSignal();
      Match<EconomyRestoreCompletionSignal.Plan> match = patch.match(node);
      if (match.alreadyApplied) {
        if (!publish(true, null)) {
          recordRejected("SKIPPED_RUNTIME");
          return null;
        }
        record("ALREADY_PATCHED");
        return null;
      }
      if (match.plan == null) { throw mismatch(match.reason); }
      patch.mutate(match.plan);
      patch.verify(match.plan, node);

      ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
      node.accept(writer);
      byte[] transformed = writer.toByteArray();
      if (!publish(true, null)) {
        recordRejected("SKIPPED_RUNTIME");
        return null;
      }
      record("APPLIED");
      PrepatcherLog.info("APPLIED AoTD economy-restore completion hook");
      return transformed;
    } catch (StructuralMismatchException mismatch) {
      reject("SKIPPED_STRUCTURAL", mismatch.getMessage());
      return null;
    } catch (Throwable failure) {
      publish(false, failure.getClass().getName());
      recordRejected("SKIPPED_ERROR");
      PrepatcherLog.error(
          "SKIPPED_ERROR AoTD economy-restore completion hook; " + "restore capability remains unavailable", failure);
      return null;
    }
  }

  private void reject(String status, String reason) {
    publish(false, reason);
    recordRejected(status);
    PrepatcherLog
        .warn(status + " AoTD economy-restore completion hook: " + reason + "; restore capability remains unavailable");
  }

  private boolean publish(boolean operational, String reason) {
    if (runtimeLoader == null) return true;
    try {
      Class<?> bridge = Class.forName(RUNTIME_BINARY, false, runtimeLoader);
      Method method = bridge.getMethod("setAoTDEconomyRestoreCompletionContract", boolean.class, String.class);
      method.invoke(null, operational, reason);
      return true;
    } catch (Throwable failure) {
      PrepatcherLog.warn("Could not publish AoTD economy-restore contract: " + failure.getClass().getName());
      return false;
    }
  }

  private boolean runtimeVisibleFrom(ClassLoader loader) {
    if (runtimeLoader == null) return true;
    for (ClassLoader current = loader; current != null; current = current.getParent()) {
      if (current == runtimeLoader) return true;
    }
    return false;
  }

  static public String statusProperty() {
    return STATUS_PROPERTY;
  }

  private static void record(String status) {
    TransformDiagnostics.status(STATUS_PROPERTY, status);
  }

  private static void recordRejected(String status) {
    TransformDiagnostics.reject(STATUS_PROPERTY, status);
  }
}
