package com.starsector.prepatcher.agent.aotd;

import static com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_DECLARED_CAPABILITIES;
import static com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_FORK_VERSION;

import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/** Byte-resource proof; never loads or initializes an optional mod class. */
final class AoTDForkClassOwnership {
  private static final String CONTRACT = "data/kaysaar/aotd/tot/compat/PrepatcherContract";

  private AoTDForkClassOwnership() {}

  static boolean matches(ClassLoader loader, ProtectionDomain domain) throws Exception {
    if (loader == null || domain == null || domain.getCodeSource() == null
        || domain.getCodeSource().getLocation() == null)
      return false;
    URL origin = domain.getCodeSource().getLocation();
    // A same-named upstream class must not borrow a marker/bridge from another JAR,
    // including a disabled fork or a parent loader's resources.
    if (ownedResource(loader, origin, AoTDForkCompatibilityTransformer.TARGET) == null) return false;
    URL contractResource = ownedResource(loader, origin, CONTRACT);
    URL bridgeResource = ownedResource(loader, origin, AoTDSchedulerBridge.TARGET);
    URL stateResource = ownedResource(loader, origin, AoTDSchedulerBridge.TARGET + "$State");
    if (contractResource == null || bridgeResource == null || stateResource == null) return false;

    ClassNode contract = read(contractResource);
    if (!CONTRACT.equals(contract.name) || !constant(contract, "MOD_ID", "Ljava/lang/String;", "aotd_theory_of_toolbox")
        || !constant(contract, "FORK_VERSION", "Ljava/lang/String;", AOTD_CURRENT_FORK_VERSION)
        || !constant(contract, "DECLARED_CAPABILITIES", "J", AOTD_CURRENT_DECLARED_CAPABILITIES))
      return false;
    ClassNode bridge = read(bridgeResource);
    if (!AoTDSchedulerBridge.TARGET.equals(bridge.name)
        || !constant(bridge, "BRIDGE_SCHEMA", "I", AoTDSchedulerBridgeTransformer.CURRENT_BRIDGE_SCHEMA)
        || !constant(bridge, "BRIDGE_MARKER", "Ljava/lang/String;",
            AoTDSchedulerBridgeTransformer.CURRENT_BRIDGE_MARKER))
      return false;
    // Reuse the transformation's ABI matcher, including callbacks and the local
    // after-mutation handoff. Registration/activation still belongs to runtime.
    new AoTDSchedulerBridge().match(bridge);
    return true;
  }

  private static URL ownedResource(ClassLoader loader, URL origin, String name) throws Exception {
    String entry = name + ".class";
    URL resource = loader.getResource(entry);
    if (resource == null) return null;
    URLConnection connection = resource.openConnection();
    connection.setUseCaches(false);
    if (connection instanceof JarURLConnection jar) {
      return entry.equals(jar.getEntryName()) && sameLocation(origin, jar.getJarFileURL()) ? resource : null;
    }
    if ("file".equals(origin.getProtocol()) && "file".equals(resource.getProtocol())) {
      Path expected = Path.of(origin.toURI()).resolve(entry).normalize();
      return expected.equals(Path.of(resource.toURI()).normalize()) ? resource : null;
    }
    return null;
  }

  private static boolean sameLocation(URL left, URL right) throws Exception {
    if ("file".equals(left.getProtocol()) && "file".equals(right.getProtocol())) {
      return Path.of(left.toURI()).normalize().equals(Path.of(right.toURI()).normalize());
    }
    return left.toURI().normalize().equals(right.toURI().normalize());
  }

  private static ClassNode read(URL resource) throws Exception {
    URLConnection connection = resource.openConnection();
    connection.setUseCaches(false);
    try (InputStream input = connection.getInputStream()) {
      ClassNode node = new ClassNode();
      new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      return node;
    }
  }

  private static boolean constant(ClassNode node, String name, String descriptor, Object value) {
    FieldNode found = null;
    for (FieldNode field : node.fields) {
      if (!name.equals(field.name)) continue;
      if (found != null) return false;
      found = field;
    }
    int required = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
    return found != null && (found.access & required) == required && descriptor.equals(found.desc)
        && value.equals(found.value);
  }
}
