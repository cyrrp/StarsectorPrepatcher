package com.starsector.prepatcher.agent.strategicjump;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

record StrategicJumpCommon(MethodNode method, MethodInsnNode destinations, AbstractInsnNode acceptedEntry,
    AbstractInsnNode candidateStart, int jumpPointLocal, int fleetLocal, int unsafeLocal) {
}
