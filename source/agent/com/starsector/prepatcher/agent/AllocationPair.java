package com.starsector.prepatcher.agent;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

record AllocationPair(TypeInsnNode allocation, AbstractInsnNode duplicate) {
}
