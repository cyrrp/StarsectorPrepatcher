package com.starsector.prepatcher.agent.bytecode;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

public record AllocationPlan(TypeInsnNode allocation, AbstractInsnNode duplicate, MethodInsnNode constructor,
    int local) {
}
