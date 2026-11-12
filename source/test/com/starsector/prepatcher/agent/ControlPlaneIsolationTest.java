package com.starsector.prepatcher.agent;

import java.lang.instrument.ClassFileTransformer;
import java.net.URI;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/** A parent-loaded control class must be rejected before diagnostics initialization or ASM parsing. */
public final class ControlPlaneIsolationTest {
  public static void main(String[] args) throws Exception {
    AtomicInteger calls = new AtomicInteger();
    ClassFileTransformer stage = new ClassFileTransformer() {
      public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
        calls.incrementAndGet(); return null;
      }
    };
    ClassLoader control = OrderedTransformerPipeline.class.getClassLoader();
    ClassLoader runtime = new ClassLoader(control) {};
    OrderedTransformerPipeline pipeline = new OrderedTransformerPipeline(
        List.of(new OrderedTransformerPipeline.Stage("control boundary", stage)), runtime, true, false);
    String name = "com/starsector/prepatcher/agent/TransformDiagnostics$Transaction";
    byte[] invalid = {0};
    if (pipeline.transform(control, name, null, OrderedTransformerPipeline.class.getProtectionDomain(), invalid) != null
        || calls.get() != 0 || System.getProperty("starsector.prepatcher.pipelineFailure." + name.replace('/', '.')) != null) {
      throw new AssertionError("own control class reached parsing/diagnostics/stages");
    }
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/ControlBoundary", null, "java/lang/Object", null);
    writer.visitEnd();
    byte[] valid = writer.toByteArray();
    ProtectionDomain otherJar = new ProtectionDomain(new CodeSource(URI.create("file:/fixture-mod.jar").toURL(), (Certificate[]) null), null);
    pipeline.transform(control, "fixture/ControlBoundary", null, otherJar, valid);
    pipeline.transform(control, "fixture/ControlBoundary", null, new ProtectionDomain(null, null), valid);
    if (calls.get() != 2) throw new AssertionError("same-loader foreign or unknown sources lost their patch checks");
    System.out.println("OK control-plane ownership isolation: own JAR rejected before parsing; same-loader foreign/unknown sources retained");
  }
}
