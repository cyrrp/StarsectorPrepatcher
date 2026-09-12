package com.starsector.prepatcher.agent.bytecode;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

public record AllocationPair(TypeInsnNode allocation, AbstractInsnNode duplicate) {
}
