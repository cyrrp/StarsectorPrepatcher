package com.starsector.prepatcher.agent.strategicjump;

import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

record StrategicJumpLazy(VarInsnNode sentinelStore, JumpInsnNode guard, VarInsnNode scoreStore) {
}
