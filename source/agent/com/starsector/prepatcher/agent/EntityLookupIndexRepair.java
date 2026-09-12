package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.Queries.*;

/**
 * CampaignEngine's id-to-entity map is transient. During XStream restore a mod
 * may call getEntityById() before the star-system graph has been attached. The
 * vanilla lookup then materializes a non-null but incomplete map; the first
 * campaign tick only rebuilds null maps, so the incomplete map survives and
 * every miss scans hyperspace plus all star systems.
 *
 * Add one transient per-engine readiness bit and rebuild the exact vanilla map
 * once, immediately after the first repository-listener restoration. The bit
 * is deliberately transient so every loaded CampaignEngine receives the same
 * repair, while steady-state ticks pay one private boolean read and branch.
 *
 * This member runs inside CampaignListenerPlan: it anchors after the listener
 * boundary proven on the same plan candidate, in either the raw or the
 * throttled call shape.
 */
final class EntityLookupIndexRepair implements StructuredPatch<EntityLookupIndexRepair.Plan> {
  private static final String ENTITY_LOOKUP_INDEX_REPAIR_FIELD = "spp$entityLookupIndexReady";

  record Plan(String owner, List<FieldNode> fields, EntityLookupIndexSurface surface) {
  }

  private record EntityLookupIndexSurface(MethodNode rebuild, MethodNode advance, MethodInsnNode listenerBoundary) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    EntityLookupIndexSurface surface = requireEntityLookupIndexSurface(node);
    FieldNode state = uniqueField(node, ENTITY_LOOKUP_INDEX_REPAIR_FIELD);
    if (state != null) {
      final int stateAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
      if (!state.desc.equals("Z") || state.access != stateAccess || state.signature != null || state.value != null) {
        return Match.incompatible("CampaignEngine entity-index readiness field is foreign");
      }
      requireEntityLookupIndexRepairBlock(node, surface);
      return Match.alreadyApplied("CampaignEngine post-load entity-index repair postcondition matches");
    }

    requireCount("CampaignEngine original advance entity-index rebuilds",
        countCalls(surface.advance(), Opcodes.INVOKEVIRTUAL, node.name, surface.rebuild().name, surface.rebuild().desc),
        1);
    return Match.plan(new Plan(node.name, node.fields, surface));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    final int stateAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
    plan.fields().add(new FieldNode(Opcodes.ASM8, stateAccess, ENTITY_LOOKUP_INDEX_REPAIR_FIELD, "Z", null, null));

