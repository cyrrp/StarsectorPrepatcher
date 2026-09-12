package com.starsector.prepatcher.agent;

import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

record StrategicJumpLazy(VarInsnNode sentinelStore, JumpInsnNode guard, VarInsnNode scoreStore) {
}
