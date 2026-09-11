package com.starsector.prepatcher.agent;

import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

record SnapshotHookPlan(MethodInsnNode hook, VarInsnNode store) {
}
