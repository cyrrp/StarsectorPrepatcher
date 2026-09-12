package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rename-survival fixture. Feeds renamed game class bytes straight into the two-phase
 * patches' match(ClassNode) and reports whether the structural contract still proves
 * out, and if not, which anchor stopped resolving.
 *
 * <p>The rename is class-internal-names only: member names, descriptor shapes, string
 * literals and instruction structure are never touched. Three scopes are measured:
 * S1 renames only the patch's target class, S2 every class in the implementation
 * jars (starfarer_obf, fs.common_obf, fs.sound_obf), S3 additionally renames
 * starfarer.api.jar. Every class in the loaded universe is rewritten under each
 * scope's map so cross-jar references stay consistent; classes that reference no
 * renamed name come out byte-identical.
 *
 * <p>Usage: RenameFixtureHarness <starsector-core dir> <results file>
 */
public final class RenameFixtureHarness {
  private static final String[] IMPL_JARS = {"starfarer_obf.jar", "fs.common_obf.jar", "fs.sound_obf.jar"};
  private static final String API_JAR = "starfarer.api.jar";
  private static final Pattern NAME_IN_SIGNATURE = Pattern.compile("L([^;<>()]+)");

  private static PrintStream out;

  private RenameFixtureHarness() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      throw new IllegalArgumentException("Usage: RenameFixtureHarness <starsector-core dir> <results file>");
    }
    Path core = Path.of(args[0]).toAbsolutePath().normalize();
    Path results = Path.of(args[1]).toAbsolutePath().normalize();
    Files.createDirectories(results.getParent());
    out = new PrintStream(Files.newOutputStream(results), false, "UTF-8");

    Map<String, byte[]> universe = new TreeMap<>();
    Map<String, String> classJar = new TreeMap<>();
    for (String jar : jars()) {
      Path jarPath = core.resolve(jar);
      try (JarFile jf = new JarFile(jarPath.toFile())) {
        Enumeration<JarEntry> entries = jf.entries();
        while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();
          String name = entry.getName();
          if (!name.endsWith(".class") || name.startsWith("META-INF/versions/") || name.equals("module-info.class")
              || name.endsWith("package-info.class")) {
            continue;
          }
          String internal = name.substring(0, name.length() - ".class".length());
          byte[] bytes = jf.getInputStream(entry).readAllBytes();
          if (universe.put(internal, bytes) != null) {
            emit("WARNING duplicate internal name " + internal + " in " + jar + " (kept first)");
          } else {
            classJar.put(internal, jar);
          }
        }
      }
    }
    emit("UNIVERSE classes=" + universe.size());

    Set<String> implClasses = new LinkedHashSet<>();
    Set<String> apiClasses = new LinkedHashSet<>();
    for (Map.Entry<String, String> e : classJar.entrySet()) {
      if (API_JAR.equals(e.getValue())) apiClasses.add(e.getKey());
      else implClasses.add(e.getKey());
    }
    emit("UNIVERSE impl=" + implClasses.size() + " api=" + apiClasses.size());

    List<PatchProbe> probes = List.of(
        new PatchProbe("gridLineCap", "com.starsector.prepatcher.agent.mapintel.GridLineCap", PrepatcherTransformer.H,
            RenameFixtureHarness::classifyGridLineCap),
        new PatchProbe("mapHitTest", "com.starsector.prepatcher.agent.mapintel.MapHitTest", PrepatcherTransformer.A,
            RenameFixtureHarness::classifyMapHitTest));

    Map<String, String> s2 = buildMap(implClasses);
    Map<String, String> s3 = buildMap(union(implClasses, apiClasses));
    checkMapCollisions(s2, universe.keySet(), "S2");
    checkMapCollisions(s3, universe.keySet(), "S3");
    emit("MAP S2 entries=" + s2.size() + " S3 entries=" + s3.size() + " example " + PrepatcherTransformer.H + " -> "
        + s2.get(PrepatcherTransformer.H));

    // Rename each scope's universe exactly once. Per-target S1 maps rename only
    // the patch's target class.
    Map<String, RenamedUniverse> scopes = new LinkedHashMap<>();
    for (PatchProbe probe : probes) {
      Map<String, String> m = new LinkedHashMap<>();
      m.put(probe.target, renameOne(probe.target, 0));
      scopes.put("S1:" + probe.target, renameUniverse(universe, m, "S1:" + probe.target, false));
    }
    scopes.put("S2", renameUniverse(universe, s2, "S2", true));
    scopes.put("S3", renameUniverse(universe, s3, "S3", true));

    for (PatchProbe probe : probes) {
      String target = probe.target;
      byte[] vanilla = universe.get(target);
      if (vanilla == null) throw new IllegalStateException("target missing: " + target);
      emit("==== patch=" + probe.id + " target=" + target + " ====");

      // S0 control: unrenamed bytes must prove the contract.
      runMatch(probe, "S0", vanilla);

      for (String scope : List.of("S1", "S2", "S3")) {
        RenamedUniverse ru = scope.equals("S1") ? scopes.get("S1:" + target) : scopes.get(scope);
        byte[] renamedTarget = ru.renamed.get(target);
        if (renamedTarget == null) {
          emit("FATAL renamed target missing for " + target + " scope=" + scope);
          continue;
        }
        verifyDifferential(probe.id, scope, target, vanilla, renamedTarget);
        residualScanTarget(probe.id, scope, vanilla, renamedTarget, ru.map.keySet());
        runMatch(probe, scope, renamedTarget);
        Path dumpDir = Path.of(".build").resolve("rename-fixture-classes").resolve(scope).resolve(probe.id);
        Files.createDirectories(dumpDir);
        Files.write(dumpDir.resolve(target.substring(target.lastIndexOf('/') + 1) + ".class"), renamedTarget);
      }
    }
    out.flush();
  }

  private static String[] jars() {
    String[] all = new String[IMPL_JARS.length + 1];
    System.arraycopy(IMPL_JARS, 0, all, 0, IMPL_JARS.length);
    all[IMPL_JARS.length] = API_JAR;
    return all;
  }

  private static Set<String> union(Set<String> a, Set<String> b) {
    Set<String> u = new LinkedHashSet<>(a);
    u.addAll(b);
    return u;
  }

  private static String renameOne(String internal, int serial) {
    int slash = internal.lastIndexOf('/');
    String pkg = slash < 0 ? "" : internal.substring(0, slash + 1);
    return pkg + "R" + serial;
  }

  private static Map<String, String> buildMap(Set<String> names) {
    List<String> sorted = new ArrayList<>(names);
    sorted.sort(null);
    Map<String, String> map = new LinkedHashMap<>();
    int i = 0;
    for (String name : sorted) map.put(name, renameOne(name, i++));
    return map;
  }

  private static void checkMapCollisions(Map<String, String> map, Set<String> universe, String scope) {
    Set<String> clashing = new LinkedHashSet<>(map.values());
    clashing.retainAll(universe);
    if (!clashing.isEmpty()) {
      emit("FATAL " + scope + " rename map collides with existing class names: " + clashing);
    }
    if (new LinkedHashSet<>(map.values()).size() != map.size()) {
      emit("FATAL " + scope + " rename map is not injective");
    }
  }

  private static final class RenamedUniverse {
    final Map<String, String> map;
    /** Keyed by ORIGINAL internal name. */
    final Map<String, byte[]> renamed = new LinkedHashMap<>();
    int structuralResidual;
    int stringResidual;

    RenamedUniverse(Map<String, String> map) {
      this.map = map;
    }
  }

  /**
   * Rewrites every loaded class under the scope map. For full-jar scopes also runs
   * the universe-wide residual scan (every renamed class is reparsed and all of its
   * referenced names are checked against the old-name set) and a BasicVerifier pass
   * over every renamed class. Both are reported, not asserted, except that a
   * structural residual is always a renamer defect.
   */
  private static RenamedUniverse renameUniverse(Map<String, byte[]> universe, Map<String, String> map, String scope,
      boolean deepCheck) {
    Remapper remapper = new SimpleRemapper(map);
    RenamedUniverse ru = new RenamedUniverse(map);
    for (Map.Entry<String, byte[]> e : universe.entrySet()) {
      ru.renamed.put(e.getKey(), remap(e.getValue(), remapper));
    }
    emit("RENAME scope=" + scope + " classes=" + ru.renamed.size() + " mapped=" + map.size());
    if (!deepCheck) return ru;

    Set<String> oldDotted = new LinkedHashSet<>();
    for (String old : map.keySet()) oldDotted.add(old.replace('/', '.'));
    long start = System.nanoTime();
    int verifyFailed = 0;
    int verifyFailedVanillaToo = 0;
    int parsed = 0;
    for (Map.Entry<String, byte[]> e : ru.renamed.entrySet()) {
      String oldName = e.getKey();
      byte[] bytes = e.getValue();
      ClassNode node;
      try {
        node = readNode(bytes);
      } catch (Throwable parseFailure) {
        emit("FATAL reparse failed for renamed " + oldName + ": " + parseFailure);
        continue;
      }
      parsed++;
      ReferencedNames refs = collectReferencedNames(node, map.keySet(), oldDotted, false);
      ru.structuralResidual += refs.structural.size();
      ru.stringResidual += refs.strings.size();
      for (String r : refs.structural) {
        emit("RESIDUAL_STRUCTURAL scope=" + scope + " class=" + oldName + " name=" + r);
      }
      List<String> failures = verifyFailures(node);
      if (!failures.isEmpty()) {
        verifyFailed++;
        // Differential: does the unrenamed class fail the same way?
        List<String> base = verifyFailures(readNode(universe.get(oldName)));
        if (!base.isEmpty()) {
          verifyFailedVanillaToo++;
        } else {
          for (String f : failures) {
            emit("VERIFY_FAILURE scope=" + scope + " class=" + oldName + " " + f);
          }
        }
      }
    }
    long ms = (System.nanoTime() - start) / 1_000_000;
    emit("RENAME_CHECK scope=" + scope + " parsed=" + parsed + " structuralResidual=" + ru.structuralResidual
        + " ldcStringResidual=" + ru.stringResidual + " verifyFailed=" + verifyFailed + " verifyFailedVanillaToo="
        + verifyFailedVanillaToo + " timeMs=" + ms);
    return ru;
  }

  private static byte[] remap(byte[] bytes, Remapper remapper) {
    ClassReader reader = new ClassReader(bytes);
    ClassWriter writer = new ClassWriter(0);
    reader.accept(new ClassRemapper(writer, remapper), 0);
    return writer.toByteArray();
  }

  private static ClassNode readNode(byte[] bytes) {
    ClassNode node = new ClassNode(Opcodes.ASM8);
    new ClassReader(bytes).accept(node, 0);
    return node;
  }

  /** The repo's own verification: BasicVerifier dataflow per concrete method. */
  private static List<String> verifyFailures(ClassNode node) {
    List<String> failures = new ArrayList<>();
    for (MethodNode method : node.methods) {
      if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
      try {
        new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
      } catch (AnalyzerException | RuntimeException ex) {
        failures.add(node.name + "." + method.name + method.desc + ": " + ex.getMessage());
      }
    }
    return failures;
  }

  private static void verifyDifferential(String patchId, String scope, String target, byte[] vanilla, byte[] renamed) {
    List<String> base = verifyFailures(readNode(vanilla));
    List<String> after = verifyFailures(readNode(renamed));
    emit("VERIFY patch=" + patchId + " scope=" + scope + " vanillaFailures=" + base.size() + " renamedFailures="
        + after.size());
    for (String f : after) emit("VERIFY_FAILURE target=" + target + " " + f);
  }

  /**
   * Totality check on the bytes actually fed to match(): no old internal name may
   * survive in a structural slot of the renamed target class. Old names inside LDC
   * string constants are reported separately (reflection-style references a
   * mechanical rename cannot fix). A byte-level scan for every name the class
   * referenced before the rename catches constant-pool leftovers ASM never visits.
   */
  private static void residualScanTarget(String patchId, String scope, byte[] vanilla, byte[] renamed,
      Set<String> oldNames) {
    ClassNode node = readNode(renamed);
    Set<String> oldDotted = new LinkedHashSet<>();
    for (String old : oldNames) oldDotted.add(old.replace('/', '.'));
    ReferencedNames refs = collectReferencedNames(node, oldNames, oldDotted, true);
    Set<String> before = collectAllNames(readNode(vanilla));
    before.retainAll(oldNames);
    int cpResidual = 0;
    for (String old : before) {
      if (containsBytes(renamed, old.getBytes(StandardCharsets.UTF_8))) {
        cpResidual++;
        emit("RESIDUAL_CP_NAME patch=" + patchId + " scope=" + scope + " name=" + old);
      }
    }
    emit("RESIDUAL patch=" + patchId + " scope=" + scope + " structural=" + refs.structural.size() + " ldcStrings="
        + refs.strings.size() + " constantPoolBytes=" + cpResidual);
    for (String r : refs.structural) emit("RESIDUAL_STRUCTURAL target=" + r);
    for (String r : refs.strings) emit("RESIDUAL_STRING target=" + r);
  }

  /**
   * Finds the old internal name as a complete constant-pool token: the byte after
   * the match must not continue the name (otherwise "…/util/A" would match the
   * unrelated renamed "…/util/A/R2854").
   */
  private static boolean containsBytes(byte[] haystack, byte[] needle) {
    outer : for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) continue outer;
      }
      int end = i + needle.length;
      if (end < haystack.length && isNameByte(haystack[end])) continue;
      return true;
    }
    return false;
  }

  private static boolean isNameByte(byte b) {
    return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9') || b == '_' || b == '$'
        || b == '/' || b == '-';
  }

  private static final class ReferencedNames {
    final Set<String> structural = new LinkedHashSet<>();
    final Set<String> strings = new LinkedHashSet<>();
  }

  /** Every internal name a class references, regardless of slot (pre-rename set). */
  private static Set<String> collectAllNames(ClassNode node) {
    Set<String> names = new LinkedHashSet<>();
    addName(names, node.name);
    addName(names, node.superName);
    if (node.interfaces != null) node.interfaces.forEach(i -> addName(names, i));
    if (node.outerClass != null) addName(names, node.outerClass);
    if (node.innerClasses != null) {
      for (InnerClassNode ic : node.innerClasses) {
        addName(names, ic.name);
        addName(names, ic.outerName);
      }
    }
    addName(names, node.nestHostClass);
    if (node.nestMembers != null) node.nestMembers.forEach(n -> addName(names, n));
    if (node.permittedSubclasses != null) {
      node.permittedSubclasses.forEach(n -> addName(names, n));
    }
    for (FieldNode f : node.fields) addDesc(names, f.desc);
    for (MethodNode m : node.methods) collectInsnNames(m, names, null);
    return names;
  }

  private static void collectInsnNames(MethodNode m, Set<String> names, Set<String> ldcStrings) {
    addDesc(names, m.desc);
    if (m.exceptions != null) m.exceptions.forEach(e -> addName(names, e));
    if (m.tryCatchBlocks != null) {
      for (TryCatchBlockNode t : m.tryCatchBlocks) addName(names, t.type);
    }
    for (AbstractInsnNode insn : m.instructions.toArray()) {
      if (insn instanceof MethodInsnNode mi) {
        addName(names, mi.owner);
        addDesc(names, mi.desc);
      } else if (insn instanceof FieldInsnNode fi) {
        addName(names, fi.owner);
        addDesc(names, fi.desc);
      } else if (insn instanceof TypeInsnNode ti) {
        addName(names, ti.desc);
      } else if (insn instanceof MultiANewArrayInsnNode ma) {
        addDesc(names, ma.desc);
      } else if (insn instanceof LdcInsnNode ldc) {
        if (ldc.cst instanceof Type t) addTypeName(names, t);
        if (ldcStrings != null && ldc.cst instanceof String s) ldcStrings.add(s);
      }
    }
  }

  /**
   * Post-rename residual check, split into structural slots vs LDC strings.
   * With thorough=false string constants only count exact old-name matches;
   * thorough=true also reports old names embedded inside longer literals.
   */
  private static ReferencedNames collectReferencedNames(ClassNode node, Set<String> oldNames, Set<String> oldDotted,
      boolean thorough) {
    ReferencedNames refs = new ReferencedNames();
    Set<String> structural = new LinkedHashSet<>();
    Set<String> ldcStrings = new LinkedHashSet<>();
    addName(structural, node.name);
    addName(structural, node.superName);
    if (node.interfaces != null) node.interfaces.forEach(i -> addName(structural, i));
    addName(structural, node.outerClass);
    if (node.innerClasses != null) {
      for (InnerClassNode ic : node.innerClasses) {
        addName(structural, ic.name);
        addName(structural, ic.outerName);
      }
    }
    addName(structural, node.nestHostClass);
    if (node.nestMembers != null) node.nestMembers.forEach(n -> addName(structural, n));
    if (node.permittedSubclasses != null) {
      node.permittedSubclasses.forEach(n -> addName(structural, n));
    }
    if (node.recordComponents != null) {
      for (RecordComponentNode rc : node.recordComponents) addDesc(structural, rc.descriptor);
    }
    for (FieldNode f : node.fields) addDesc(structural, f.desc);
    for (MethodNode m : node.methods) collectInsnNames(m, structural, ldcStrings);
    // Signature attributes carry generics; ClassRemapper rewrites them, so any
    // old name left inside one is a structural residual too.
    for (String sig : signatures(node)) {
      Matcher matcher = NAME_IN_SIGNATURE.matcher(sig);
      while (matcher.find()) addName(structural, matcher.group(1));
    }
    structural.retainAll(oldNames);
    refs.structural.addAll(structural);
    for (String s : ldcStrings) {
      if (oldNames.contains(s) || oldDotted.contains(s)) {
        refs.strings.add(s);
        continue;
      }
      if (thorough && (s.indexOf('/') >= 0 || s.indexOf('.') >= 0)) {
        for (String old : oldNames) {
          if (s.contains(old) || s.contains(old.replace('/', '.'))) {
            refs.strings.add(s);
            break;
          }
        }
      }
    }
    return refs;
  }

  private static List<String> signatures(ClassNode node) {
    List<String> sigs = new ArrayList<>();
    if (node.signature != null) sigs.add(node.signature);
    for (FieldNode f : node.fields) if (f.signature != null) sigs.add(f.signature);
    for (MethodNode m : node.methods) if (m.signature != null) sigs.add(m.signature);
    return sigs;
  }

  private static void addName(Set<String> out, String internal) {
    if (internal != null) out.add(internal);
  }

  private static void addDesc(Set<String> out, String desc) {
    if (desc == null) return;
    try {
      if (desc.startsWith("(")) {
        for (Type t : Type.getArgumentTypes(desc)) addTypeName(out, t);
        addTypeName(out, Type.getReturnType(desc));
      } else {
        addTypeName(out, Type.getType(desc));
      }
    } catch (RuntimeException ignored) {
      // Descriptors containing Java-illegal obfuscated names can defeat the
      // Type parser; fall back to a structural L-name scan.
      Matcher matcher = NAME_IN_SIGNATURE.matcher(desc);
      while (matcher.find()) out.add(matcher.group(1));
    }
  }

  private static void addTypeName(Set<String> out, Type t) {
    if (t.getSort() == Type.ARRAY) t = t.getElementType();
    if (t.getSort() == Type.OBJECT) out.add(t.getInternalName());
  }

  // ------------------------------------------------------------------
  // match() invocation and outcome classification
  // ------------------------------------------------------------------

  private record PatchProbe(String id, String patchClassName, String target, AnchorClassifier classifier) {
  }

  @FunctionalInterface
  private interface AnchorClassifier {
    /** Maps a refusal reason to "anchor|kind"; null means unrecognized. */
    String classify(String reason, ClassNode renamedNode);
  }

  private static void runMatch(PatchProbe probe, String scope, byte[] bytes) {
    String outcome;
    String reason = "-";
    String anchor = "-";
    String kind = "-";
    try {
      Class<?> patchClass = Class.forName(probe.patchClassName);
      java.lang.reflect.Constructor<?> ctor = patchClass.getDeclaredConstructor();
      ctor.setAccessible(true);
      Object patch = ctor.newInstance();
      Method match = patchClass.getDeclaredMethod("match", ClassNode.class);
      match.setAccessible(true);
      Object result = match.invoke(patch, readNode(bytes));
      Class<?> matchClass = result.getClass();
      Field planF = matchClass.getDeclaredField("plan");
      Field reasonF = matchClass.getDeclaredField("reason");
      Field alreadyF = matchClass.getDeclaredField("alreadyApplied");
      planF.setAccessible(true);
      reasonF.setAccessible(true);
      alreadyF.setAccessible(true);
      Object plan = planF.get(result);
      String r = (String) reasonF.get(result);
      boolean already = (boolean) alreadyF.get(result);
      if (plan != null) {
        outcome = "PROVEN";
      } else if (already) {
        outcome = "ALREADY";
        reason = r;
      } else {
        outcome = "INCOMPATIBLE";
        reason = r;
      }
    } catch (InvocationTargetException ex) {
      Throwable cause = ex.getCause();
      outcome = cause.getClass().getSimpleName().replace("Exception", "").toUpperCase();
      reason = String.valueOf(cause.getMessage());
    } catch (Throwable ex) {
      outcome = "HARNESS_ERROR";
      reason = ex.toString();
    }
    if (!"PROVEN".equals(outcome) && !"-".equals(reason)) {
      String classified = probe.classifier.classify(reason, readNode(bytes));
      if (classified != null) {
        int bar = classified.indexOf('|');
        anchor = classified.substring(0, bar);
        kind = classified.substring(bar + 1);
      } else {
        anchor = "UNRECOGNIZED";
        kind = "UNRECOGNIZED";
      }
    }
    emit("RESULT patch=" + probe.id + " scope=" + scope + " outcome=" + outcome + " anchor=" + anchor + " kind=" + kind
        + " reason=\"" + reason + "\"");
  }

  // ------------------------------------------------------------------
  // Per-patch anchor tables, authored against match() source order.
  // Kind: CLASS_NAME, MEMBER_NAME, DESCRIPTOR, STRUCTURAL.
  // ------------------------------------------------------------------

  private static String classifyGridLineCap(String reason, ClassNode node) {
    if (reason.contains("expected one method") && reason.contains(".null(F)V")) {
      return "method 'null'(F)V on target class|MEMBER_NAME";
    }
    if (reason.contains("gridSpacing")) { return "mod-hook call count (gridSpacing)|STRUCTURAL"; }
    if (reason.contains("global-grid bound constants")) {
      return "ldc 2000f bound constants in FLOAD/FDIV/F2I/ISTORE context|STRUCTURAL";
    }
    if (reason.contains("global-grid step constant")) {
      return "ldc 2000f step constant before ALOAD0/GETFIELD self.F/FMUL/FSTORE|STRUCTURAL";
    }
    if (reason.contains("grid starscape guard")) { return "INVOKEVIRTUAL self.isStarscapeMode()Z|MEMBER_NAME"; }
    if (reason.contains("not dominated by the starscape branch marker")) {
      return "constant dominance vs starscape branch|STRUCTURAL";
    }
    return null;
  }

  private static String classifyMapHitTest(String reason, ClassNode node) {
    if (reason.contains("OO0000") && (reason.contains("wrapper collision/mixed state")
        || reason.contains("invalid partial wrapper") || reason.contains("expected one method"))) {
      // Evidence in the renamed class: does the member name still resolve,
      // and does the original descriptor?
      boolean namePresent = node.methods.stream().anyMatch(m -> m.name.equals("OO0000"));
      boolean descPresent =
          node.methods.stream().anyMatch(m -> m.desc.equals("(FFF)Lcom/fs/starfarer/api/campaign/SectorEntityToken;"));
      if (namePresent && !descPresent) {
        return "descriptor (FFF)L…/SectorEntityToken; of method OO0000 "
            + "(member name still resolves, descriptor does not)|DESCRIPTOR";
      }
      if (!namePresent) return "method name OO0000|MEMBER_NAME";
      return "method OO0000(FFF)L…/SectorEntityToken; member+descriptor|MEMBER_NAME";
    }
    if (reason.contains("scratch-scope")) { return "scratch-scope begin/end hook counts|STRUCTURAL"; }
    if (reason.contains("use contract changed")) {
      // The use-site contract hardcodes call-site owners; a wildcard covers
      // member names only where the patch wrote one.
      if (reason.contains("com/fs/starfarer/prototype/")) {
        return "scratch-local use-site owner com/fs/starfarer/prototype/Utils "
            + "(INVOKESTATIC, member name wildcarded)|CLASS_NAME";
      }
      if (reason.contains("com/fs/starfarer/api/util/Misc")) {
        return "scratch-local use-site owner com/fs/starfarer/api/util/Misc|CLASS_NAME";
      }
      return "scratch-local use-site call contract|STRUCTURAL";
    }
    if (reason.contains("hit-test scratch") || reason.contains("allocation/hook sites")
        || reason.contains("allocation result")) {
      return "ArrayList/Vector2f allocation group|STRUCTURAL";
    }
    if (reason.contains("writes state or uses monitor")) { return "no-write/no-monitor invariant|STRUCTURAL"; }
    if (reason.contains("hit-test semantic call count")) { return "total method-call count 50|STRUCTURAL"; }
    if (reason.contains("map-handler fields")) {
      return "field of type Lcom/fs/starfarer/coreui/A/H; on target|CLASS_NAME";
    }
    if (reason.contains("hit-test H.getLocation")) {
      return "INVOKEVIRTUAL H.getLocation()L…/BaseLocation;|CLASS_NAME";
    }
    if (reason.contains("hit-test H.getFactor")) { return "INVOKEVIRTUAL H.getFactor()F|CLASS_NAME"; }
    if (reason.contains("hit-test H.getIcons")) {
      return "INVOKEVIRTUAL H.getIcons()Ljava/util/LinkedHashMap;|CLASS_NAME";
    }
    if (reason.contains("mixed wrapper/scope/allocation state") || reason.contains("wrapper exists without")) {
      return "wrapper/scope/allocation consistency|STRUCTURAL";
    }
    return null;
  }

  private static void emit(String line) {
    System.out.println(line);
    out.println(line);
  }
}
