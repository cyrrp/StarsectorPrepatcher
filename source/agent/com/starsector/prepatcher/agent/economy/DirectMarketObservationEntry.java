package com.starsector.prepatcher.agent.economy;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.starsector.prepatcher.agent.bytecode.Contract;
import com.starsector.prepatcher.agent.bytecode.Queries;
import com.starsector.prepatcher.agent.bytecode.Flow;
import com.starsector.prepatcher.agent.bytecode.Mutations;
import com.starsector.prepatcher.agent.bytecode.Allocations;
import com.starsector.prepatcher.agent.bytecode.ClassIO;
import com.starsector.prepatcher.agent.bytecode.Markers;
import com.starsector.prepatcher.agent.bytecode.Names;
import com.starsector.prepatcher.agent.bytecode.OwnedTemplates;


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
