package com.starsector.prepatcher.agent;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.campaign.CampaignEngineLayers;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.*;
import org.lwjgl.util.vector.Vector2f;
import org.objectweb.asm.*;

/** Executes real game method bodies in isolated fixtures; no game campaign or GL context is opened. */
public final class GameUpdateBehaviorTest implements Opcodes {
  private static final String MEMORY = "com/fs/starfarer/campaign/rules/Memory";
  private static final String TERRAIN = "com/fs/starfarer/api/impl/campaign/terrain/BaseTiledTerrain";
  private static final String ENGINE = "com/fs/starfarer/campaign/CampaignEngine";
  private static final String CLOCK = "com/fs/starfarer/campaign/CampaignClock";

  static void run(List<Path> jars) throws Exception {
    Map<String, byte[]> classes = new HashMap<>();
    for (Path path : jars) try (JarFile jar = new JarFile(path.toFile())) {
      for (JarEntry entry : Collections.list(jar.entries())) {
        String name = entry.getName();
        if (name.equals(TERRAIN + ".class") || name.startsWith(MEMORY) && name.endsWith(".class")) {
          classes.put(name.substring(0, name.length() - 6).replace('/', '.'), jar.getInputStream(entry).readAllBytes());
        }
      }
    }
    memory(classes);
    terrain(classes);
    System.out.println(
        "OK game-update behavior: real Memory empty/paused/expiry/requirements differential; terrain rectangular range/comparison/assignment");
  }

  private static PrepatcherConfig config() throws Exception {
    Constructor<PrepatcherConfig> constructor = PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
    constructor.setAccessible(true);
    Properties properties = new Properties();
    properties.setProperty("patch.terrainRandomReuse", "false");
    return constructor.newInstance(properties);
  }

  private static void memory(Map<String, byte[]> originals) throws Exception {
    byte[] patched = new PrepatcherTransformer(config()).transform(null, MEMORY, null, null,
        originals.get(MEMORY.replace('/', '.')));
    require(patched != null, "Memory fixture was not patched");
    for (int scenario = 0; scenario < 5; scenario++) {
      String raw = memoryResult(originals, originals.get(MEMORY.replace('/', '.')), scenario);
      String optimized = memoryResult(originals, patched, scenario);
      require(raw.equals(optimized),
          "Memory behavior changed in scenario " + scenario + ": " + raw + " / " + optimized);
    }
  }

  private static String memoryResult(Map<String, byte[]> originals, byte[] bytes, int scenario) throws Exception {
    Map<String, byte[]> definitions = new HashMap<>(originals);
    definitions.put(MEMORY.replace('/', '.'), bytes);
    definitions.put(ENGINE.replace('/', '.'), engine());
    definitions.put(CLOCK.replace('/', '.'), clock());
    Loader loader = new Loader(definitions);
    Class<?> type = loader.loadClass(MEMORY.replace('/', '.'));
    Object instance = type.getConstructor().newInstance();
    if (scenario != 0) field(type, "restored").setBoolean(instance, true);
    Method set = type.getMethod("set", String.class, Object.class);
    if (scenario >= 1) set.invoke(instance, "$stable", "value");
    if (scenario >= 2)
      type.getMethod("set", String.class, Object.class, float.class).invoke(instance, "$timed", "timed", .1f);
    if (scenario >= 3) {
      set.invoke(instance, "$dependent", "dependent");
      type.getMethod("addRequired", String.class, String.class).invoke(instance, "$dependent", "$missing");
    }
    loader.loadClass(ENGINE.replace('/', '.')).getField("paused").setBoolean(null, scenario == 4);
    type.getMethod("advance", float.class).invoke(instance, .2f);
    Map<?, ?> data = (Map<?, ?>) field(type, "data").get(instance);
    Map<?, ?> requirements = (Map<?, ?>) field(type, "require").get(instance);
    return data + "/expire=" + type.getMethod("getExpire", String.class).invoke(instance, "$timed") + "/require="
        + requirements.keySet() + "/restored=" + field(type, "restored").getBoolean(instance);
  }

