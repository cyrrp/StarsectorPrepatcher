package com.starsector.prepatcher.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Early, side-effect-free discovery of an AoTD fork candidate.
 *
 * <p>The scan is intentionally diagnostic only: a directory can exist while the
 * mod is disabled. Runtime capabilities become active solely after the loaded
 * fork performs the ABI handshake.</p>
 */
final class AoTDForkMarkerScanner {
    private static final String MOD_ID = "aotd_theory_of_toolbox";
    private static final String MARKER_ENTRY =
            "data/kaysaar/aotd/tot/compat/PrepatcherContract.class";
    private static final Pattern JARS_ARRAY = Pattern.compile(
            "\\\"jars\\\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern JSON_STRING = Pattern.compile(
            "\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");

    private AoTDForkMarkerScanner() {}

    static Result scan(Path prepatcherModRoot) {
        if (prepatcherModRoot == null || prepatcherModRoot.getParent() == null) {
            return new Result(Status.UNAVAILABLE, null, null, "mods root is unavailable");
        }
        Path modsRoot = prepatcherModRoot.getParent().toAbsolutePath().normalize();
        if (!Files.isDirectory(modsRoot)) {
            return new Result(Status.UNAVAILABLE, null, null,
                    "mods root is not a directory: " + modsRoot);
        }

        List<Candidate> markerCandidates = new ArrayList<>();
        try (var directories = Files.list(modsRoot)) {
            directories.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(directory -> inspectDirectory(directory, markerCandidates));
        } catch (IOException ex) {
            return new Result(Status.ERROR, null, null,
                    ex.getClass().getSimpleName() + ": " + safeMessage(ex));
        }

        if (markerCandidates.isEmpty()) {
            return new Result(Status.NOT_FOUND, null, null,
                    "no AoTD native contract marker found");
        }
        if (markerCandidates.size() > 1) {
            StringBuilder detail = new StringBuilder("multiple marker candidates: ");
            for (int i = 0; i < markerCandidates.size(); i++) {
                if (i > 0) detail.append(", ");
                detail.append(markerCandidates.get(i).jar());
            }
            return new Result(Status.AMBIGUOUS, null, null, detail.toString());
        }
        Candidate candidate = markerCandidates.get(0);
        return new Result(Status.CANDIDATE_FOUND, candidate.directory(), candidate.jar(),
                "marker found; runtime handshake is still required");
    }

    private static void inspectDirectory(Path directory, List<Candidate> result) {
        Path modInfo = directory.resolve("mod_info.json");
        if (!Files.isRegularFile(modInfo)) return;
        String json;
        try {
            json = Files.readString(modInfo, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return;
        }
        if (!containsModId(json, MOD_ID)) return;

        for (String declared : declaredJars(json)) {
            Path jar = directory.resolve(declared).normalize();
            if (!jar.startsWith(directory) || !Files.isReadable(jar)
                    || !Files.isRegularFile(jar)
                    || !jar.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                    .endsWith(".jar")) {
                continue;
            }
            try (JarFile file = new JarFile(jar.toFile())) {
                if (file.getJarEntry(MARKER_ENTRY) != null) {
                    result.add(new Candidate(directory, jar));
                }
            } catch (IOException ignored) {
                // A malformed declared JAR cannot make startup fail.
            }
        }
    }

    private static Set<String> declaredJars(String json) {
        Matcher array = JARS_ARRAY.matcher(json);
        if (!array.find()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        Matcher strings = JSON_STRING.matcher(array.group(1));
        while (strings.find()) {
            String value = unescapeJsonString(strings.group(1));
            if (!value.isBlank()) result.add(value.replace('\\', '/'));
        }
        return result;
    }

    private static String unescapeJsonString(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!escaped) {
                if (current == '\\') escaped = true;
                else result.append(current);
                continue;
            }
            escaped = false;
            result.append(switch (current) {
                case '\\', '/', '"' -> current;
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                default -> current;
            });
        }
        if (escaped) result.append('\\');
        return result.toString();
    }

    private static boolean containsModId(String json, String expected) {
        int index = 0;
        while ((index = json.indexOf("\"id\"", index)) >= 0) {
            int colon = json.indexOf(':', index + 4);
            if (colon < 0) return false;
            int firstQuote = json.indexOf('"', colon + 1);
            if (firstQuote < 0) return false;
            int secondQuote = json.indexOf('"', firstQuote + 1);
            if (secondQuote < 0) return false;
            if (expected.equals(json.substring(firstQuote + 1, secondQuote))) return true;
            index = secondQuote + 1;
        }
        return false;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? "no message" : message;
    }

    enum Status {
        CANDIDATE_FOUND,
        NOT_FOUND,
        AMBIGUOUS,
        UNAVAILABLE,
        ERROR
    }

    record Result(Status status, Path modDirectory, Path markerJar, String detail) {}
    private record Candidate(Path directory, Path jar) {}
}
