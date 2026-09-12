package com.starsector.prepatcher.agent;

import org.objectweb.asm.tree.AbstractInsnNode;

record StrategicJumpOriginal(AbstractInsnNode start, AbstractInsnNode end) {
}