  private static void terrain(Map<String, byte[]> originals) throws Exception {
    byte[] patched = new PrepatcherTransformer(config()).transform(null, TERRAIN, null, null,
        originals.get(TERRAIN.replace('/', '.')));
    require(patched != null, "Terrain fixture was not patched");
    Map<String, byte[]> definitions = new HashMap<>();
    definitions.put(TERRAIN.replace('/', '.'), patched);
    definitions.put("fixture.TiledTerrain", terrainSubclass());
    ClassWriter gl = new ClassWriter(0);
    gl.visit(V17, ACC_PUBLIC, "org/lwjgl/opengl/GL11", null, "java/lang/Object", null);
    MethodVisitor noop = gl.visitMethod(ACC_PUBLIC | ACC_STATIC, "glEnable", "(I)V", null, null);
    noop.visitCode();
    noop.visitInsn(RETURN);
    noop.visitMaxs(0, 1);
    noop.visitEnd();
    gl.visitEnd();
    definitions.put("org.lwjgl.opengl.GL11", gl.toByteArray());
    Class<?> type = new Loader(definitions).loadClass("fixture.TiledTerrain");
    Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
    SectorAPI previous = Global.getSector();
    try {
      for (int[] dimensions : new int[][]{{3, 9}, {9, 3}, {5, 5}}) {
        for (float height : new float[]{100f, 5000f}) {
          Object instance = unsafe.allocateInstance(type);
          field(type, "tiles").set(instance, new int[dimensions[0]][dimensions[1]]);
          field(type, "entity").set(instance, proxy(SectorEntityToken.class,
              (p, m, a) -> m.getName().equals("getLocation") ? new Vector2f() : zero(m.getReturnType())));
          field(type, "texture").set(instance, proxy(SpriteAPI.class, (p, m, a) -> zero(m.getReturnType())));
          ViewportAPI viewport = proxy(ViewportAPI.class, (p, m, a) -> switch (m.getName()) {
            case "getLLX" -> -dimensions[0] * 50f - 50f;
            case "getLLY" -> -dimensions[1] * 50f - 50f;
            case "getVisibleWidth" -> 1500f;
            case "getVisibleHeight" -> height;
            case "getAlphaMult" -> 1f;
            default -> zero(m.getReturnType());
          });
          Global.setSector(proxy(SectorAPI.class,
              (p, m, a) -> m.getName().equals("getViewport") ? viewport : zero(m.getReturnType())));
          int end = Math.min(dimensions[1] - 1, (int) ((height + 50f) / 100f) + 1);
          type.getField("capturedYEnd").setFloat(instance, Float.NaN);
          type.getMethod("render", CampaignEngineLayers.class, ViewportAPI.class).invoke(instance, null, viewport);
          require(type.getField("capturedYEnd").getFloat(instance) == end, "render used wrong rectangular yEnd/clamp");
          Method visible = type.getMethod("isTileVisible", int.class, int.class);
          for (int x = 0; x < dimensions[0]; x++) for (int y = 0; y < dimensions[1]; y++) {
            require((boolean) visible.invoke(instance, x, y) == (y <= end),
                "isTileVisible differs from rectangular viewport bounds");
          }
        }
      }
    } finally {
      Global.setSector(previous);
    }
  }

  private static byte[] terrainSubclass() {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(V17, ACC_PUBLIC, "fixture/TiledTerrain", null, TERRAIN, null);
    writer.visitField(ACC_PUBLIC, "capturedYEnd", "F", null, null).visitEnd();
    for (String name : List.of("getTileSize", "getTileRenderSize")) {
      MethodVisitor method = writer.visitMethod(ACC_PUBLIC, name, "()F", null, null);
      method.visitCode();
      method.visitLdcInsn(100f);
      method.visitInsn(FRETURN);
      method.visitMaxs(1, 1);
      method.visitEnd();
    }
    MethodVisitor pre = writer.visitMethod(ACC_PUBLIC, "preRender",
        "(Lcom/fs/starfarer/api/campaign/CampaignEngineLayers;F)V", null, null);
    pre.visitCode();
    pre.visitInsn(RETURN);
    pre.visitMaxs(0, 3);
    pre.visitEnd();
    MethodVisitor render = writer.visitMethod(ACC_PROTECTED, "renderSubArea", "(FFFFFIF)V", null, null);
    render.visitCode();
    render.visitVarInsn(ALOAD, 0);
    render.visitVarInsn(FLOAD, 4);
    render.visitFieldInsn(PUTFIELD, "fixture/TiledTerrain", "capturedYEnd", "F");
    render.visitInsn(RETURN);
    render.visitMaxs(2, 8);
    render.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  private static byte[] engine() {
    ClassWriter writer = basic(ENGINE);
    writer.visitField(ACC_PUBLIC | ACC_STATIC, "paused", "Z", null, null).visitEnd();
    factory(writer, "getInstance", ENGINE, true);
    factory(writer, "getClock", CLOCK, false);
    MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "isPaused", "()Z", null, null);
    method.visitCode();
    method.visitFieldInsn(GETSTATIC, ENGINE, "paused", "Z");
    method.visitInsn(IRETURN);
    method.visitMaxs(1, 1);
    method.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  private static byte[] clock() {
    ClassWriter writer = basic(CLOCK);
    MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "convertToDays", "(F)F", null, null);
    method.visitCode();
    method.visitVarInsn(FLOAD, 1);
    method.visitInsn(FRETURN);
    method.visitMaxs(1, 2);
    method.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  private static ClassWriter basic(String name) {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
    MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
    init.visitCode();
    init.visitVarInsn(ALOAD, 0);
    init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    init.visitInsn(RETURN);
    init.visitMaxs(1, 1);
    init.visitEnd();
    return writer;
  }

  private static void factory(ClassWriter writer, String name, String target, boolean isStatic) {
    MethodVisitor method =
        writer.visitMethod(ACC_PUBLIC | (isStatic ? ACC_STATIC : 0), name, "()L" + target + ";", null, null);
    method.visitCode();
    method.visitTypeInsn(NEW, target);
    method.visitInsn(DUP);
    method.visitMethodInsn(INVOKESPECIAL, target, "<init>", "()V", false);
    method.visitInsn(ARETURN);
    method.visitMaxs(2, isStatic ? 0 : 1);
    method.visitEnd();
  }

  private static Field field(Class<?> type, String name) throws Exception {
    for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
      try {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException ignored) {}
    }
    throw new NoSuchFieldException(name);
  }
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
  }
  private static Object zero(Class<?> type) {
    if (type == boolean.class) return false;
    if (type == float.class) return 0f;
    if (type == int.class) return 0;
    return null;
  }
  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  private static final class Loader extends ClassLoader {
    private final Map<String, byte[]> definitions;
    Loader(Map<String, byte[]> definitions) {
      super(GameUpdateBehaviorTest.class.getClassLoader());
      this.definitions = definitions;
    }
    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      Class<?> type = findLoadedClass(name);
      if (type == null) {
        byte[] bytes = definitions.get(name);
        type = bytes == null ? super.loadClass(name, false) : defineClass(name, bytes, 0, bytes.length);
      }
      if (resolve) resolveClass(type);
      return type;
    }
  }
}
