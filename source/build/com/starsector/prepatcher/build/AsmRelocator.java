package com.starsector.prepatcher.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/** Build-only relocator which gives the agent its own private ASM namespace. */
public final class AsmRelocator {
    private static final String PUBLIC_ASM = "org/objectweb/asm";
    private static final String PRIVATE_ASM =
            "com/starsector/prepatcher/internal/asm";

    private AsmRelocator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: AsmRelocator <output-directory> <input-directory>...");
        }
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        for (int index = 1; index < args.length; index++) {
            relocateTree(Path.of(args[index]), output);
        }
    }

    private static void relocateTree(Path input, Path output) throws IOException {
        try (Stream<Path> files = Files.walk(input)) {
            for (Path source : files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                Path relative = input.relativize(source);
                String unixName = relative.toString().replace('\\', '/');
                if (unixName.equals("module-info.class")
                        || unixName.startsWith("META-INF/versions/")
                        || unixName.startsWith("META-INF/")
                        || !unixName.endsWith(".class")) {
                    continue;
                }
                relocateClass(source, output);
            }
        }
    }

    private static void relocateClass(Path source, Path output) throws IOException {
        ClassReader reader = new ClassReader(Files.readAllBytes(source));
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassRemapper(writer, new PrivateAsmRemapper()), 0);
        byte[] relocated = writer.toByteArray();
        String relocatedName = new ClassReader(relocated).getClassName();
        Path target = output.resolve(relocatedName + ".class");
        Files.createDirectories(target.getParent());
        Files.copy(new java.io.ByteArrayInputStream(relocated), target,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static final class PrivateAsmRemapper extends Remapper {
        private PrivateAsmRemapper() {
            super(Opcodes.ASM9);
        }

        @Override
        public String map(String internalName) {
            if (internalName.equals(PUBLIC_ASM)
                    || internalName.startsWith(PUBLIC_ASM + "/")) {
                return PRIVATE_ASM + internalName.substring(PUBLIC_ASM.length());
            }
            return internalName;
        }

        @Override
        public Object mapValue(Object value) {
            if (value instanceof String text) {
                return text.replace(PUBLIC_ASM, PRIVATE_ASM)
                        .replace("org.objectweb.asm",
                                "com.starsector.prepatcher.internal.asm");
            }
            return super.mapValue(value);
        }
    }
}
