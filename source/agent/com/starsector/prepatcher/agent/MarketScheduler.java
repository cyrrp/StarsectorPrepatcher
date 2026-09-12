package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.Queries.*;

final class MarketScheduler {
  static final int MARKET_SCHEDULER_COMPONENT_ENGINE = 1;
  static final int MARKET_SCHEDULER_COMPONENT_ECONOMY = 2;
  static final int MARKET_SCHEDULER_COMPONENT_ENTITY = 4;
  static final int MARKET_SCHEDULER_COMPONENT_SAVE = 8;
  static final int MARKET_SCHEDULER_COMPONENT_BATCH_PROTOCOL = 16;
  static final int MARKET_SCHEDULER_COMPONENT_MARKET_SEMANTICS = 32;
  static final int MARKET_SCHEDULER_COMPONENT_MILITARY_BASE = 64;
  static final int MARKET_SCHEDULER_COMPONENT_LIONS_GUARD = 128;
  static final int MARKET_SCHEDULER_COMPONENT_RECENT_UNREST = 256;
  static final int MARKET_SCHEDULER_COMPONENT_BASE_INDUSTRY = 512;
  static final int MARKET_SCHEDULER_COMPONENT_CONSTRUCTION_QUEUE = 1024;

  static PatchState marketSchedulerRegistrationState(ClassNode node, int component, String label) {
    List<MethodNode> initializers = methods(node, "<clinit>", "()V");
    if (initializers.size() > 1) { throw mismatch(label + " has duplicate class initializers"); }
    if (initializers.isEmpty()) return PatchState.ORIGINAL;
    MethodNode initializer = initializers.get(0);
    int returns = 0;
    int matching = 0;
    int totalHooks = countCalls(initializer, Opcodes.INVOKESTATIC, HOOKS, "registerMarketSchedulerComponent", "(I)V");
    for (AbstractInsnNode insn : initializer.instructions.toArray()) {
      if (insn.getOpcode() != Opcodes.RETURN) continue;
      returns++;
      AbstractInsnNode callInsn = previousMeaningful(insn);
      if (!(callInsn instanceof MethodInsnNode call)
          || !callMatches(call, Opcodes.INVOKESTATIC, HOOKS, "registerMarketSchedulerComponent", "(I)V"))
        continue;
      AbstractInsnNode source = previousMeaningful(call);
      if (!isIntegerLdc(source, component)) { throw mismatch(label + " registration component changed"); }
      matching++;
    }
    if (totalHooks == 0) return PatchState.ORIGINAL;
    if (returns > 0 && totalHooks == returns && matching == returns) { return PatchState.PATCHED; }
    throw mismatch(label + " registration is partial or not on every normal class-init exit");
  }

  static void installMarketSchedulerRegistration(ClassNode node, int component, String label) {
    if (marketSchedulerRegistrationState(node, component, label) != PatchState.ORIGINAL) {
      throw mismatch(label + " registration is not original");
    }
    List<MethodNode> initializers = methods(node, "<clinit>", "()V");
    MethodNode initializer;
    if (initializers.isEmpty()) {
      initializer = new MethodNode(Opcodes.ASM8, Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
      initializer.instructions.add(new InsnNode(Opcodes.RETURN));
      node.methods.add(initializer);
    } else {
      initializer = initializers.get(0);
    }
    List<AbstractInsnNode> returns = new ArrayList<>();
    for (AbstractInsnNode insn : initializer.instructions.toArray()) {
      if (insn.getOpcode() == Opcodes.RETURN) returns.add(insn);
    }
    if (returns.isEmpty()) throw mismatch(label + " class initializer has no normal return");
    for (AbstractInsnNode exit : returns) {
      InsnList registration = new InsnList();
      registration.add(pushInt(component));
      registration
          .add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "registerMarketSchedulerComponent", "(I)V", false));
      initializer.instructions.insertBefore(exit, registration);
    }
  }

}
