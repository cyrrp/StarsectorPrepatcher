package com.starsector.prepatcher.agent.uieconomy;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.PrepatcherLog;
import com.starsector.prepatcher.agent.patch.StructuralMismatchException;

/**
 * Guards the exact vanilla market-transaction doubleStep call with a runtime
 * fail-open predicate. The original virtual call remains in bytecode and runs
 * whenever transaction inspection or the targeted refresh cannot be proven safe.
 */
public final class TradeMarketMutationTransformer implements ClassFileTransformer {
  public static final String TARGET = "com/fs/starfarer/campaign/ui/class";

  private final boolean enabled;
  private final ClassLoader runtimeLoader;

  public TradeMarketMutationTransformer(boolean enabled, ClassLoader runtimeLoader) {
    this.enabled = enabled;
    this.runtimeLoader = runtimeLoader;
  }

  public boolean isTargetEnabled(String internalName) {
    return enabled && TARGET.equals(internalName);
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    if (!enabled || !TARGET.equals(className)) return null;
    if (!runtimeVisibleFrom(loader)) {
      record("SKIPPED_LOADER");
      PrepatcherLog.warn("Trade market-mutation refresh not patched: target loader=" + loaderName(loader)
          + ", runtime loader=" + loaderName(runtimeLoader));
      return null;
    }
    try {
      ClassNode node = read(classfileBuffer);
      if (!TARGET.equals(node.name)) { throw mismatch("unexpected owner " + node.name); }
      TradeMarketMutationRefresh patch = new TradeMarketMutationRefresh();
      Match<TradeMarketMutationRefresh.Plan> match = patch.match(node);
      if (match.alreadyApplied) {
        record("ALREADY_APPLIED");
        return null;
      }
      if (match.plan == null) { throw mismatch(match.reason); }
      patch.mutate(match.plan);
      patch.verify(match.plan, node);
      ClassWriter writer = new LoaderNeutralClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
      node.accept(writer);
      byte[] transformed = writer.toByteArray();
      patch.verify(match.plan, read(transformed));
      record("APPLIED");
      PrepatcherLog.info("APPLIED trade market-mutation refresh to " + className
          + ".confirmTransaction: pre-twConfirm debt barrier and exact "
          + "transaction-local guard preserve doubleStep as fallback");
      return transformed;
    } catch (StructuralMismatchException mismatch) {
      record("SKIPPED_STRUCTURAL");
      PrepatcherLog.warn("SKIPPED_STRUCTURAL trade market-mutation refresh in " + className + ": "
          + mismatch.getMessage() + "; original doubleStep remains active");
      return null;
    } catch (Throwable failure) {
      record("SKIPPED_ERROR");
      PrepatcherLog.error(
          "SKIPPED_ERROR trade market-mutation refresh in " + className + "; original doubleStep remains active",
          failure);
      return null;
    }
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

  static public String statusProperty() {
    return "starsector.prepatcher.tradeMarketMutationPatch";
  }

  private static void record(String status) {
    System.setProperty(statusProperty(), status);
  }

  private static String loaderName(ClassLoader loader) {
    if (loader == null) return "bootstrap";
    return loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
  }

  private static final class LoaderNeutralClassWriter extends ClassWriter {
    LoaderNeutralClassWriter(int flags) {
      super(flags);
    }
    @Override
    protected String getCommonSuperClass(String type1, String type2) {
      return "java/lang/Object";
    }
  }
}
