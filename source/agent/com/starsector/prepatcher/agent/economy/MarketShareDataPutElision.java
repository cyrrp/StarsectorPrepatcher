package com.starsector.prepatcher.agent.economy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;

/**
 * Moves the redundant existing-entry LinkedHashMap.put in
 * CommodityMarketData.getMarketShareData inside the null branch, so a present
 * entry skips the write entirely.
 */
public final class MarketShareDataPutElision implements StructuredPatch<MarketShareDataPutElision.Plan> {
  private static final String MARKET_SHARE_DATA = "com/fs/starfarer/campaign/econ/reach/MarketShareData";

  record PutShape(MethodNode method, JumpInsnNode guard, AbstractInsnNode start, AbstractInsnNode end,
      boolean optimized) {
  }

  record Plan(PutShape shape) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    PutShape shape = requirePutShape(node);
    if (shape.optimized()) { return Match.alreadyApplied("market-share data put-elision postcondition matches"); }

    Set<AbstractInsnNode> moving = new LinkedHashSet<>();
    AbstractInsnNode cursor = shape.start();
    while (cursor != null) {
      if (cursor instanceof LabelNode || cursor instanceof FrameNode) {
        return Match.incompatible("market-share put sequence contains a control-flow boundary");
      }
      moving.add(cursor);
      if (cursor == shape.end()) break;
      cursor = cursor.getNext();
    }
    if (cursor == null) return Match.incompatible("market-share put sequence is not contiguous");
    for (AbstractInsnNode insn : shape.method().instructions.toArray()) {
      if (moving.contains(insn)) continue;
      if (insn instanceof JumpInsnNode jump && moving.contains(jump.label)) {
        return Match.incompatible("market-share put sequence has an external jump target");
      }
    }
    for (TryCatchBlockNode block : shape.method().tryCatchBlocks) {
      if (moving.contains(block.start) || moving.contains(block.end) || moving.contains(block.handler)) {
        return Match.incompatible("market-share put sequence crosses an exception-handler boundary");
      }
    }
    return Match.plan(new Plan(shape));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    PutShape shape = plan.shape();
    InsnList moved = new InsnList();
    AbstractInsnNode cursor = shape.start();
    while (cursor != null) {
      AbstractInsnNode next = cursor.getNext();
      shape.method().instructions.remove(cursor);
      moved.add(cursor);
      if (cursor == shape.end()) break;
      cursor = next;
    }
    shape.method().instructions.insertBefore(shape.guard().label, moved);

    PatchReport report = new PatchReport();
    report.add("existing-entry market-share put elision", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    PutShape post = requirePutShape(node);
    if (!post.optimized()) { throw mismatch("market-share put sequence did not move inside the null branch"); }
  }

  private static PutShape requirePutShape(ClassNode node) {
    String desc = "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)L" + MARKET_SHARE_DATA + ";";
    MethodNode method = requireMethod(node, "getMarketShareData", desc);
    if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch("CommodityMarketData.getMarketShareData has no concrete code");
    }
    MethodInsnNode get = only(calls(method, Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "get",
        "(Ljava/lang/Object;)Ljava/lang/Object;"), "market-share data LinkedHashMap.get");
    MethodInsnNode put = only(calls(method, Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "put",
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), "market-share data LinkedHashMap.put");
    requireCount("market-share data allocation", countTypeInstructions(method, Opcodes.NEW, MARKET_SHARE_DATA), 1);

    AbstractInsnNode cast = nextMeaningful(get);
    if (!(cast instanceof TypeInsnNode type) || cast.getOpcode() != Opcodes.CHECKCAST
        || !type.desc.equals(MARKET_SHARE_DATA)) {
      throw mismatch("market-share data get result cast changed");
    }
    AbstractInsnNode storeInsn = nextMeaningful(cast);
    if (!(storeInsn instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
      throw mismatch("market-share data local store changed");
    }
    int dataLocal = store.var;

    List<JumpInsnNode> guards = new ArrayList<>();
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFNONNULL) continue;
      AbstractInsnNode loadInsn = previousMeaningful(jump);
      if (loadInsn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == dataLocal) {
        guards.add(jump);
      }
    }
    JumpInsnNode guard = only(guards, "market-share data non-null guard");

    AbstractInsnNode end = nextMeaningful(put);
    if (end == null || end.getOpcode() != Opcodes.POP) {
      throw mismatch("market-share data put result discard changed");
    }
    AbstractInsnNode valueLoad = previousMeaningful(put);
    AbstractInsnNode keyLoad = previousMeaningful(valueLoad);
    AbstractInsnNode fieldRead = previousMeaningful(keyLoad);
    AbstractInsnNode thisLoad = previousMeaningful(fieldRead);
    if (!(valueLoad instanceof VarInsnNode value) || value.getOpcode() != Opcodes.ALOAD || value.var != dataLocal
        || !(keyLoad instanceof VarInsnNode key) || key.getOpcode() != Opcodes.ALOAD || key.var != 1
        || !(fieldRead instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
        || !field.owner.equals(node.name) || !field.desc.equals("Ljava/util/LinkedHashMap;")
        || !(thisLoad instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0) {
      throw mismatch("market-share data put operand sequence changed");
    }

    int targetIndex = method.instructions.indexOf(guard.label);
    int startIndex = method.instructions.indexOf(thisLoad);
    int endIndex = method.instructions.indexOf(end);
    if (targetIndex < 0 || startIndex < 0 || endIndex < startIndex) {
      throw mismatch("market-share data put indices are invalid");
    }
    boolean original = targetIndex < startIndex && nextMeaningful(guard.label) == thisLoad;
    boolean optimized = startIndex < targetIndex && previousMeaningful(guard.label) == end;
    if (!original && !optimized) { throw mismatch("market-share data put is neither vanilla nor optimized shape"); }
    AbstractInsnNode returnLoad = nextMeaningful(guard.label);
    if (optimized) {
      if (!(returnLoad instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != dataLocal
          || nextMeaningful(returnLoad) == null || nextMeaningful(returnLoad).getOpcode() != Opcodes.ARETURN) {
        throw mismatch("market-share data optimized return path changed");
      }
    }
    return new PutShape(method, guard, thisLoad, end, optimized);
  }
}
