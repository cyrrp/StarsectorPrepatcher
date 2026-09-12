package com.starsector.prepatcher.agent.bytecode;

import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public record SnapshotHookPlan(MethodInsnNode hook, VarInsnNode store) {
}
