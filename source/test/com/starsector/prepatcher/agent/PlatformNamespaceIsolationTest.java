package com.starsector.prepatcher.agent;

import java.lang.instrument.ClassFileTransformer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Platform ownership must be checked before any bytecode parser or patch stage. */
public final class PlatformNamespaceIsolationTest {
  public static void main(String[] args) {
    AtomicInteger calls = new AtomicInteger();
    ClassFileTransformer stage = new ClassFileTransformer() {
      @Override
      public byte[] transform(ClassLoader loader, String name, Class<?> type,
          java.security.ProtectionDomain domain, byte[] bytes) {
        calls.incrementAndGet();
        throw new AssertionError("Platform class reached a patch stage: " + name);
      }
    };
    OrderedTransformerPipeline pipeline = new OrderedTransformerPipeline(
        List.of(new OrderedTransformerPipeline.Stage("platform isolation", stage)),
        ClassLoader.getSystemClassLoader(), false, true);
    // Deliberately unparseable input proves that no ASM/version check is involved.
    byte[] bytes = {0};
    for (ClassLoader loader : new ClassLoader[] {null, ClassLoader.getPlatformClassLoader()}) {
      for (String name : List.of("org/xml/sax/XMLReader", "org/w3c/dom/Document", "future/platform/Class")) {
        if (pipeline.transform(loader, name, null, null, bytes) != null) {
          throw new AssertionError("Platform bytes changed: " + name);
        }
        if (System.getProperty("starsector.prepatcher.pipelineFailure." + name.replace('/', '.')) != null) {
          throw new AssertionError("Platform rejection was logged: " + name);
        }
      }
    }
    if (calls.get() != 0) throw new AssertionError("Platform stage calls: " + calls);
    System.out.println("OK platform namespace isolation: bootstrap/platform loaders, package-independent, no parsing");
  }
}
