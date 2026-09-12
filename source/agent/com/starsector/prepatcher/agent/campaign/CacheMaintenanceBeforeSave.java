package com.starsector.prepatcher.agent.campaign;

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
import com.starsector.prepatcher.agent.economy.MarketAdvance;
import com.starsector.prepatcher.agent.economy.MarketScheduler;
import com.starsector.prepatcher.agent.economy.EconomyGroupIndex;
import com.starsector.prepatcher.agent.economy.PersistentState;


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
