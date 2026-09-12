package com.starsector.prepatcher.agent.campaign;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;


import static com.starsector.prepatcher.agent.bytecode.Contract.already;
import static com.starsector.prepatcher.agent.bytecode.Contract.mismatch;
import static com.starsector.prepatcher.agent.bytecode.Contract.requireCall;
import static com.starsector.prepatcher.agent.bytecode.Contract.requireCount;
import static com.starsector.prepatcher.agent.bytecode.Contract.requireMethod;
import static com.starsector.prepatcher.agent.bytecode.Names.HOOKS;
import static com.starsector.prepatcher.agent.bytecode.Queries.asMethod;
import static com.starsector.prepatcher.agent.bytecode.Queries.countCalls;
import static com.starsector.prepatcher.agent.bytecode.Queries.firstMeaningful;
import static com.starsector.prepatcher.agent.bytecode.Queries.nextMeaningful;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.PatchAction;


public final class CacheMaintenanceBeforeSave implements PatchAction {
  @Override
  public PatchReport apply(ClassNode node) {
    MethodNode method =
        requireMethod(node, "o00000", "(Lcom/fs/starfarer/campaign/CampaignEngine$o;JZ)Ljava/lang/String;");
    int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "runCacheMaintenance", "(Z)V");
    if (hooks == 1) {
      AbstractInsnNode first = firstMeaningful(method);
      if (first == null || first.getOpcode() != Opcodes.ICONST_1) {
        throw mismatch("forced cache maintenance true argument is not first");
      }
      MethodInsnNode call = asMethod(nextMeaningful(first), "forced cache maintenance hook");
      requireCall(call, Opcodes.INVOKESTATIC, HOOKS, "runCacheMaintenance", "(Z)V", "forced cache maintenance hook");
      throw already("cache maintenance pre-save barrier postcondition matches");
    }
    requireCount("forced cache maintenance hooks", hooks, 0);
    InsnList prefix = new InsnList();
    prefix.add(new InsnNode(Opcodes.ICONST_1));
    prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "runCacheMaintenance", "(Z)V", false));
    method.instructions.insert(prefix);
    PatchReport report = new PatchReport();
    report.add("idle cache and scratch maintenance before save", 1);
    return report;
  }
}
