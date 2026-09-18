package com.fs.starfarer.api;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import sun.misc.Unsafe;

/** Reflection must not turn an unrelated unresolved mod signature into a gameplay crash. */
public final class OptionalModProbeLinkageTest {
  public static void main(String[] args) throws Exception {
    Class<?> commodity = fixture("data/kaysaar/aotd/tot/scripts/commoditydata/AoTDCommodityMarketData",
        "com/fs/starfarer/campaign/econ/reach/CommodityMarketData");
    proveUnresolvedSignature(commodity, "getMarketSharePercentPerFaction");
    require(!StarsectorPrepatcherMarketShareRuntime.isEligibleClass(commodity), "broken market-share type admitted");

    Class<?> industry =
        fixture("fixture/OptionalIndustry", "com/fs/starfarer/api/impl/campaign/econ/impl/BaseIndustry");
    proveUnresolvedSignature(industry, "advance", float.class);
    var unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    Object instance = ((Unsafe) unsafeField.get(null)).allocateInstance(industry);
    require(!StarsectorPrepatcherMarketScheduler.isBaseIndustryDormantFastPathEligible(instance),
        "broken industry type admitted to dormant fast path");

    Class<?> entity = fixture("fixture/OptionalEntity", "java/lang/Object");
    proveUnresolvedSignature(entity, "equals", Object.class);
    require(StarsectorPrepatcherScratch.CUSTOM_EQUALITY.get(entity), "unknown equality assumed identity-safe");
    System.out.println(
        "OK optional-mod reflective probes: unresolved-signature market-share/dormant/equality preserved-path");
  }

  private static Class<?> fixture(String name, String parent) {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, parent, null);
    var method =
        writer.visitMethod(Opcodes.ACC_PUBLIC, "unrelatedOptionalMethod", "()Lfixture/MissingOptional;", null, null);
    method.visitInsn(Opcodes.ACONST_NULL);
    method.visitInsn(Opcodes.ARETURN);
    method.visitMaxs(0, 0);
    method.visitEnd();
    byte[] bytes = writer.toByteArray();
    return new ClassLoader(OptionalModProbeLinkageTest.class.getClassLoader()) {
      Class<?> define() {
        return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
      }
    }.define();
  }

  private static void proveUnresolvedSignature(Class<?> type, String method, Class<?>... arguments) throws Exception {
    try {
      type.getMethod(method, arguments);
      throw new AssertionError("fixture did not trigger lazy optional-signature resolution");
    } catch (NoClassDefFoundError expected) {
      require(expected.getMessage().contains("MissingOptional"), "unexpected missing dependency: " + expected);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
