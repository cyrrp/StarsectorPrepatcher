package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import com.starsector.prepatcher.agent.aotd.AoTDForkCompatibilityTransformer;

/**
 * Exact-JAR regression coverage for the fork-owned construction-start
 * boundary. The test deliberately mutates the current method surface to make
 * sure a future incompatible fork revision fails closed rather than receiving
 * a name-only transformation.
 */
public final class AoTDForkCompatibilityTransformerTest {
  private static final String ENTRY = "data/kaysaar/aotd/tot/industries/AoTDConstructionSite.class";
  private static final String TARGET = "data/kaysaar/aotd/tot/industries/AoTDConstructionSite";
  private static final String METHOD_DESC = "(Ljava/lang/String;)V";
  private static final String BRIDGE = "data/kaysaar/aotd/tot/compat/SchedulerBridge";
  private static final String BEFORE_DESC = "(Ljava/lang/Object;I)J";
  private static final String AFTER_DESC = "(JLjava/lang/Object;IJ)V";
  private static final String MARKER = "smo$patched$aotdConstructionStartBoundary";
  private static final String STATUS_KEY = "starsector.prepatcher.patchStatus."
      + "data.kaysaar.aotd.tot.industries.AoTDConstructionSite." + "aotdConstructionStartBoundary";

  private AoTDForkCompatibilityTransformerTest() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Expected AoTD JAR path");
    Map<String, byte[]> forkClasses = readClasses(Path.of(args[0]));
    byte[] original = forkClasses.get(TARGET.replace('/', '.'));
    require(original != null, "missing exact fork class: " + ENTRY);
    URL forkUrl = Path.of(args[0]).toUri().toURL();
    ProtectionDomain domain = domain(forkUrl);
    try (URLClassLoader loader = resourceOnlyLoader(new URL[]{forkUrl})) {

      require(new AoTDForkCompatibilityTransformer(false).transform(null, TARGET, null, null, original) == null,
          "disabled compatibility transformer changed the class");
      require(
          new AoTDForkCompatibilityTransformer(true).transform(null, TARGET + "$Other", null, null, original) == null,
          "compatibility transformer changed an unrelated class");

      System.clearProperty(STATUS_KEY);
      AoTDForkCompatibilityTransformer transformer = new AoTDForkCompatibilityTransformer(true);
      byte[] patched = transformer.transform(loader, TARGET, null, domain, original);
      require(patched != null, "exact fork construction site did not patch");
      require("APPLIED".equals(System.getProperty(STATUS_KEY)),
          "unexpected apply status: " + System.getProperty(STATUS_KEY));
      inspectPatched(patched);
      verifyMethods(patched);
      verifyExactClassLoads(forkClasses, patched);

      System.clearProperty(STATUS_KEY);
      require(transformer.transform(loader, TARGET, null, domain, patched) == null,
          "repeated transformation was not idempotent");
      require("ALREADY_APPLIED".equals(System.getProperty(STATUS_KEY)),
          "unexpected idempotence status: " + System.getProperty(STATUS_KEY));

      byte[] changedFuture = changeBuildingAssignment(original, Opcodes.ICONST_0);
      System.clearProperty(STATUS_KEY);
      require(transformer.transform(loader, TARGET, null, domain, changedFuture) == null,
          "changed future construction method was transformed");
      require("SKIPPED_STRUCTURAL".equals(System.getProperty(STATUS_KEY)),
          "changed future method did not fail closed: " + System.getProperty(STATUS_KEY));

      byte[] orphanRaw = removeMarker(patched);
      System.clearProperty(STATUS_KEY);
      require(transformer.transform(loader, TARGET, null, domain, orphanRaw) == null, "orphan raw helper was accepted");
      require("SKIPPED_STRUCTURAL".equals(System.getProperty(STATUS_KEY)),
          "orphan raw helper did not fail closed: " + System.getProperty(STATUS_KEY));
    }
    verifyOwnershipCases(forkClasses, original, forkUrl);

