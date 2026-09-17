package com.starsector.prepatcher.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.ref.WeakReference;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One explicitly ordered transformation chain. Every stage sees the bytes returned by
 * the preceding stage; a null result means "keep the current bytes".
 */
final class OrderedTransformerPipeline implements ClassFileTransformer {
  private final List<Stage> stages;
  private final WeakReference<ClassLoader> runtimeLoader;
  private final boolean predefineBridgeRoute;
  private final boolean repairIllegalNamesInAgentPipeline;

  OrderedTransformerPipeline(List<Stage> stages, ClassLoader runtimeLoader, boolean predefineBridgeRoute,
      boolean repairIllegalNamesInAgentPipeline) {
    this.stages = Collections.unmodifiableList(new ArrayList<>(stages));
    this.runtimeLoader = new WeakReference<>(runtimeLoader);
    this.predefineBridgeRoute = predefineBridgeRoute;
    this.repairIllegalNamesInAgentPipeline = repairIllegalNamesInAgentPipeline;
    System.setProperty("starsector.prepatcher.transformerOrder",
        String.join(" -> ", this.stages.stream().map(Stage::name).toList()));
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    if (className == null || classfileBuffer == null) return null;
    // Platform ownership, not package spelling: java.xml also owns org.xml/org.w3c.
    // These bytes are never a game/mod transformation surface and may use a newer
    // classfile format than the ASM used to inspect Starsector's bytecode.
    if (loader == null || loader == ClassLoader.getPlatformClassLoader()) return null;
    ClassLoader expected = runtimeLoader.get();
    if (predefineBridgeRoute && expected != null && loader == expected) {
      // Old Faster Rendering defines its com.fs classes from the bridge below.
      // Returning bytes here would make Java 27 verify an intermediate agent result.
      return null;
    }
    return apply(loader, className, classBeingRedefined, protectionDomain, classfileBuffer, true);
  }

  byte[] applyPredefine(String resourceName, byte[] bytes) {
    if (resourceName == null || bytes == null) return bytes;
    String className =
        resourceName.endsWith(".class") ? resourceName.substring(0, resourceName.length() - 6) : resourceName;
    ClassLoader loader = runtimeLoader.get();
    if (loader == null) {
      PrepatcherLog.warn(
          "Faster Rendering predefine bridge lost its target loader; " + "leaving " + resourceName + " unchanged.");
      return bytes;
    }
    byte[] transformed = apply(loader, className, null, null, bytes, false);
    return transformed == null ? bytes : transformed;
  }

  private byte[] apply(ClassLoader loader, String className, Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain, byte[] original, boolean nullWhenUnchanged) {
    byte[] current = original;
    byte[] pipelineInput = original;
    boolean changed = false;
    try (TransformDiagnostics.Transaction diagnostics = TransformDiagnostics.begin(className)) {
      try {
        if (repairIllegalNamesInAgentPipeline) {
          byte[] compatible = IllegalObfuscatedMemberNameRepair.repair(className, current);
          if (compatible != current) {
            current = compatible;
            changed = true;
          }
        }
        // Java 27 compatibility normalization is a prerequisite to the patch group,
        // not one of its optional mutations. A later patch failure rolls back to
        // these loadable bytes instead of restoring an invalid obfuscated class.
        pipelineInput = current;
        GameNamespace namespace = GameNamespace.current();
        current = namespace.toCanonical(current);
        String canonicalName = namespace.canonicalName(className);
        boolean stageChanged = false;
        // The complete chain is an atomic class-level group. Its input is retained
        // until every ordered member has had a chance to validate its post-state.
        for (Stage stage : stages) {
          diagnostics.beginStage();
          byte[] next =
              stage.transformer().transform(loader, canonicalName, classBeingRedefined, protectionDomain, current);
          TransformDiagnostics.StageResult result = diagnostics.finishStage(next);
          String rejection = result.rejection();
          if (rejection != null) {
            throw new IllegalStateException("stage '" + stage.name() + "' published " + rejection);
          }
          if (result.bytes() != null) {
            current = result.bytes();
            changed = true;
            stageChanged = true;
          }
        }
        // Translation is inside the same transaction: conversion failure must not
        // publish successful patch diagnostics or return a half-translated class.
        byte[] output = stageChanged ? namespace.toNativeRuntime(current)
            : (changed || !nullWhenUnchanged ? pipelineInput : null);
        diagnostics.commit();
        return output;
      } catch (Throwable failure) {
        diagnostics.rollback();
        PrepatcherLog.error("Atomic transformer pipeline rolled back " + className
            + " to its input bytes after an ordered-stage failure.", failure);
        System.setProperty("starsector.prepatcher.pipelineFailure." + className.replace('/', '.'),
            failure.getClass().getSimpleName() + ":" + String.valueOf(failure.getMessage()));
        if (nullWhenUnchanged && pipelineInput == original) return null;
        return pipelineInput;
      }
    }
  }

  record Stage(String name, ClassFileTransformer transformer) {
    Stage {
      if (name == null || name.isBlank() || transformer == null) {
        throw new IllegalArgumentException("Pipeline stage metadata is incomplete");
      }
    }
  }
}
