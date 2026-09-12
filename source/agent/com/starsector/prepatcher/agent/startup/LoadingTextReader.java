package com.starsector.prepatcher.agent.startup;

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
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.StructuredPatch;


public final class LoadingTextReader implements StructuredPatch<LoadingTextReader.Plan> {
  record Plan(MethodNode method) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode method = requireMethod(node, "super", "(Ljava/io/InputStream;)Ljava/lang/String;");
    int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "readUtf8Normalized",
        "(Ljava/io/InputStream;)Ljava/lang/String;");
    int buffers = 0;
    int oneMiB = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof IntInsnNode array && array.getOpcode() == Opcodes.NEWARRAY && array.operand == Opcodes.T_BYTE)
        buffers++;
      if (insn instanceof LdcInsnNode ldc && Integer.valueOf(1_048_576).equals(ldc.cst)) oneMiB++;
    }
    int stringBuffers = countCalls(method, Opcodes.INVOKESPECIAL, "java/lang/StringBuffer", "<init>", "()V");
    int replaceAll = countCalls(method, Opcodes.INVOKEVIRTUAL, "java/lang/String", "replaceAll",
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (hooks == 1 && buffers == 0 && oneMiB == 0 && stringBuffers == 0 && replaceAll == 0) {
      return Match.alreadyApplied("streaming UTF-8 text reader postcondition matches");
    }
    requireCount("text reader hook", hooks, 0);
    requireCount("text reader byte-array allocation", buffers, 1);
    requireCount("text reader 1 MiB constant", oneMiB, 1);
    requireCount("text reader StringBuffer", stringBuffers, 1);
    requireCount("text reader CR regex", replaceAll, 1);
    requireOriginalLoadingTextReaderShape(method);
    return Match.plan(new Plan(method));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode method = plan.method();
    method.instructions.clear();
    method.tryCatchBlocks.clear();
    if (method.localVariables != null) method.localVariables.clear();
    if (method.visibleLocalVariableAnnotations != null) method.visibleLocalVariableAnnotations.clear();
    if (method.invisibleLocalVariableAnnotations != null) method.invisibleLocalVariableAnnotations.clear();
    method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "readUtf8Normalized",
        "(Ljava/io/InputStream;)Ljava/lang/String;", false));
    method.instructions.add(new InsnNode(Opcodes.ARETURN));
    method.maxLocals = 1;
    method.maxStack = 1;

    PatchReport report = new PatchReport();
    report.add("streamingUtf8Reader", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("text reader hook", countCalls(plan.method(), Opcodes.INVOKESTATIC, HOOKS, "readUtf8Normalized",
        "(Ljava/io/InputStream;)Ljava/lang/String;"), 1);
  }

  private static void requireOriginalLoadingTextReaderShape(MethodNode method) {
    requireCount("text reader String(byte[],int,int,String) constructor",
        countCalls(method, Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([BIILjava/lang/String;)V"), 1);
    requireCount("text reader StringBuffer append", countCalls(method, Opcodes.INVOKEVIRTUAL, "java/lang/StringBuffer",
        "append", "(Ljava/lang/String;)Ljava/lang/StringBuffer;"), 1);
    requireCount("text reader InputStream.read",
        countCalls(method, Opcodes.INVOKEVIRTUAL, "java/io/InputStream", "read", "([B)I"), 1);
    requireCount("text reader InputStream.close",
        countCalls(method, Opcodes.INVOKEVIRTUAL, "java/io/InputStream", "close", "()V"), 3);
    requireCount("text reader StringBuffer.toString",
        countCalls(method, Opcodes.INVOKEVIRTUAL, "java/lang/StringBuffer", "toString", "()Ljava/lang/String;"), 1);
    requireCount("text reader total method calls", countMethodInstructions(method), 9);
    requireCount("text reader field accesses", countFieldInstructions(method), 0);
    requireCount("text reader invokedynamic sites", countOpcode(method, Opcodes.INVOKEDYNAMIC), 0);
    requireCount("text reader try/catch regions", method.tryCatchBlocks.size(), 2);
    requireCount("text reader UTF-8 literals", countStringConstants(method, "UTF-8"), 1);
    requireCount("text reader CR literals", countStringConstants(method, "\\r"), 1);
    requireCount("text reader empty literals", countStringConstants(method, ""), 1);

    int newString = 0;
    int newStringBuffer = 0;
    int otherNew = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof TypeInsnNode type) || type.getOpcode() != Opcodes.NEW) continue;
      if (type.desc.equals("java/lang/String")) newString++;
      else if (type.desc.equals("java/lang/StringBuffer")) newStringBuffer++;
      else otherNew++;
    }
    requireCount("text reader new String", newString, 1);
    requireCount("text reader new StringBuffer", newStringBuffer, 1);
    requireCount("text reader other object allocations", otherNew, 0);
  }

}

