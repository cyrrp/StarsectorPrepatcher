package com.starsector.prepatcher.agent.economy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;


import static com.starsector.prepatcher.agent.bytecode.Allocations.*;
import static com.starsector.prepatcher.agent.bytecode.ClassIO.*;
import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.economy.EconomyGroupIndex.*;
import static com.starsector.prepatcher.agent.bytecode.Flow.*;
import static com.starsector.prepatcher.agent.bytecode.Markers.*;
import static com.starsector.prepatcher.agent.economy.MarketAdvance.*;
import static com.starsector.prepatcher.agent.economy.MarketScheduler.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.bytecode.OwnedTemplates.*;
import static com.starsector.prepatcher.agent.economy.PersistentState.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.PatchAction;


public final class DirectMarketObservationEntry implements PatchAction {
  @Override
  public PatchReport apply(ClassNode node) {
    MethodNode method = marketAdvanceSemanticBody(node);
    String descriptor = "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;F)V";
    int existing = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "observeMarketAdvanceEntry", descriptor);
    if (existing == 1) {
      AbstractInsnNode expected = firstMeaningful(method);
      if (expected instanceof MethodInsnNode scratch
          && callMatches(scratch, Opcodes.INVOKESTATIC, HOOKS, "beginScratchScope", "()V")) {
        expected = nextMeaningful(expected);
      }
      if (!(expected instanceof VarInsnNode owner) || owner.getOpcode() != Opcodes.ALOAD || owner.var != 0
          || !(nextMeaningful(expected) instanceof VarInsnNode amount) || amount.getOpcode() != Opcodes.FLOAD
          || amount.var != 1 || !(nextMeaningful(amount) instanceof MethodInsnNode hook)
          || !callMatches(hook, Opcodes.INVOKESTATIC, HOOKS, "observeMarketAdvanceEntry", descriptor)) {
        throw mismatch("Market.advance observation entry hook is not at the verified entry point");
      }
      throw already("Market.advance observation entry hook is present");
    }
    requireCount("Market.advance observation entry hook", existing, 0);

    InsnList prologue = new InsnList();
    prologue.add(new VarInsnNode(Opcodes.ALOAD, 0));
    prologue.add(new VarInsnNode(Opcodes.FLOAD, 1));
    prologue.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "observeMarketAdvanceEntry", descriptor, false));
    AbstractInsnNode entry = firstMeaningful(method);
    if (entry instanceof MethodInsnNode scratch
        && callMatches(scratch, Opcodes.INVOKESTATIC, HOOKS, "beginScratchScope", "()V")) {
      method.instructions.insert(entry, prologue);
    } else {
      method.instructions.insertBefore(entry, prologue);
    }

    PatchReport report = new PatchReport();
    report.add("marketAdvanceEntry", 1);
    return report;
  }
}
