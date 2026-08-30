package com.starsector.prepatcher.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class AoTDForkMarkerScannerTest {
    private AoTDForkMarkerScannerTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected AoTD mod root");
        Path sourceMod = Path.of(args[0]);
        Path temp = Files.createTempDirectory("aotd-marker-test-");
        try {
            Path mods = temp.resolve("mods");
            Path prepatcher = mods.resolve("StarsectorPrepatcher");
            Path aotd = mods.resolve("AoTD");
            Files.createDirectories(prepatcher);
            Files.createDirectories(aotd.resolve("jars"));
            Files.copy(sourceMod.resolve("mod_info.json"), aotd.resolve("mod_info.json"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(sourceMod.resolve("jars/AoTDToolboxTheory.jar"),
                    aotd.resolve("jars/AoTDToolboxTheory.jar"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.createDirectories(aotd.resolve(".build/diagnostic"));
            Files.copy(sourceMod.resolve("jars/AoTDToolboxTheory.jar"),
                    aotd.resolve(".build/diagnostic/AoTDToolboxTheory.jar"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(sourceMod.resolve("jars/AoTDToolboxTheory.jar"),
                    aotd.resolve("AoTDToolboxTheory-backup.jar"),
                    StandardCopyOption.REPLACE_EXISTING);

            AoTDForkMarkerScanner.Result result = AoTDForkMarkerScanner.scan(prepatcher);
            require(result.status() == AoTDForkMarkerScanner.Status.CANDIDATE_FOUND,
                    "expected candidate, got " + result);
            require(result.markerJar() != null, "marker JAR missing");
            require(result.markerJar().equals(
                            aotd.resolve("jars/AoTDToolboxTheory.jar")),
                    "scanner considered an undeclared diagnostic/archive JAR: " + result);

            Files.delete(aotd.resolve("jars/AoTDToolboxTheory.jar"));
            result = AoTDForkMarkerScanner.scan(prepatcher);
            require(result.status() == AoTDForkMarkerScanner.Status.NOT_FOUND,
                    "expected not found, got " + result);
            System.out.println("AoTD marker scanner test passed.");
        } finally {
            deleteRecursively(temp);
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(item -> {
                        try { Files.deleteIfExists(item); } catch (Exception ignored) {}
                    });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