    System.out.println("OK aotd-fork-compatibility-transformer "
        + "construction-start-boundary exact/idempotent/fail-closed/basic-verifier/no-bridge-invocation/ownership");
  }

  private static ProtectionDomain domain(URL origin) {
    return new ProtectionDomain(new CodeSource(origin, (Certificate[]) null), null);
  }

  private static URLClassLoader resourceOnlyLoader(URL[] urls) {
    return new URLClassLoader(urls, AoTDForkCompatibilityTransformerTest.class.getClassLoader()) {
      @Override
      protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("data.kaysaar.aotd.tot.")) {
          throw new AssertionError("Ownership inspection loaded optional class: " + name);
        }
        return super.loadClass(name, resolve);
      }
    };
  }

  private static void verifyOwnershipCases(Map<String, byte[]> classes, byte[] original, URL forkUrl) throws Exception {
    Path directory = Files.createTempDirectory("prepatcher-construction-ownership-");
    List<Path> files = new ArrayList<>();
    try {
      Map<String, byte[]> upstream = new HashMap<>(classes);
      upstream.keySet().removeIf(name -> name.startsWith("data.kaysaar.aotd.tot.compat."));
      URL upstreamUrl = fixtureJar(directory, files, "upstream.jar", upstream);
      try (URLClassLoader loader = resourceOnlyLoader(new URL[]{upstreamUrl})) {
        assertUnowned(loader, domain(upstreamUrl), original, "upstream without bridge");
        verifyUnpatchedInvocation(loader, domain(upstreamUrl));
      }
      // A fork elsewhere in the same search path is not proof of ownership.
      try (URLClassLoader loader = resourceOnlyLoader(new URL[]{upstreamUrl, forkUrl})) {
        assertUnowned(loader, domain(upstreamUrl), original, "mixed upstream/fork jars");
      }
      try (URLClassLoader parent = resourceOnlyLoader(new URL[]{forkUrl});
          URLClassLoader child = new URLClassLoader(new URL[]{upstreamUrl}, parent)) {
        assertUnowned(child, domain(upstreamUrl), original, "parent fork with upstream child");
      }
      try (URLClassLoader loader = resourceOnlyLoader(new URL[]{forkUrl})) {
        assertUnowned(loader, null, original, "missing code source");
        require(new AoTDForkCompatibilityTransformer(true, new ClassLoader(null) {}).transform(loader, TARGET, null,
            domain(forkUrl), original) == null, "invisible runtime accepted");
      }
      for (String missing : List.of("compat.SchedulerBridge", "compat.PrepatcherContract",
          "compat.SchedulerBridge$State")) {
        Map<String, byte[]> incomplete = new HashMap<>(classes);
        incomplete.remove("data.kaysaar.aotd.tot." + missing);
        URL url = fixtureJar(directory, files, "missing-" + missing + ".jar", incomplete);
        try (URLClassLoader loader = resourceOnlyLoader(new URL[]{url})) {
          assertUnowned(loader, domain(url), original, "missing " + missing);
        }
      }
      for (String fieldName : List.of("BRIDGE_SCHEMA", "FORK_VERSION", "DECLARED_CAPABILITIES")) {
        String owner = fieldName.equals("BRIDGE_SCHEMA") ? BRIDGE : "data/kaysaar/aotd/tot/compat/PrepatcherContract";
        Map<String, byte[]> future = new HashMap<>(classes);
        ClassNode node = read(future.get(owner.replace('/', '.')));
        FieldNode field = field(node, fieldName);
        field.value = fieldName.equals("BRIDGE_SCHEMA") ? Integer.valueOf(999)
            : fieldName.equals("FORK_VERSION") ? "future-fork" : Long.valueOf(0);
        future.put(owner.replace('/', '.'), write(node));
        URL url = fixtureJar(directory, files, "future-" + fieldName + ".jar", future);
        try (URLClassLoader loader = resourceOnlyLoader(new URL[]{url})) {
          assertUnowned(loader, domain(url), original, "incompatible " + fieldName);
        }
      }
    } finally {
      for (Path file : files) Files.deleteIfExists(file);
      Files.deleteIfExists(directory);
    }
  }

  private static URL fixtureJar(Path directory, List<Path> files, String name, Map<String, byte[]> classes)
      throws Exception {
    Path path = directory.resolve(name);
    files.add(path);
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
      for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
        // Only the optional package is needed for resource ownership inspection.
        if (!entry.getKey().startsWith("data.kaysaar.aotd.tot.")) continue;
        output.putNextEntry(new JarEntry(entry.getKey().replace('.', '/') + ".class"));
        output.write(entry.getValue());
        output.closeEntry();
      }
    }
    return path.toUri().toURL();
  }

  private static void assertUnowned(ClassLoader loader, ProtectionDomain domain, byte[] original, String label) {
    System.clearProperty(STATUS_KEY);
    require(new AoTDForkCompatibilityTransformer(true).transform(loader, TARGET, null, domain, original) == null,
        label + " was transformed");
    require("NOT_APPLICABLE_UNOWNED".equals(System.getProperty(STATUS_KEY)), label + " status mismatch");
  }

  /** Executes an upstream-shaped construction method with no bridge class available. */
  private static void verifyUnpatchedInvocation(ClassLoader resourceLoader, ProtectionDomain domain) throws Exception {
    ClassNode node = new ClassNode();
    node.version = Opcodes.V17;
    node.access = Opcodes.ACC_PUBLIC;
    node.name = TARGET;
    node.superName = "java/lang/Object";
    node.fields
        .add(new FieldNode(Opcodes.ACC_PUBLIC, "market", "Lcom/fs/starfarer/api/campaign/econ/MarketAPI;", null, null));
    node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "assignedWonder", "Ljava/lang/String;", null, null));
    node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "building", "Z", null, null));
    MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
    ctor.visitVarInsn(Opcodes.ALOAD, 0);
    ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    ctor.visitInsn(Opcodes.RETURN);
    node.methods.add(ctor);
    MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "setAssignedWonder", METHOD_DESC, null, null);
    method.visitVarInsn(Opcodes.ALOAD, 0);
    method.visitVarInsn(Opcodes.ALOAD, 1);
    method.visitFieldInsn(Opcodes.PUTFIELD, TARGET, "assignedWonder", "Ljava/lang/String;");
    method.visitVarInsn(Opcodes.ALOAD, 0);
    method.visitInsn(Opcodes.ICONST_1);
    method.visitFieldInsn(Opcodes.PUTFIELD, TARGET, "building", "Z");
    method.visitVarInsn(Opcodes.ALOAD, 0);
    method.visitFieldInsn(Opcodes.GETFIELD, TARGET, "market", "Lcom/fs/starfarer/api/campaign/econ/MarketAPI;");
    method.visitVarInsn(Opcodes.ALOAD, 1);
    method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/econ/MarketAPI",
        "instantiateIndustry", "(Ljava/lang/String;)Lcom/fs/starfarer/api/campaign/econ/Industry;", true);
    method.visitInsn(Opcodes.POP);
    method.visitInsn(Opcodes.ACONST_NULL);
    method.visitMethodInsn(Opcodes.INVOKESTATIC,
        "data/kaysaar/aotd/tot/scripts/trade/contracts/AoTDTradeContractManager", "addContract",
        "(Ldata/kaysaar/aotd/tot/scripts/trade/contracts/AoTDTradeContract;)V", false);
    method.visitInsn(Opcodes.RETURN);
    node.methods.add(method);
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    node.accept(writer);
    byte[] original = writer.toByteArray();
    ClassNode regression = read(original);
    method(regression, "setAssignedWonder", METHOD_DESC).instructions.insert(new InsnNode(Opcodes.POP2));
    method(regression, "setAssignedWonder", METHOD_DESC).instructions
        .insert(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "getRuntimeCapabilities", "()J", false));
    try {
      OptionalModLinkageAudit.verify(original, write(regression));
      throw new AssertionError("linkage audit missed the newly introduced bridge dependency");
    } catch (AssertionError expected) {
      require(expected.getMessage().contains("New optional-mod linkage"), expected.getMessage());
    }
    assertUnowned(resourceLoader, domain, original, "callable upstream fixture");
    String manager = "data/kaysaar/aotd/tot/scripts/trade/contracts/AoTDTradeContractManager";
    String contract = "data/kaysaar/aotd/tot/scripts/trade/contracts/AoTDTradeContract";
    ClassWriter managerWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    managerWriter.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, manager, null, "java/lang/Object", null);
    managerWriter.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "I", null, null).visitEnd();
    var add = managerWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "addContract", "(L" + contract + ";)V",
        null, null);
    add.visitFieldInsn(Opcodes.GETSTATIC, manager, "calls", "I");
    add.visitInsn(Opcodes.ICONST_1);
    add.visitInsn(Opcodes.IADD);
    add.visitFieldInsn(Opcodes.PUTSTATIC, manager, "calls", "I");
    add.visitInsn(Opcodes.RETURN);
    add.visitMaxs(0, 0);
    add.visitEnd();
    ClassWriter contractWriter = new ClassWriter(0);
    contractWriter.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, contract, null, "java/lang/Object", null);
    Map<String, byte[]> isolated = Map.of(TARGET.replace('/', '.'), original, manager.replace('/', '.'),
        managerWriter.toByteArray(), contract.replace('/', '.'), contractWriter.toByteArray());
    Class<?> type = new ForkClassLoader(isolated).loadClass(TARGET.replace('/', '.'));
    Object site = type.getConstructor().newInstance();
    try {
      type.getMethod("setAssignedWonder", String.class).invoke(site, "test-wonder");
      throw new AssertionError("fixture must throw its original null-market exception");
    } catch (java.lang.reflect.InvocationTargetException failure) {
      require(failure.getCause() instanceof NullPointerException,
          "optional dependency replaced original exception: " + failure);
    }
    require("test-wonder".equals(type.getField("assignedWonder").get(site)), "original assignment was not executed");
    require(type.getField("building").getBoolean(site), "original construction flag was not executed");
    int[] instantiated = {0};
    Object market = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),
        new Class<?>[]{com.fs.starfarer.api.campaign.econ.MarketAPI.class}, (proxy, call, args) -> {
          if (call.getName().equals("instantiateIndustry")) {
            require("normal-wonder".equals(args[0]), "wonder argument changed");
            instantiated[0]++;
            return null;
          }
          throw new AssertionError("Unexpected market call: " + call);
        });
    type.getField("market").set(site, market);
    type.getMethod("setAssignedWonder", String.class).invoke(site, "normal-wonder");
    require(instantiated[0] == 1, "original industry instantiation not preserved");
    require(type.getClassLoader().loadClass(manager.replace('/', '.')).getField("calls").getInt(null) == 1,
        "original contract registration not preserved");
  }

  private static void inspectPatched(byte[] bytes) {
    ClassNode node = read(bytes);
    FieldNode marker = field(node, MARKER);
    require(marker != null, "owned marker missing");
    require((marker.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
        | Opcodes.ACC_SYNTHETIC)) == (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
            | Opcodes.ACC_SYNTHETIC),
        "owned marker access mismatch");

    MethodNode wrapper = method(node, "setAssignedWonder", METHOD_DESC);
    MethodNode raw = method(node, AoTDForkCompatibilityTransformer.RAW_SET_ASSIGNED_WONDER, METHOD_DESC);
    require(wrapper != null && raw != null, "wrapper/raw pair missing");
    require((wrapper.access & Opcodes.ACC_PUBLIC) != 0, "public API wrapper lost public access");
    require(
        (raw.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) == (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC),
        "raw helper is not private synthetic");
    require(countCalls(wrapper, BRIDGE, "beforeMarketMutation", BEFORE_DESC) == 1,
        "wrapper before boundary count mismatch");
    require(countCalls(wrapper, BRIDGE, "afterMarketMutation", AFTER_DESC) == 2,
        "wrapper after/finally boundary count mismatch");
    require(countCalls(wrapper, TARGET, AoTDForkCompatibilityTransformer.RAW_SET_ASSIGNED_WONDER, METHOD_DESC) == 1,
        "wrapper raw invocation count mismatch");
    require(wrapper.tryCatchBlocks.size() == 1 && "java/lang/Throwable".equals(wrapper.tryCatchBlocks.get(0).type),
        "wrapper does not own a Throwable finally boundary");
    require(
        countCalls(raw, BRIDGE, "beforeMarketMutation", BEFORE_DESC) == 0
            && countCalls(raw, BRIDGE, "afterMarketMutation", AFTER_DESC) == 0,
        "raw helper contains a duplicated scheduler boundary");
    require(countFieldWrites(raw, TARGET, "building", "Z") == 1, "raw helper lost construction-state write");
    require(countFieldWrites(raw, TARGET, "assignedWonder", "Ljava/lang/String;") == 1,
        "raw helper lost assigned-wonder write");
  }

  private static byte[] changeBuildingAssignment(byte[] original, int replacementOpcode) {
    ClassNode node = read(original);
    MethodNode method = method(node, "setAssignedWonder", METHOD_DESC);
    require(method != null, "setAssignedWonder missing from future fixture");
    FieldInsnNode building = null;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && TARGET.equals(field.owner)
          && "building".equals(field.name) && "Z".equals(field.desc)) {
        require(building == null, "duplicate building write in fixture");
        building = field;
      }
    }
    require(building != null, "building write missing from fixture");
    AbstractInsnNode value = previousMeaningful(building);
    require(value != null && value.getOpcode() == Opcodes.ICONST_1, "unexpected original building value");
    method.instructions.set(value, new InsnNode(replacementOpcode));
    return write(node);
  }

  private static byte[] removeMarker(byte[] patched) {
    ClassNode node = read(patched);
    boolean removed = node.fields.removeIf(field -> MARKER.equals(field.name));
    require(removed, "marker missing from orphan-raw fixture");
    return write(node);
  }

  private static void verifyMethods(byte[] bytes) throws Exception {
    ClassNode node = read(bytes);
    for (MethodNode method : node.methods) {
      if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
      try {
        new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
      } catch (Throwable failure) {
        throw new AssertionError("BasicVerifier rejected " + node.name + "." + method.name + method.desc, failure);
      }
    }
  }

  private static Map<String, byte[]> readClasses(Path jarPath) throws Exception {
    Map<String, byte[]> classes = new HashMap<>();
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      var entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
        String binaryName =
            entry.getName().substring(0, entry.getName().length() - ".class".length()).replace('/', '.');
        try (var input = jar.getInputStream(entry)) {
          classes.put(binaryName, input.readAllBytes());
        }
      }
    }
    return classes;
  }

  private static void verifyExactClassLoads(Map<String, byte[]> originalClasses, byte[] patchedTarget)
      throws Exception {
    Map<String, byte[]> classes = new HashMap<>(originalClasses);
    String targetName = TARGET.replace('/', '.');
    classes.put(targetName, patchedTarget);
    Class<?> loaded = new ForkClassLoader(classes).loadClass(targetName);
    require(loaded.getDeclaredMethod("setAssignedWonder", String.class) != null,
        "patched public API method did not load");
    require(loaded.getDeclaredMethod(AoTDForkCompatibilityTransformer.RAW_SET_ASSIGNED_WONDER, String.class) != null,
        "patched raw helper did not load");
  }

  private static ClassNode read(byte[] bytes) {
    ClassNode node = new ClassNode(Opcodes.ASM8);
    new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
    return node;
  }

  private static byte[] write(ClassNode node) {
    ClassWriter writer = new ClassWriter(0);
    node.accept(writer);
    return writer.toByteArray();
  }

  private static MethodNode method(ClassNode node, String name, String desc) {
    MethodNode found = null;
    for (MethodNode method : node.methods) {
      if (!name.equals(method.name) || !desc.equals(method.desc)) continue;
      require(found == null, "duplicate method " + name + desc);
      found = method;
    }
    return found;
  }

  private static FieldNode field(ClassNode node, String name) {
    for (FieldNode field : node.fields) {
      if (name.equals(field.name)) return field;
    }
    return null;
  }

  private static int countCalls(MethodNode method, String owner, String name, String desc) {
    int count = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)
          && desc.equals(call.desc))
        count++;
    }
    return count;
  }

  private static int countFieldWrites(MethodNode method, String owner, String name, String desc) {
    int count = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && owner.equals(field.owner)
          && name.equals(field.name) && desc.equals(field.desc))
        count++;
    }
    return count;
  }

  private static AbstractInsnNode previousMeaningful(AbstractInsnNode node) {
    AbstractInsnNode current = node == null ? null : node.getPrevious();
    while (current != null && current.getOpcode() < 0) current = current.getPrevious();
    return current;
  }


  private static final class ForkClassLoader extends ClassLoader {
    private final Map<String, byte[]> classes;

    ForkClassLoader(Map<String, byte[]> classes) {
      super(AoTDForkCompatibilityTransformerTest.class.getClassLoader());
      this.classes = classes;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
          byte[] bytes = classes.get(name);
          if (bytes != null) loaded = defineClass(name, bytes, 0, bytes.length);
        }
        if (loaded == null) loaded = super.loadClass(name, false);
        if (resolve) resolveClass(loaded);
        return loaded;
      }
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
