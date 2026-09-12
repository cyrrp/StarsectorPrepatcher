package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.Queries.*;

final class SaveOutputBufferDedup implements StructuredPatch<SaveOutputBufferDedup.Plan> {
  record Plan(MethodNode method, BufferConstruction construction) {
  }

  private record BufferConstruction(AbstractInsnNode start, AbstractInsnNode end) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode method =
        requireMethod(node, "o00000", "(Lcom/fs/starfarer/campaign/CampaignEngine$o;JZ)Ljava/lang/String;");
    List<BufferConstruction> plans = new ArrayList<>();
    for (MethodInsnNode call : calls(method, Opcodes.INVOKESPECIAL, "java/io/BufferedOutputStream", "<init>",
        "(Ljava/io/OutputStream;I)V")) {
      AbstractInsnNode size = previousMeaningful(call);
      AbstractInsnNode stream = previousMeaningful(size);
      AbstractInsnNode duplicate = previousMeaningful(stream);
      AbstractInsnNode allocation = previousMeaningful(duplicate);
      AbstractInsnNode store = nextMeaningful(call);
      if (!(size instanceof LdcInsnNode ldc) || !Integer.valueOf(1_048_576).equals(ldc.cst)) continue;
      if (!(stream instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) continue;
      if (duplicate == null || duplicate.getOpcode() != Opcodes.DUP) continue;
      if (!(allocation instanceof TypeInsnNode type) || type.getOpcode() != Opcodes.NEW
          || !type.desc.equals("java/io/BufferedOutputStream"))
        continue;
      if (!(store instanceof VarInsnNode target) || target.getOpcode() != Opcodes.ASTORE || target.var != load.var)
        continue;
      requireNoControlBoundary(allocation, store, "save output buffer construction");
      plans.add(new BufferConstruction(allocation, store));
    }
    if (plans.isEmpty()) return Match.alreadyApplied("outer save buffer is already absent");
    requireCount("redundant outer save buffers", plans.size(), 1);
    return Match.plan(new Plan(method, plans.get(0)));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    removeRange(plan.method(), plan.construction().start(), plan.construction().end());
    PatchReport report = new PatchReport();
    report.add("outer1MiBBufferRemoved", 1);
    return report;
  }
}
