package com.starsector.prepatcher.agent;

import com.starsector.prepatcher.namespace.GameNamespaceDetector;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public final class GameNamespaceSelectionTest {
    private static final String OWNER = "com/fs/starfarer/combat/CombatEngine";
    private static final String PREFIX = "com/starsector/prepatcher/agent/";

    public static void main(String[] args) throws Exception {
        GameNamespace.forRuntimeInstallation();
        String actual = System.getProperty("starsector.prepatcher.gameNamespace");
        if (!args[0].equals(actual)) throw new AssertionError("Expected " + args[0] + ", selected " + actual);
        try (JarFile jar = new JarFile(args[1])) {
            Set<String> files = jar.stream().map(JarEntry::getName).filter(n -> n.endsWith(".tsv"))
                    .collect(Collectors.toSet());
            Set<String> expected = GameNamespaceDetector.TABLES.stream().map(id -> PREFIX + id + ".tsv")
                    .collect(Collectors.toSet());
            if (!files.equals(expected)) throw new AssertionError("Unexpected namespace table inventory: " + files);
            GameNamespaceDetector.TableSource tables = id -> jar.getInputStream(jar.getJarEntry(PREFIX + id + ".tsv"));
            check(tables, "windows-0.98a-RC8", OWNER, "Object/String");
            check(tables, "linux-0.98a-RC8", OWNER, "Object/E");
            check(tables, "windows-0.98.5a-RC8", OWNER, "OoOO/D");
            check(tables, "linux-0.98.5a-RC8", OWNER, "B/D");
            check(tables, null, OWNER, "Unrecognized");
            check(tables, null, OWNER, "Object/E", "B/D");
            check(tables, null, OWNER);
            check(tables, null, "foreign/CombatEngine", "Object/String");
            check(id -> tables.open("linux-0.98a-RC8"), null, OWNER, "Object/E");
        }
        System.out.println("OK namespace selection " + actual + ", shipped tables, ambiguous and unknown probes");
    }

    private static void check(GameNamespaceDetector.TableSource tables, String expected, String owner,
            String... results) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, owner, null, "java/lang/Object", null);
        for (String result : results) {
            writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getResults",
                    "()Lcom/fs/starfarer/combat/" + result + ";", null, null).visitEnd();
        }
        writer.visitEnd();
        String actual;
        try {
            actual = GameNamespaceDetector.detect(new ByteArrayInputStream(writer.toByteArray()), tables);
        } catch (IOException failure) {
            if (expected == null) return;
            throw failure;
        }
        if (!actual.equals(expected)) throw new AssertionError(List.of(results) + " unexpectedly selected " + actual);
    }
}
