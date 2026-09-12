package com.starsector.prepatcher.agent.startup;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

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

final class StartupInfoBlocks {
  private StartupInfoBlocks() {}

  record LogBlock(MethodNode method, AbstractInsnNode start, AbstractInsnNode end) {
  }

  static List<LogBlock> startupInfoBlockPlans(ClassNode node, String literal) {
    List<LogBlock> plans = new ArrayList<>();
    for (MethodNode method : node.methods) {
      for (AbstractInsnNode insn : method.instructions.toArray()) {
        if (!isStringLdc(insn, literal)) continue;
        MethodInsnNode info = null;
        int meaningful = 0;
        for (AbstractInsnNode cursor = insn; cursor != null && meaningful <= 40; cursor = cursor.getNext()) {
          if (cursor.getOpcode() >= 0) meaningful++;
          if (cursor instanceof MethodInsnNode call
              && callMatches(call, Opcodes.INVOKEVIRTUAL, "org/apache/log4j/Logger", "info", "(Ljava/lang/Object;)V")) {
            info = call;
            break;
          }
        }
        if (info == null) throw mismatch("no Logger.info after startup literal " + literal);
        FieldInsnNode logger = null;
        meaningful = 0;
        for (AbstractInsnNode cursor = insn; cursor != null && meaningful <= 40; cursor = cursor.getPrevious()) {
          if (cursor.getOpcode() >= 0) meaningful++;
          if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
              && field.desc.equals("Lorg/apache/log4j/Logger;")) {
            logger = field;
            break;
          }
        }
        if (logger == null) throw mismatch("no logger field before startup literal " + literal);
        requireNoControlBoundary(logger, info, "startup INFO block " + literal);
        requirePureStartupInfoBlock(logger, info, literal);
        plans.add(new LogBlock(method, logger, info));
      }
    }
    return plans;
  }


  /**
   * Whole-block INFO suppression is permitted only for compiler-generated message
   * construction. This makes the patch fail open if another javaagent inserts a
   * getter, callback, probe, or other potentially observable call into the block.
   */
  static void requirePureStartupInfoBlock(AbstractInsnNode start, AbstractInsnNode end, String literal) {
    int infoCalls = 0;
    for (AbstractInsnNode cursor = start; cursor != null; cursor = cursor.getNext()) {
      if (cursor instanceof MethodInsnNode call) {
        boolean allowed;
        if (callMatches(call, Opcodes.INVOKEVIRTUAL, "org/apache/log4j/Logger", "info", "(Ljava/lang/Object;)V")) {
          infoCalls++;
          allowed = true;
        } else if (call.owner.equals("java/lang/StringBuilder")) {
          allowed = (call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>"))
              || (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                  && (call.name.equals("append") || call.name.equals("toString")));
        } else if (callMatches(call, Opcodes.INVOKESTATIC, "java/lang/String", "format",
            "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;")) {
              allowed = true;
            } else
          if (callMatches(call, Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
              "(Ljava/lang/Object;)Ljava/lang/String;")) {
                allowed = true;
              } else {
                allowed = false;
              }
        if (!allowed) {
          throw mismatch("startup INFO block " + literal + " contains an observable call " + call.owner + "."
              + call.name + call.desc);
        }
      } else if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD) {
        throw mismatch("startup INFO block " + literal + " writes field " + field.owner + "." + field.name);
      } else if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC) {
        throw mismatch("startup INFO block " + literal + " writes static field " + field.owner + "." + field.name);
      } else if (cursor.getOpcode() == Opcodes.INVOKEDYNAMIC || cursor.getOpcode() == Opcodes.MONITORENTER
          || cursor.getOpcode() == Opcodes.MONITOREXIT) {
            throw mismatch("startup INFO block " + literal + " contains unsupported opcode " + cursor.getOpcode());
          }
      if (cursor == end) break;
    }
    requireCount("Logger.info calls in startup block " + literal, infoCalls, 1);
  }


  static void suppressStartupInfoBlocks(List<LogBlock> plans, int category, boolean emitRuntimeCounter) {
    for (LogBlock plan : plans) {
      if (emitRuntimeCounter) {
        InsnList replacement = new InsnList();
        replacement.add(new LdcInsnNode(category));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "startupLogSuppressed", "(I)V", false));
        plan.method.instructions.insertBefore(plan.start, replacement);
      }
      removeRange(plan.method, plan.start, plan.end);
    }
  }
}
