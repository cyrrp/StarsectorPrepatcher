package com.starsector.prepatcher.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Markers.*;

/**
 * Gives HyperspaceAutomaton a private spare-buffer slot so advance() reuses the retired cells
 * array instead of allocating once the public getCells() escape state permits it. The patch is
 * all-or-nothing: any partial instrumentation field, missing cells/next field, missing
 * getCells/advance method, or a swap/allocation/next-store shape that is not exactly the vanilla
 * sequence refuses the whole plan before anything changes.
 */
final class AutomatonBufferReuse implements StructuredPatch<AutomatonBufferReuse.Plan> {

  /** The proven vanilla shape: fields list, getter return, and the ordered advance() sites. */
  record Plan(String owner, List<FieldNode> classFields, MethodNode getter, InsnNode getterReturn, MethodNode advance,
      FieldInsnNode swap, FieldInsnNode nextStore, MultiANewArrayInsnNode allocation) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    if (hasPatchMarker(node, "automatonBufferReuse")) {
      requireCount("automatonBuffers postcondition sites", countPatched(node), 1);
      return Match.alreadyApplied("structural marker and complete postcondition are present");
    }
    Plan plan = scan(node);
    requireCount("automatonBuffers structural sites", plan == null ? 0 : 1, 1);
    return Match.plan(plan);
  }

  @Override
  public PatchReport mutate(Plan plan) {
    plan.classFields().add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
        HyperspacePatches.AUTO_SPARE, "[[I", null, null));
    plan.classFields().add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
        HyperspacePatches.AUTO_EXPOSED, "Z", null, null));
    plan.classFields()
        .add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            HyperspacePatches.AUTO_MARKER, "Z", null, 1));

    InsnList escaped = new InsnList();
    escaped.add(new VarInsnNode(Opcodes.ALOAD, 0));
    escaped.add(new InsnNode(Opcodes.ICONST_1));
    escaped.add(new FieldInsnNode(Opcodes.PUTFIELD, plan.owner(), HyperspacePatches.AUTO_EXPOSED, "Z"));
    plan.getter().instructions.insertBefore(plan.getterReturn(), escaped);

    InsnList stash = new InsnList();
    stash.add(new VarInsnNode(Opcodes.ALOAD, 0));
    stash.add(new VarInsnNode(Opcodes.ALOAD, 0));
    stash.add(new FieldInsnNode(Opcodes.GETFIELD, plan.owner(), "cells", "[[I"));
    stash.add(new VarInsnNode(Opcodes.ALOAD, 0));
    stash.add(new FieldInsnNode(Opcodes.GETFIELD, plan.owner(), HyperspacePatches.AUTO_EXPOSED, "Z"));
    stash.add(new VarInsnNode(Opcodes.ALOAD, 0));
    stash.add(new LdcInsnNode(plan.owner().replace('/', '.')));
    stash.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HyperspacePatches.HOOK, "retainAutomatonSpare",
        "([[IZLjava/lang/Object;Ljava/lang/String;)[[I", false));
    stash.add(new FieldInsnNode(Opcodes.PUTFIELD, plan.owner(), HyperspacePatches.AUTO_SPARE, "[[I"));
    plan.advance().instructions.insertBefore(plan.swap(), stash);
    InsnList unexposed = new InsnList();
    unexposed.add(new VarInsnNode(Opcodes.ALOAD, 0));
    unexposed.add(new InsnNode(Opcodes.ICONST_0));
    unexposed.add(new FieldInsnNode(Opcodes.PUTFIELD, plan.owner(), HyperspacePatches.AUTO_EXPOSED, "Z"));
    plan.advance().instructions.insert(plan.swap(), unexposed);

    InsnList spareArg = new InsnList();
    spareArg.add(new VarInsnNode(Opcodes.ALOAD, 0));
    spareArg.add(new FieldInsnNode(Opcodes.GETFIELD, plan.owner(), HyperspacePatches.AUTO_SPARE, "[[I"));
    plan.advance().instructions.insertBefore(plan.allocation(), spareArg);
    plan.advance().instructions.set(plan.allocation(), new MethodInsnNode(Opcodes.INVOKESTATIC, HyperspacePatches.HOOK,
        "acquireAutomatonBuffer", "(II[[I)[[I", false));
    InsnList clearSpare = new InsnList();
    clearSpare.add(new VarInsnNode(Opcodes.ALOAD, 0));
    clearSpare.add(new InsnNode(Opcodes.ACONST_NULL));
    clearSpare.add(new FieldInsnNode(Opcodes.PUTFIELD, plan.owner(), HyperspacePatches.AUTO_SPARE, "[[I"));
    plan.advance().instructions.insert(plan.nextStore(), clearSpare);

    PatchReport report = new PatchReport();
    report.add("automatonBuffers", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("automatonBuffers postcondition sites", countPatched(node), 1);
  }

  private static Plan scan(ClassNode node) {
    for (FieldNode field : node.fields)
      if (field.name.equals(HyperspacePatches.AUTO_SPARE) || field.name.equals(HyperspacePatches.AUTO_EXPOSED)
          || field.name.equals(HyperspacePatches.AUTO_MARKER))
        return null;
    boolean cells = false, next = false;
    for (FieldNode field : node.fields) {
      if (field.name.equals("cells") && field.desc.equals("[[I")) cells = true;
      if (field.name.equals("next") && field.desc.equals("[[I")) next = true;
    }
    if (!cells || !next) return null;

    MethodNode getter = null, advance = null;
    for (MethodNode method : node.methods) {
      if (method.name.equals("getCells") && method.desc.equals("()[[I")) getter = method;
      else if (method.name.equals("advance") && method.desc.equals("(F)V")) advance = method;
    }
    if (getter == null || advance == null) return null;

    List<InsnNode> returns = new ArrayList<>();
    for (AbstractInsnNode n = getter.instructions.getFirst(); n != null; n = n.getNext())
      if (n.getOpcode() == Opcodes.ARETURN) returns.add((InsnNode) n);
    List<FieldInsnNode> swaps = new ArrayList<>(), nextStores = new ArrayList<>();
    List<MultiANewArrayInsnNode> allocations = new ArrayList<>();
    for (AbstractInsnNode n = advance.instructions.getFirst(); n != null; n = n.getNext()) {
      if (n instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && field.owner.equals(node.name)) {
        if (field.name.equals("cells") && field.desc.equals("[[I")) swaps.add(field);
        else if (field.name.equals("next") && field.desc.equals("[[I")) nextStores.add(field);
      } else if (n instanceof MultiANewArrayInsnNode alloc && alloc.desc.equals("[[I") && alloc.dims == 2)
        allocations.add(alloc);
    }
    if (returns.size() != 1 || swaps.size() != 1 || nextStores.size() != 1 || allocations.size() != 1) return null;
    FieldInsnNode swap = swaps.get(0), nextStore = nextStores.get(0);
    MultiANewArrayInsnNode allocation = allocations.get(0);
    if (!isBefore(swap, allocation) || !isBefore(allocation, nextStore)) return null;
    return new Plan(node.name, node.fields, getter, returns.get(0), advance, swap, nextStore, allocation);
  }

  private static int countPatched(ClassNode node) {
    int spare = 0, exposed = 0, marker = 0;
    for (FieldNode field : node.fields) {
      if (field.name.equals(HyperspacePatches.AUTO_SPARE) && field.desc.equals("[[I")) spare++;
      else if (field.name.equals(HyperspacePatches.AUTO_EXPOSED) && field.desc.equals("Z")) exposed++;
      else if (field.name.equals(HyperspacePatches.AUTO_MARKER) && field.desc.equals("Z")
          && Integer.valueOf(1).equals(field.value))
        marker++;
    }
    return spare == 1 && exposed == 1 && marker == 1
        && HyperspacePatches.countStaticCalls(node, "retainAutomatonSpare") == 1
        && HyperspacePatches.countStaticCalls(node, "acquireAutomatonBuffer") == 1 ? 1 : 0;
  }

  private static boolean isBefore(AbstractInsnNode first, AbstractInsnNode second) {
    for (AbstractInsnNode n = first; n != null; n = n.getNext()) if (n == second) return true;
    return false;
  }
}
