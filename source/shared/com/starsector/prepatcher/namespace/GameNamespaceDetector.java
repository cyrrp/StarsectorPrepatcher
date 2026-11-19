package com.starsector.prepatcher.namespace;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/** Shared by the offline builder and the agent before it defines game-loader payloads. */
public final class GameNamespaceDetector {
    private static final String OWNER = "com/fs/starfarer/combat/CombatEngine";
    private static final String RESULT = "com/fs/starfarer/combat/Object/String";
    public static final String PROBE = OWNER + ".class";
    public static final String IDENTITY = "windows-0.98a-RC8";
    public static final List<String> TABLES = List.of("linux-0.98a-RC8", "windows-0.98.5a-RC8", "linux-0.98.5a-RC8");

    private GameNamespaceDetector() {}

    @FunctionalInterface
    public interface TableSource {
        InputStream open(String id) throws IOException;
    }

    public static String detect(InputStream probe, TableSource tables) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(probe).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!OWNER.equals(node.name)) throw new IOException("Wrong namespace probe class: " + node.name);
        List<String> getters = node.methods.stream()
                .filter(m -> m.name.equals("getResults") && m.desc.startsWith("()"))
                .map(m -> m.desc).toList();
        if (getters.size() != 1) throw new IOException("Expected one CombatEngine.getResults() declaration: " + getters);
        String descriptor = getters.get(0);
        List<String> matches = new ArrayList<>();
        if (descriptor.equals("()L" + RESULT + ";")) matches.add(IDENTITY);
        for (String id : TABLES) {
            if (descriptor.equals(resultDescriptor(tables, id))) matches.add(id);
        }
        if (matches.size() != 1) {
            throw new IOException("Unknown or ambiguous Starsector namespace for CombatEngine.getResults"
                    + descriptor + ": " + matches);
        }
        return matches.get(0);
    }

    private static String resultDescriptor(TableSource tables, String id) throws IOException {
        String prefix = "C\t" + RESULT + "\t";
        InputStream stream = tables.open(id);
        if (stream == null) throw new IOException("Missing namespace table: " + id);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith(prefix)) return "()L" + line.substring(prefix.length()) + ";";
            }
        }
        throw new IOException("Missing CombatEngine result type in namespace table: " + id);
    }
}
