package com.starsector.prepatcher.agent;

import java.util.LinkedHashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/** Rejects new symbolic optional-mod dependencies in core-game transformation results. */
final class OptionalModLinkageAudit {
  private OptionalModLinkageAudit() {}

  static void verify(byte[] original, byte[] transformed) {
    Set<String> added = references(transformed);
    added.removeAll(references(original));
    added.removeIf(name -> !isOptionalMod(name));
    if (!added.isEmpty()) {
      throw new AssertionError(
          "New optional-mod linkage in " + new ClassReader(original).getClassName() + ": " + added);
    }
  }

  private static boolean isOptionalMod(String name) {
    return name.startsWith("data/") || name.startsWith("exerelin/") || name.startsWith("ashlib/")
        || name.startsWith("org/lazywizard/") || name.startsWith("org/magiclib/") || name.startsWith("com/genir/");
  }

  private static Set<String> references(byte[] bytes) {
    Set<String> result = new LinkedHashSet<>();
    new ClassReader(bytes).accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
      @Override
      public String map(String name) {
        result.add(name);
        return name;
      }
    }), 0);
    return result;
  }
}
