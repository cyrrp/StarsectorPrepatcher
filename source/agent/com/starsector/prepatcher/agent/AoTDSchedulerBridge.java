package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/** Rewrites the fork-owned no-op bridge to direct target-loader ABI calls. */
final class AoTDSchedulerBridge implements StructuredPatch<AoTDSchedulerBridge.Plan> {
  static final String TARGET = "data/kaysaar/aotd/tot/compat/SchedulerBridge";
  private static final String STATE_DESC = "Ldata/kaysaar/aotd/tot/compat/SchedulerBridge$State;";
  private static final String INITIALIZE_DESC = "()" + STATE_DESC;
  private static final String ACTIVATE_DESC = "(J)" + STATE_DESC;
  private static final String RUNTIME_BRIDGE = "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";
  static final String PATCH_FIELD = "smo$patched$aotdSchedulerBridge";
  static final String PATCH_VALUE = "StarsectorPrepatcher:aotd-bridge-v10";

  record Rewritten(MethodNode method, String runtimeName) {
  }

  record Plan(List<FieldNode> classFields, List<Rewritten> rewritten) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode initialize = require(node, "initialize", INITIALIZE_DESC);
    require(node, "activateFromPrepatcher", ACTIVATE_DESC);
    require(node, "deliverySignalConsumer", "()Ljava/util/function/Consumer;");
    require(node, "deficitResolverFunction", "()Ljava/util/function/BiFunction;");
    require(node, "economyRestoreCompleteSignal", "()Ljava/lang/Runnable;");
    List<Rewritten> rewritten = new ArrayList<>();
    rewritten.add(new Rewritten(requireStatic(initialize), "registerAoTDForkContract"));
    rewritten.add(new Rewritten(requireStatic(node, "getDeliveredMarketGeneration", "(Ljava/lang/Object;)J"),
        "getAoTDMarketDeliveredGeneration"));
    rewritten.add(new Rewritten(requireStatic(node, "getLastMarketDeliverySequence", "(Ljava/lang/Object;)J"),
        "getAoTDMarketLastDeliverySequence"));
    rewritten.add(new Rewritten(requireStatic(node, "getLastMarketDeliveredAmount", "(Ljava/lang/Object;)F"),
        "getAoTDMarketLastDeliveredAmount"));
    rewritten.add(new Rewritten(requireStatic(node, "getMarketStructuralGeneration", "(Ljava/lang/Object;)J"),
        "getAoTDMarketStructuralGeneration"));
    rewritten.add(new Rewritten(requireStatic(node, "beforeMarketMutation", "(Ljava/lang/Object;I)J"),
        "beforeAoTDMarketMutation"));
    rewritten.add(new Rewritten(requireStatic(node, "afterMarketMutation", "(JLjava/lang/Object;IJ)V"),
        "afterAoTDMarketMutation"));
    rewritten.add(new Rewritten(requireStatic(node, "publishRuntimeEpoch", "(JJ)V"), "publishAoTDRuntimeEpoch"));
    rewritten.add(new Rewritten(requireStatic(node, "getRuntimeCapabilities", "()J"), "getAoTDNegotiatedCapabilities"));
    rewritten.add(new Rewritten(requireStatic(node, "beforeGlobalBoundary", "(IZ)J"), "beforeAoTDGlobalBoundary"));
    rewritten.add(new Rewritten(requireStatic(node, "afterGlobalBoundary", "(JJ)V"), "afterAoTDGlobalBoundary"));
    return Match.plan(new Plan(node.fields, rewritten));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    List<Rewritten> rewritten = plan.rewritten();
    rewrite(rewritten.get(0).method(), buildInitializeBody(), 7, 0);
    rewrite(rewritten.get(1).method(), directObjectLong("getAoTDMarketDeliveredGeneration"), 1, 1);
    rewrite(rewritten.get(2).method(), directObjectLong("getAoTDMarketLastDeliverySequence"), 1, 1);
    rewrite(rewritten.get(3).method(), directObjectFloat("getAoTDMarketLastDeliveredAmount"), 1, 1);
    rewrite(rewritten.get(4).method(), directObjectLong("getAoTDMarketStructuralGeneration"), 1, 1);
    rewrite(rewritten.get(5).method(), directBeforeMutation(), 2, 2);
    rewrite(rewritten.get(6).method(), directAfterMutation(), 6, 6);
    rewrite(rewritten.get(7).method(), directPublishRuntimeEpoch(), 4, 4);
    rewrite(rewritten.get(8).method(), directNoArgLong("getAoTDNegotiatedCapabilities"), 2, 0);
    rewrite(rewritten.get(9).method(), directBeforeGlobalBoundary(), 2, 2);
    rewrite(rewritten.get(10).method(), directAfterGlobalBoundary(), 4, 4);