    LabelNode ready = new LabelNode();
    InsnList repair = new InsnList();
    repair.add(new VarInsnNode(Opcodes.ALOAD, 0));
    repair.add(new FieldInsnNode(Opcodes.GETFIELD, plan.owner(), ENTITY_LOOKUP_INDEX_REPAIR_FIELD, "Z"));
    repair.add(new JumpInsnNode(Opcodes.IFNE, ready));
    repair.add(new VarInsnNode(Opcodes.ALOAD, 0));
    repair.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, plan.owner(), plan.surface().rebuild().name,
        plan.surface().rebuild().desc, false));
    repair.add(new VarInsnNode(Opcodes.ALOAD, 0));
    repair.add(new InsnNode(Opcodes.ICONST_1));
    repair.add(new FieldInsnNode(Opcodes.PUTFIELD, plan.owner(), ENTITY_LOOKUP_INDEX_REPAIR_FIELD, "Z"));
    repair.add(ready);
    plan.surface().advance().instructions.insert(plan.surface().listenerBoundary(), repair);

    PatchReport report = new PatchReport();
    report.add("transient CampaignEngine entity-index readiness flag", 1);
    report.add("one post-load vanilla entity-index rebuild", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireEntityLookupIndexRepairBlock(node, requireEntityLookupIndexSurface(node));
  }

  private static EntityLookupIndexSurface requireEntityLookupIndexSurface(ClassNode node) {
    MethodNode rebuild = requireMethod(node, "rebuildIDToEntityMap", "()V");
    if ((rebuild.access & Opcodes.ACC_PRIVATE) == 0
        || (rebuild.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch("CampaignEngine entity-index rebuild method access changed");
    }

    FieldInsnNode mapWrite = null;
    int hashMapAllocations = 0;
    for (AbstractInsnNode insn : rebuild.instructions.toArray()) {
      if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && field.owner.equals(node.name)
          && field.desc.equals("Ljava/util/Map;")) {
        if (mapWrite != null) { throw mismatch("CampaignEngine entity-index rebuild writes multiple Map fields"); }
        mapWrite = field;
      }
      if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
          && type.desc.equals("java/util/HashMap")) {
        hashMapAllocations++;
      }
    }
    if (mapWrite == null) { throw mismatch("CampaignEngine entity-index rebuild Map field was not found"); }
    requireCount("CampaignEngine entity-index HashMap allocation", hashMapAllocations, 1);
    requireCount("CampaignEngine entity-index population sites", calls(rebuild, Opcodes.INVOKEINTERFACE,
        "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;").size(), 2);

    MethodNode lookup = requireMethod(node, "getEntityById", "(Ljava/lang/String;)" + ENTITY_TOKEN_DESC);
    MethodInsnNode lookupRebuild = only(calls(lookup, Opcodes.INVOKEVIRTUAL, node.name, rebuild.name, rebuild.desc),
        "CampaignEngine.getEntityById null-map rebuild");
    Frame<SourceValue>[] lookupFrames = sourceFrames(node.name, lookup);
    if (!sourceIsLocal(receiverSource(lookup, lookupRebuild, lookupFrames), Opcodes.ALOAD, 0)) {
      throw mismatch("CampaignEngine.getEntityById rebuild receiver is not this");
    }
    int lookupMapReads = 0;
    for (AbstractInsnNode insn : lookup.instructions.toArray()) {
      if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
          || !field.owner.equals(node.name) || !field.desc.equals("Ljava/util/Map;")) {
        continue;
      }
      if (!field.name.equals(mapWrite.name)) {
        throw mismatch("CampaignEngine.getEntityById reads multiple Map fields");
      }
      lookupMapReads++;
    }
    requireCount("CampaignEngine.getEntityById entity-index reads", lookupMapReads, 5);

    MethodNode advance = requireMethod(node, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
    MethodInsnNode listenerBoundary = requireEntityLookupListenerBoundary(node, advance);
    return new EntityLookupIndexSurface(rebuild, advance, listenerBoundary);
  }

  private static MethodInsnNode requireEntityLookupListenerBoundary(ClassNode node, MethodNode advance) {
    List<MethodInsnNode> raw = calls(advance, Opcodes.INVOKEVIRTUAL, node.name, "readdChangeListeners", "()V");
    String hookDesc = "(Ljava/lang/Object;Ljava/util/List;Ljava/lang/Object;)V";
    List<MethodInsnNode> throttled =
        calls(advance, Opcodes.INVOKESTATIC, HOOKS, "readdChangeListenersIfNeeded", hookDesc);
    Frame<SourceValue>[] frames = sourceFrames(node.name, advance);
    if (raw.size() == 1 && throttled.isEmpty()) {
      MethodInsnNode boundary = raw.get(0);
      if (!sourceIsLocal(receiverSource(advance, boundary, frames), Opcodes.ALOAD, 0)) {
        throw mismatch("CampaignEngine listener restoration receiver is not this");
      }
      return boundary;
    }
    if (raw.isEmpty() && throttled.size() == 1) {
      MethodInsnNode boundary = throttled.get(0);
      if (!sourceIsLocal(argumentSource(advance, boundary, frames, 0), Opcodes.ALOAD, 0)) {
        throw mismatch("CampaignEngine throttled listener restoration engine is not this");
      }
      return boundary;
    }
    throw mismatch("CampaignEngine listener restoration boundary is missing or ambiguous: raw=" + raw.size()
        + ", throttled=" + throttled.size());
  }

  private static void requireEntityLookupIndexRepairBlock(ClassNode node, EntityLookupIndexSurface surface) {
    requireCount("CampaignEngine repaired advance entity-index rebuilds",
        countCalls(surface.advance(), Opcodes.INVOKEVIRTUAL, node.name, surface.rebuild().name, surface.rebuild().desc),
        2);

    AbstractInsnNode cursor = nextMeaningful(surface.listenerBoundary());
    requireVar(cursor, Opcodes.ALOAD, 0, "entity-index readiness owner");
    cursor = nextMeaningful(cursor);
    requireField(cursor, Opcodes.GETFIELD, node.name, ENTITY_LOOKUP_INDEX_REPAIR_FIELD, "Z",
        "entity-index readiness read");
    cursor = nextMeaningful(cursor);
    if (!(cursor instanceof JumpInsnNode ready) || ready.getOpcode() != Opcodes.IFNE) {
      throw mismatch("entity-index readiness branch changed");
    }
    cursor = nextMeaningful(cursor);
    requireVar(cursor, Opcodes.ALOAD, 0, "entity-index rebuild owner");
    cursor = nextMeaningful(cursor);
    if (!(cursor instanceof MethodInsnNode rebuild)
        || !callMatches(rebuild, Opcodes.INVOKEVIRTUAL, node.name, surface.rebuild().name, surface.rebuild().desc)) {
      throw mismatch("entity-index guarded rebuild call changed");
    }
    cursor = nextMeaningful(cursor);
    requireVar(cursor, Opcodes.ALOAD, 0, "entity-index readiness write owner");
    cursor = nextMeaningful(cursor);
    if (cursor == null || cursor.getOpcode() != Opcodes.ICONST_1) {
      throw mismatch("entity-index readiness value changed");
    }
    cursor = nextMeaningful(cursor);
    requireField(cursor, Opcodes.PUTFIELD, node.name, ENTITY_LOOKUP_INDEX_REPAIR_FIELD, "Z",
        "entity-index readiness write");
    if (surface.advance().instructions.indexOf(ready.label) <= surface.advance().instructions.indexOf(cursor)
        || nextMeaningful(cursor) != nextMeaningful(ready.label)) {
      throw mismatch("entity-index readiness branch does not join after publication");
    }
  }
}
