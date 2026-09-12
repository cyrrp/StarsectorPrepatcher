package com.starsector.prepatcher.agent.aotd;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.PrepatcherLog;

/** Rewrites the fork-owned no-op bridge to direct target-loader ABI calls. */
public final class AoTDSchedulerBridgeTransformer implements ClassFileTransformer {
  public static final String TARGET = AoTDSchedulerBridge.TARGET;
  private static final int CURRENT_BRIDGE_SCHEMA = 10;
  private static final String CURRENT_BRIDGE_MARKER = "AOTD_SCHEDULER_BRIDGE_V10";

  private final ClassLoader runtimeLoader;

  public AoTDSchedulerBridgeTransformer(ClassLoader runtimeLoader) {
    this.runtimeLoader = runtimeLoader;
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    if (!TARGET.equals(className)) return null;
    if (!runtimeVisibleFrom(loader)) {
      record("SKIPPED_LOADER");
      PrepatcherLog.warn("AoTD bridge not patched: bridge loader=" + loaderName(loader) + ", runtime loader="
          + loaderName(runtimeLoader));
      return null;
    }
    try {
      if (containsForbiddenReflectionReference(classfileBuffer)) {
        record("SKIPPED_REFLECTION_PRESENT");
        PrepatcherLog.warn("AoTD bridge not patched: reflection reference present.");
        return null;
      }
      ClassNode node = new ClassNode();
      new ClassReader(classfileBuffer).accept(node, 0);
      if (!TARGET.equals(node.name)) return null;
      if (fieldValue(node, AoTDSchedulerBridge.PATCH_FIELD) != null) {
        record("ALREADY_PATCHED");
        return null;
      }
      Object schemaValue = fieldValue(node, "BRIDGE_SCHEMA");
      Object markerValue = fieldValue(node, "BRIDGE_MARKER");
      if (!Integer.valueOf(CURRENT_BRIDGE_SCHEMA).equals(schemaValue) || !CURRENT_BRIDGE_MARKER.equals(markerValue)) {
        record("UNSUPPORTED_CONTRACT");
        PrepatcherLog.warn("Unsupported AoTD Scheduler Bridge: expected schema=" + CURRENT_BRIDGE_SCHEMA + ", marker="
            + CURRENT_BRIDGE_MARKER + "; actual schema=" + schemaValue + ", marker=" + markerValue);
        return null;
      }
      AoTDSchedulerBridge patch = new AoTDSchedulerBridge();
      Match<AoTDSchedulerBridge.Plan> match = patch.match(node);
      patch.mutate(match.plan);
      patch.verify(match.plan, node);
      ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
      node.accept(writer);
      record("APPLIED");
      System.setProperty("starsector.prepatcher.aotdBridgeLoader", loaderName(loader));
      return writer.toByteArray();
    } catch (Throwable failure) {
      record("SKIPPED_ERROR");
      PrepatcherLog.error("AoTD bridge transformation failed open.", failure);
      return null;
    }
  }

  private boolean runtimeVisibleFrom(ClassLoader loader) {
    if (runtimeLoader == null) return true;
    for (ClassLoader current = loader; current != null; current = current.getParent())
      if (current == runtimeLoader) return true;
    return false;
  }

  private static Object fieldValue(ClassNode node, String name) {
    for (FieldNode field : node.fields) if (name.equals(field.name)) return field.value;
    return null;
  }

  private static boolean containsForbiddenReflectionReference(byte[] bytes) {
    String constants = new String(bytes, StandardCharsets.ISO_8859_1);
    return constants.contains("java/lang/reflect") || constants.contains("java.lang.reflect");
  }

  private static void record(String status) {
    System.setProperty("starsector.prepatcher.aotdBridgePatch", status);
  }

  private static String loaderName(ClassLoader loader) {
    if (loader == null) return "bootstrap";
    return loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
  }
}