    plan.classFields()
        .add(new FieldNode(Opcodes.ASM8,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, PATCH_FIELD,
            "Ljava/lang/String;", null, PATCH_VALUE));
    PatchReport report = new PatchReport();
    report.add("scheduler bridge ABI rewrites", rewritten.size());
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    Object marker = fieldValue(node, PATCH_FIELD);
    if (!PATCH_VALUE.equals(marker)) { throw new IllegalStateException("bridge patch marker missing after rewrite"); }
    for (Rewritten entry : plan.rewritten()) {
      boolean found = false;
      for (AbstractInsnNode instruction : entry.method().instructions) {
        if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
            && RUNTIME_BRIDGE.equals(call.owner) && entry.runtimeName().equals(call.name)) {
          found = true;
          break;
        }
      }
      if (!found) { throw new IllegalStateException("bridge ABI method lost runtime call: " + entry.method().name); }
    }
  }

  private static InsnList buildInitializeBody() {
    InsnList code = new InsnList();
    String contract = "data/kaysaar/aotd/tot/compat/PrepatcherContract";
    code.add(new FieldInsnNode(Opcodes.GETSTATIC, contract, "MOD_ID", "Ljava/lang/String;"));
    code.add(new FieldInsnNode(Opcodes.GETSTATIC, contract, "FORK_VERSION", "Ljava/lang/String;"));
    code.add(new FieldInsnNode(Opcodes.GETSTATIC, contract, "DECLARED_CAPABILITIES", "J"));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, "deliverySignalConsumer",
        "()Ljava/util/function/Consumer;", false));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, "deficitResolverFunction",
        "()Ljava/util/function/BiFunction;", false));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, "economyRestoreCompleteSignal", "()Ljava/lang/Runnable;",
        false));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE, "registerAoTDForkContract",
        "(Ljava/lang/String;Ljava/lang/String;JLjava/util/function/Consumer;"
            + "Ljava/util/function/BiFunction;Ljava/lang/Runnable;)J",
        false));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, "activateFromPrepatcher", ACTIVATE_DESC, false));
    code.add(new InsnNode(Opcodes.ARETURN));
    return code;
  }

  private static InsnList directNoArgLong(String name) {
    InsnList code = new InsnList();
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE, name, "()J", false));
    code.add(new InsnNode(Opcodes.LRETURN));
    return code;
  }

  private static InsnList directObjectLong(String name) {
    InsnList code = new InsnList();
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE, name, "(Ljava/lang/Object;)J", false));
    code.add(new InsnNode(Opcodes.LRETURN));
    return code;
  }

  private static InsnList directObjectFloat(String name) {
    InsnList code = new InsnList();
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE, name, "(Ljava/lang/Object;)F", false));
    code.add(new InsnNode(Opcodes.FRETURN));
    return code;
  }

  private static InsnList directBeforeMutation() {
    InsnList code = new InsnList();
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new VarInsnNode(Opcodes.ILOAD, 1));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE, "beforeAoTDMarketMutation",
        "(Ljava/lang/Object;I)J", false));
    code.add(new InsnNode(Opcodes.LRETURN));
    return code;
  }

  private static InsnList directAfterMutation() {
    InsnList code = new InsnList();
    code.add(new VarInsnNode(Opcodes.LLOAD, 0));
    code.add(new VarInsnNode(Opcodes.ALOAD, 2));
    code.add(new VarInsnNode(Opcodes.ILOAD, 3));
    code.add(new VarInsnNode(Opcodes.LLOAD, 4));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE, "afterAoTDMarketMutation",
        "(JLjava/lang/Object;IJ)V", false));
    // Preserve the fork-local dirty queue update after the runtime committed
    // structural generation. This is a direct same-loader call, not reflection.
    code.add(new VarInsnNode(Opcodes.ALOAD, 2));
    code.add(new VarInsnNode(Opcodes.ILOAD, 3));
    code.add(new VarInsnNode(Opcodes.LLOAD, 4));
    code.add(new VarInsnNode(Opcodes.ALOAD, 2));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE, "getAoTDMarketStructuralGeneration",
        "(Ljava/lang/Object;)J", false));
    code.add(
        new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, "acceptMarketMutation", "(Ljava/lang/Object;IJJ)V", false));
    code.add(new InsnNode(Opcodes.RETURN));
    return code;
  }


  private static InsnList directPublishRuntimeEpoch() {
    InsnList code = new InsnList();
    code.add(new VarInsnNode(Opcodes.LLOAD, 0));
    code.add(new VarInsnNode(Opcodes.LLOAD, 2));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE, "publishAoTDRuntimeEpoch", "(JJ)V", false));
    code.add(new InsnNode(Opcodes.RETURN));
    return code;
  }

  private static InsnList directBeforeGlobalBoundary() {
    InsnList code = new InsnList();
    code.add(new VarInsnNode(Opcodes.ILOAD, 0));
    code.add(new VarInsnNode(Opcodes.ILOAD, 1));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE, "beforeAoTDGlobalBoundary", "(IZ)J", false));
    code.add(new InsnNode(Opcodes.LRETURN));
    return code;
  }

  private static InsnList directAfterGlobalBoundary() {
    InsnList code = new InsnList();
    code.add(new VarInsnNode(Opcodes.LLOAD, 0));
    code.add(new VarInsnNode(Opcodes.LLOAD, 2));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE, "afterAoTDGlobalBoundary", "(JJ)V", false));
    code.add(new InsnNode(Opcodes.RETURN));
    return code;
  }

  private static void rewrite(MethodNode method, InsnList body, int maxStack, int maxLocals) {
    if ((method.access & Opcodes.ACC_STATIC) == 0)
      throw new IllegalStateException("Bridge ABI method is not static: " + method.name);
    method.instructions = body;
    method.tryCatchBlocks.clear();
    if (method.localVariables != null) method.localVariables.clear();
    method.visibleLocalVariableAnnotations = null;
    method.invisibleLocalVariableAnnotations = null;
    method.maxStack = maxStack;
    method.maxLocals = maxLocals;
  }

  private static MethodNode find(ClassNode node, String name, String desc) {
    for (MethodNode method : node.methods) if (name.equals(method.name) && desc.equals(method.desc)) return method;
    return null;
  }

  private static MethodNode require(ClassNode node, String name, String desc) {
    MethodNode method = find(node, name, desc);
    if (method != null) return method;
    throw new IllegalStateException("Missing bridge ABI method " + name + desc);
  }

  private static MethodNode requireStatic(ClassNode node, String name, String desc) {
    return requireStatic(require(node, name, desc));
  }

  private static MethodNode requireStatic(MethodNode method) {
    if ((method.access & Opcodes.ACC_STATIC) == 0) {
      throw new IllegalStateException("Bridge ABI method is not static: " + method.name);
    }
    return method;
  }

  private static Object fieldValue(ClassNode node, String name) {
    for (FieldNode field : node.fields) if (name.equals(field.name)) return field.value;
    return null;
  }
}
