package com.starsector.prepatcher.agent.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;

public final class Markers {
  public static final String PATCH_MARKER_PREFIX = "smo$patched$";

  static final String PATCH_MARKER_DESC = "Ljava/lang/String;";

  // This is a patch-schema identifier, not the release version. Keeping it stable prevents a
  // routine SemVer bump from changing idempotency/ownership semantics.
  public static final String PATCH_MARKER_VALUE_PREFIX = "StarsectorPrepatcher:patch-v1:";

  public static boolean hasPatchMarker(ClassNode node, String patchId) {
    String name = PATCH_MARKER_PREFIX + patchId;
    List<FieldNode> matches = new ArrayList<>();
    for (FieldNode field : node.fields) {
      if (field.name.equals(name)) matches.add(field);
    }
    if (matches.isEmpty()) return false;
    if (matches.size() != 1) throw mismatch("duplicate optimizer patch marker " + name);
    FieldNode marker = matches.get(0);
    int expectedAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
    if (!marker.desc.equals(PATCH_MARKER_DESC) || marker.access != expectedAccess
        || !PATCH_MARKER_VALUE_PREFIX.concat(patchId).equals(marker.value)) {
      throw mismatch("foreign or malformed optimizer patch marker " + name);
    }
    return true;
  }

  public static void addPatchMarker(ClassNode node, String patchId) {
    if (hasPatchMarker(node, patchId)) throw mismatch("patch marker already exists");
    node.fields.add(new FieldNode(Opcodes.ASM8,
        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
        PATCH_MARKER_PREFIX + patchId, PATCH_MARKER_DESC, null, PATCH_MARKER_VALUE_PREFIX + patchId));
  }

}
