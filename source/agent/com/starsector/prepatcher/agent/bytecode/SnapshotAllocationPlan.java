package com.starsector.prepatcher.agent.bytecode;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public record SnapshotAllocationPlan(TypeInsnNode allocation, AbstractInsnNode duplicate, MethodInsnNode constructor,
    VarInsnNode store) {
}
