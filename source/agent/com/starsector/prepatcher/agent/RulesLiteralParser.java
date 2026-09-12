package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.Queries.*;

final class RulesLiteralParser implements StructuredPatch<RulesLiteralParser.Plan> {
  record Plan(MethodNode method, List<MethodInsnNode> carriageReturns, List<MethodInsnNode> newlineToSpace,
      List<MethodInsnNode> newlineSplits, List<MethodInsnNode> orSplits, List<MethodInsnNode> colonSplits) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode method = requireMethod(node, "o00000", "(Lcom/fs/starfarer/loading/ResourceLoaderState;)V");
    List<MethodInsnNode> carriageReturns = new ArrayList<>();
    List<MethodInsnNode> newlineToSpace = new ArrayList<>();
    List<MethodInsnNode> newlineSplits = new ArrayList<>();
    List<MethodInsnNode> orSplits = new ArrayList<>();
    List<MethodInsnNode> colonSplits = new ArrayList<>();

    for (MethodInsnNode call : calls(method, Opcodes.INVOKEVIRTUAL, "java/lang/String", "replaceAll",
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")) {
      AbstractInsnNode replacement = previousMeaningful(call);
      AbstractInsnNode pattern = previousMeaningful(replacement);
      if (isStringLdc(pattern, "\\r") && isStringLdc(replacement, "")) {
        carriageReturns.add(call);
      } else if (isStringLdc(pattern, "\\n") && isStringLdc(replacement, " ")) {
        newlineToSpace.add(call);
      }
    }
    for (MethodInsnNode call : calls(method, Opcodes.INVOKEVIRTUAL, "java/lang/String", "split",
        "(Ljava/lang/String;)[Ljava/lang/String;")) {
      AbstractInsnNode delimiter = previousMeaningful(call);
      if (isStringLdc(delimiter, "\\n")) {
        newlineSplits.add(call);
      } else if (isStringLdc(delimiter, "\nOR\n")) {
        orSplits.add(call);
      } else if (delimiter instanceof MethodInsnNode quote && callMatches(quote, Opcodes.INVOKESTATIC,
          "java/util/regex/Pattern", "quote", "(Ljava/lang/String;)Ljava/lang/String;")
          && isStringLdc(previousMeaningful(quote), ":")) {
            colonSplits.add(call);
          }
    }

    int crHooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "removeCarriageReturns",
        "(Ljava/lang/String;)Ljava/lang/String;");
    int spaceHooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "replaceNewlinesWithSpace",
        "(Ljava/lang/String;)Ljava/lang/String;");
    int newlineHooks =
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "splitNewlines", "(Ljava/lang/String;)[Ljava/lang/String;");
    int orHooks =
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "splitOrBlocks", "(Ljava/lang/String;)[Ljava/lang/String;");
    int colonHooks =
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "splitColon", "(Ljava/lang/String;)[Ljava/lang/String;");
    if (carriageReturns.isEmpty() && newlineToSpace.isEmpty() && newlineSplits.isEmpty() && orSplits.isEmpty()
        && colonSplits.isEmpty() && crHooks == 3 && spaceHooks == 1 && newlineHooks == 3 && orHooks == 1
        && colonHooks == 1) {
      return Match.alreadyApplied("literal rules parser postcondition matches");
    }
    requireCount("rules CR replacements", carriageReturns.size(), 3);
    requireCount("rules newline-to-space replacements", newlineToSpace.size(), 1);
    requireCount("rules newline splits", newlineSplits.size(), 3);
    requireCount("rules OR splits", orSplits.size(), 1);
    requireCount("rules colon splits", colonSplits.size(), 1);
    requireCount("rules CR hooks", crHooks, 0);
    requireCount("rules newline-to-space hooks", spaceHooks, 0);
    requireCount("rules newline hooks", newlineHooks, 0);
    requireCount("rules OR hooks", orHooks, 0);
    requireCount("rules colon hooks", colonHooks, 0);
    return Match.plan(new Plan(method, carriageReturns, newlineToSpace, newlineSplits, orSplits, colonSplits));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode method = plan.method();
    for (MethodInsnNode call : plan.carriageReturns()) {
      AbstractInsnNode replacement = previousMeaningful(call);
      AbstractInsnNode pattern = previousMeaningful(replacement);
      method.instructions.remove(pattern);
      method.instructions.remove(replacement);
      makeStatic(call, HOOKS, "removeCarriageReturns", "(Ljava/lang/String;)Ljava/lang/String;");
    }
    for (MethodInsnNode call : plan.newlineToSpace()) {
      AbstractInsnNode replacement = previousMeaningful(call);
      AbstractInsnNode pattern = previousMeaningful(replacement);
      method.instructions.remove(pattern);
      method.instructions.remove(replacement);
      makeStatic(call, HOOKS, "replaceNewlinesWithSpace", "(Ljava/lang/String;)Ljava/lang/String;");
    }
    for (MethodInsnNode call : plan.newlineSplits()) {
      method.instructions.remove(previousMeaningful(call));
      makeStatic(call, HOOKS, "splitNewlines", "(Ljava/lang/String;)[Ljava/lang/String;");
    }
    for (MethodInsnNode call : plan.orSplits()) {
      method.instructions.remove(previousMeaningful(call));
      makeStatic(call, HOOKS, "splitOrBlocks", "(Ljava/lang/String;)[Ljava/lang/String;");
    }
    for (MethodInsnNode call : plan.colonSplits()) {
      AbstractInsnNode quote = previousMeaningful(call);
      AbstractInsnNode colon = previousMeaningful(quote);
      method.instructions.remove(colon);
      method.instructions.remove(quote);
      makeStatic(call, HOOKS, "splitColon", "(Ljava/lang/String;)[Ljava/lang/String;");
    }

    PatchReport report = new PatchReport();
    report.add("removeCR", plan.carriageReturns().size());
    report.add("newlineToSpace", plan.newlineToSpace().size());
    report.add("splitNewline", plan.newlineSplits().size());
    report.add("splitOR", plan.orSplits().size());
    report.add("splitColon", plan.colonSplits().size());
    return report;
  }
}
