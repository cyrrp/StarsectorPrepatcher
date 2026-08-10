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
            Path ignoredBuildJar = aotd.resolve(".build/audit/AoTDToolboxTheory-test.jar");
            Path ignoredReleaseJar = aotd.resolve("releases/staging/AoTDToolboxTheory.jar");
            Files.createDirectories(ignoredBuildJar.getParent());
            Files.createDirectories(ignoredReleaseJar.getParent());
            Files.copy(sourceMod.resolve("jars/AoTDToolboxTheory.jar"), ignoredBuildJar,
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(sourceMod.resolve("jars/AoTDToolboxTheory.jar"), ignoredReleaseJar,
                    StandardCopyOption.REPLACE_EXISTING);

            AoTDForkMarkerScanner.Result result = AoTDForkMarkerScanner.scan(prepatcher);
            require(result.status() == AoTDForkMarkerScanner.Status.CANDIDATE_FOUND,
                    "expected candidate, got " + result);
            require(aotd.resolve("jars/AoTDToolboxTheory.jar").equals(result.markerJar()),
                    "diagnostic/release duplicate displaced the installed marker: " + result);

            Files.delete(aotd.resolve("jars/AoTDToolboxTheory.jar"));
            result = AoTDForkMarkerScanner.scan(prepatcher);
            require(result.status() == AoTDForkMarkerScanner.Status.NOT_FOUND,
                    "ignored build/release marker became a candidate: " + result);
            System.out.println("AoTD marker scanner test passed: installed candidate only; "
                    + ".build/releases ignored.");
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
