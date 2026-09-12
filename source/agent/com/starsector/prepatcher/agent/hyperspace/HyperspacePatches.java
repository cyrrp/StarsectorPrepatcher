
package com.starsector.prepatcher.agent.hyperspace;

import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Shared constants and hook-call counting for the hyperspace/terrain target classes. */
public final class HyperspacePatches {
  public static final String HOOK = "com/fs/starfarer/api/StarsectorPrepatcherHyperspaceHooks";
  public static final String AUTO_SPARE = "smo$automatonSpare";
  public static final String AUTO_EXPOSED = "smo$automatonCellsExposed";
  public static final String AUTO_MARKER = "smo$automatonBufferInstrumented";
  public static final String BASE_TILED = "com/fs/starfarer/api/impl/campaign/terrain/BaseTiledTerrain";
  public static final String HYPER_TERRAIN = "com/fs/starfarer/api/impl/campaign/terrain/HyperspaceTerrainPlugin";
  public static final String AUTOMATON = "com/fs/starfarer/api/impl/campaign/terrain/HyperspaceAutomaton";
  public static final String STARFIELD = "com/fs/starfarer/combat/OOOo";
  public static final List<String> TARGET_CLASSES = List.of(BASE_TILED, HYPER_TERRAIN, AUTOMATON, STARFIELD);

  private HyperspacePatches() {}

  static int countStaticCalls(ClassNode cn, String name) {
    int total = 0;
    for (MethodNode m : cn.methods) for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext())
      if (n instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(HOOK)
          && call.name.equals(name))
        total++;
    return total;
  }
}
