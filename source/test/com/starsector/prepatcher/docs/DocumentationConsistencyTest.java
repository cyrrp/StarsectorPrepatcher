package com.starsector.prepatcher.docs;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** Repository-level regression test for the public documentation contract. */
public final class DocumentationConsistencyTest {
    private static final Pattern STRICT_VERSION =
            Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");
    private static final Pattern VERSION_PROPERTY = Pattern.compile("\\\"version\\\"\\s*:");
    private static final Pattern BRACKET_HEADING = Pattern.compile(
            "^##[ \\t]+\\[([^]\\r\\n]+)](?:[ \\t]+-[ \\t]+([0-9]{4}-[0-9]{2}-[0-9]{2}))?[ \\t]*$");
    private static final Pattern REFERENCE_LINK = Pattern.compile(
            "^[ \\t]{0,3}\\[[^]\\r\\n]+]:[ \\t]*(?:<([^>\\r\\n]+)>|([^\\s]+)).*$");
    private static final Pattern URI_SCHEME =
            Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");
    private static final Pattern JAVA_VERSION_CONSTANT = Pattern.compile(
            "public\\s+static\\s+final\\s+String\\s+VERSION\\s*=\\s*\"([^\"]+)\"\\s*;");
    private static final Pattern MANIFEST_VERSION = Pattern.compile(
            "Implementation-Version:\\s*([0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?)");
    private static final Pattern PATCH_SCHEMA = Pattern.compile(
            "PATCH_MARKER_VALUE_PREFIX\\s*=\\s*\"StarsectorPrepatcher:patch-v([1-9][0-9]*):\"");
    private static final Pattern PATCH_ID = Pattern.compile("`(patch\\.[A-Za-z0-9]+)`");
    private static final Pattern SHA256_ENTRY = Pattern.compile("^([0-9a-f]{64})  (.+)$");
    private static final Set<String> LEGACY_CANONICAL_DOC_NAMES = Set.of(
            "PATCH_VALIDATION_CHECKLIST.MD",
            "STRUCTURAL_COMPATIBILITY.MD",
            "TEST_REPORT.MD");
    private static final Set<String> CURRENT_DOC_ROOT_NAMES = Set.of(
            "PATCHES.MD",
            "COMPATIBILITY.MD",
            "VALIDATION.MD",
            "ROADMAP.MD");

    private DocumentationConsistencyTest() {}

    public static void main(String[] args) {
        try {
            run(args);
        } catch (AssertionError failure) {
            throw failure;
        } catch (Exception failure) {
            throw new AssertionError("documentation consistency check failed: "
                    + failure.getMessage(), failure);
        }
    }

    private static void run(String[] args) throws IOException {
        require(args.length == 1,
                "Usage: DocumentationConsistencyTest <mod-root>");

        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        require(Files.isDirectory(root), "mod root is not a directory: " + root);

        Path modInfo = requiredFile(root.resolve("mod_info.json"));
        Version currentVersion = readCurrentVersion(modInfo);
        Path changelog = requiredFile(root.resolve("CHANGELOG.md"));
        checkChangelog(changelog, currentVersion);
        checkVersionConsumers(root, currentVersion);
        checkKnownDisabledStartupPatches(root);
        checkAuditCadenceProfiles(root);
        checkDiagnosticProfileIsolation(root);
        checkReportLayout(root);
        checkChecksums(root);

        Set<Path> markdown = collectCurrentMarkdown(root);
        checkPortableDocumentation(markdown);
        checkForbiddenLayout(root);
        checkCurrentVersionSuffixes(markdown, changelog, modInfo, currentVersion);
        int patchCount = checkPatchValidationCoverage(root);

        LinkGraph graph = checkLinks(root, markdown);
        checkReachability(root, markdown, graph.edges);

        System.out.println("OK documentation-consistency version=" + currentVersion
                + " markdown=" + markdown.size()
                + " relativeLinks=" + graph.relativeLinkCount
                + " reachable=" + markdown.size()
                + " patchScenarios=" + patchCount);
    }

    private static void checkPortableDocumentation(Set<Path> markdown) throws IOException {
        for (Path path : markdown) {
            require(!readUtf8(path).contains("C:\\Games\\Starsector_test"),
                    "documentation contains a developer-local Starsector path: " + path);
        }
    }

    private static Version readCurrentVersion(Path modInfo) throws IOException {
        String json = readUtf8(modInfo);
        Matcher matcher = VERSION_PROPERTY.matcher(json);
        require(matcher.find(), "mod_info.json has no version property");
        int valueStart = skipWhitespace(json, matcher.end());
        require(!matcher.find(), "mod_info.json has more than one version property");
        require(valueStart < json.length(), "mod_info.json version value is missing");

        String value;
        char first = json.charAt(valueStart);
        if (first == '"') {
            ParsedString parsed = parseJsonString(json, valueStart, "mod_info.json version");
            value = parsed.value;
        } else if (first == '{') {
            Map<String, String> fields = parseVersionObject(json, valueStart);
            require(fields.keySet().equals(Set.of("major", "minor", "patch")),
                    "mod_info.json version object must contain only major, minor and patch: "
                            + fields.keySet());
            value = fields.get("major") + "." + fields.get("minor") + "." + fields.get("patch");
        } else {
            throw new AssertionError(
                    "mod_info.json version must be a strict x.y.z string or major/minor/patch object");
        }

        return Version.parse(value, "mod_info.json version");
    }

    private static Map<String, String> parseVersionObject(String json, int start) {
        Map<String, String> fields = new LinkedHashMap<>();
        int cursor = start + 1;
        while (true) {
            cursor = skipWhitespace(json, cursor);
            require(cursor < json.length(), "unterminated mod_info.json version object");
            if (json.charAt(cursor) == '}') return fields;

            require(json.charAt(cursor) == '"',
                    "version object key must be a JSON string at offset " + cursor);
            ParsedString key = parseJsonString(json, cursor, "version object key");
            cursor = skipWhitespace(json, key.end);
            require(cursor < json.length() && json.charAt(cursor) == ':',
                    "missing ':' after version object key " + key.value);
            cursor = skipWhitespace(json, cursor + 1);
            require(cursor < json.length(), "missing value for version field " + key.value);

            String value;
            if (json.charAt(cursor) == '"') {
                ParsedString parsed = parseJsonString(json, cursor,
                        "version field " + key.value);
                value = parsed.value;
                cursor = parsed.end;
            } else {
                int numberStart = cursor;
                while (cursor < json.length() && Character.isDigit(json.charAt(cursor))) cursor++;
                require(numberStart != cursor,
                        "version field " + key.value + " must be a string or non-negative integer");
                value = json.substring(numberStart, cursor);
            }

            require(fields.putIfAbsent(key.value, value) == null,
                    "duplicate version field in mod_info.json: " + key.value);
            cursor = skipWhitespace(json, cursor);
            require(cursor < json.length(), "unterminated mod_info.json version object");
            if (json.charAt(cursor) == '}') return fields;
            require(json.charAt(cursor) == ',',
                    "expected ',' in mod_info.json version object at offset " + cursor);
            cursor++;
        }
    }

    private static ParsedString parseJsonString(String text, int start, String description) {
        require(start < text.length() && text.charAt(start) == '"',
                description + " is not a JSON string");
        StringBuilder value = new StringBuilder();
        for (int cursor = start + 1; cursor < text.length(); cursor++) {
            char character = text.charAt(cursor);
            if (character == '"') return new ParsedString(value.toString(), cursor + 1);
            require(character >= 0x20, "control character in " + description);
            if (character != '\\') {
                value.append(character);
                continue;
            }

            require(++cursor < text.length(), "unterminated escape in " + description);
            char escaped = text.charAt(cursor);
            switch (escaped) {
                case '"': value.append('"'); break;
                case '\\': value.append('\\'); break;
                case '/': value.append('/'); break;
                case 'b': value.append('\b'); break;
                case 'f': value.append('\f'); break;
                case 'n': value.append('\n'); break;
                case 'r': value.append('\r'); break;
                case 't': value.append('\t'); break;
                case 'u':
                    require(cursor + 4 < text.length(), "short Unicode escape in " + description);
                    String digits = text.substring(cursor + 1, cursor + 5);
                    try {
                        value.append((char) Integer.parseInt(digits, 16));
                    } catch (NumberFormatException invalid) {
                        throw new AssertionError("invalid Unicode escape in " + description
                                + ": \\u" + digits, invalid);
                    }
                    cursor += 4;
                    break;
                default:
                    throw new AssertionError("invalid JSON escape in " + description
                            + ": \\" + escaped);
            }
        }
        throw new AssertionError("unterminated " + description);
    }

    private static void checkChangelog(Path changelog, Version currentVersion) throws IOException {
        List<ReleaseHeading> releases = new ArrayList<>();
        Set<Version> unique = new HashSet<>();
        int unreleasedCount = 0;
        int unreleasedLine = -1;

        String[] lines = readUtf8(changelog).split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = stripBom(lines[index], index);
            Matcher heading = BRACKET_HEADING.matcher(line);
            if (!heading.matches()) {
                require(!line.matches("^##[ \\t]+\\[.*"),
                        location(changelog, index + 1) + ": malformed changelog release heading");
                continue;
            }

            String label = heading.group(1);
            String date = heading.group(2);
            if (label.equals("Unreleased")) {
                unreleasedCount++;
                unreleasedLine = index + 1;
                require(date == null,
                        location(changelog, index + 1) + ": [Unreleased] must not have a date");
                continue;
            }

            Version version = Version.parse(label,
                    location(changelog, index + 1) + " release version");
            require(date != null,
                    location(changelog, index + 1) + ": released version must have YYYY-MM-DD date");
            try {
                LocalDate.parse(date);
            } catch (DateTimeParseException invalid) {
                throw new AssertionError(location(changelog, index + 1)
                        + ": invalid release date " + date, invalid);
            }
            require(unique.add(version),
                    location(changelog, index + 1) + ": duplicate release version " + version);
            releases.add(new ReleaseHeading(version, index + 1));
        }

        require(unreleasedCount == 1,
                "CHANGELOG.md must contain exactly one '## [Unreleased]' heading; found "
                        + unreleasedCount);
        require(!releases.isEmpty(), "CHANGELOG.md contains no released versions");
        require(unreleasedLine < releases.get(0).line,
                "CHANGELOG.md [Unreleased] must precede released versions");
        require(releases.get(0).version.equals(currentVersion),
                "first CHANGELOG.md release " + releases.get(0).version
                        + " does not match mod_info.json version " + currentVersion);

        for (int index = 1; index < releases.size(); index++) {
            Version newer = releases.get(index - 1).version;
            Version older = releases.get(index).version;
            require(newer.compareTo(older) > 0,
                    "CHANGELOG.md releases are not in strictly descending order: "
                            + newer + " before " + older);
        }
    }

    private static void checkVersionConsumers(Path root, Version currentVersion) throws IOException {
        String expected = currentVersion.toString();
        String modInfo = readUtf8(requiredFile(root.resolve("mod_info.json")));
        require(modInfo.contains("\"id\": \"starsector_prepatcher\"")
                        && modInfo.contains("\"name\": \"StarsectorPrepatcher\"")
                        && modInfo.contains("jars/StarsectorPrepatcherBootstrap.jar")
                        && modInfo.contains("com.starsector.prepatcher.bootstrap.PrepatcherBootstrapPlugin"),
                "mod_info.json does not expose the StarsectorPrepatcher public identity");
        for (String obsoletePath : List.of(
                "agent/StarsectorMapOptimizerAgent.jar",
                "agent/StarsectorHyperspaceOptimizerAgent.jar",
                "jars/StarsectorMapOptimizerBootstrap.jar",
                "optimizer.properties",
                "hyperspace-optimizer.properties",
                "hyperspace-prepatcher.properties",
                "agent/StarsectorPrepatcherHyperspaceAgent.jar",
                "source/agent/com/starsector/mapoptimizer",
                "source/bootstrap/com/starsector/mapoptimizer",
                "source/hyperspace/com/starsector/mapoptimizer",
                "source/hyperspace/com/starsector/prepatcher",
                "source/test/com/starsector/mapoptimizer")) {
            require(!Files.exists(root.resolve(obsoletePath)),
                    "obsolete release path remains in StarsectorPrepatcher: " + obsoletePath);
        }
        Path releaseReport = requiredFile(root.resolve("docs/releases/" + expected + ".md"));
        String releaseHeading = readUtf8(releaseReport).split("\\R", 2)[0];
        require(releaseHeading.equals("# Отчёт о выпуске " + expected),
                "release report H1 does not match mod_info.json version: " + releaseHeading);
        for (String relative : List.of(
                "source/agent/com/starsector/prepatcher/agent/PrepatcherAgent.java")) {
            Path path = requiredFile(root.resolve(relative));
            Matcher matcher = JAVA_VERSION_CONSTANT.matcher(readUtf8(path));
            require(matcher.find(), "missing public VERSION constant: " + relative);
            require(matcher.group(1).equals(expected),
                    relative + " VERSION " + matcher.group(1)
                            + " does not match mod_info.json " + expected);
            require(!matcher.find(), "multiple public VERSION constants: " + relative);
        }

        for (String relative : List.of("build-agent.ps1", "build-agent.sh")) {
            Path path = requiredFile(root.resolve(relative));
            Matcher matcher = MANIFEST_VERSION.matcher(readUtf8(path));
            int count = 0;
            while (matcher.find()) {
                count++;
                require(matcher.group(1).equals(expected),
                        relative + " manifest version " + matcher.group(1)
                                + " does not match mod_info.json " + expected);
            }
            require(count == 2,
                    relative + " must define exactly two Implementation-Version values; found "
                            + count);
        }

        for (String relative : List.of(
                "agent/StarsectorPrepatcherAgent.jar",
                "jars/StarsectorPrepatcherBootstrap.jar")) {
            Path path = requiredFile(root.resolve(relative));
            try (JarFile jar = new JarFile(path.toFile())) {
                require(jar.getManifest() != null, relative + " has no manifest");
                String actual = jar.getManifest().getMainAttributes()
                        .getValue(Attributes.Name.IMPLEMENTATION_VERSION);
                require(expected.equals(actual),
                        relative + " manifest version " + actual
                                + " does not match mod_info.json " + expected);
            }
        }

        Path transformer = requiredFile(root.resolve(
                "source/agent/com/starsector/prepatcher/agent/PrepatcherTransformer.java"));
        Matcher patchSchema = PATCH_SCHEMA.matcher(readUtf8(transformer));
        require(patchSchema.find(),
                "transformer ownership marker must use a release-independent patch-vN schema");
        require(!patchSchema.find(), "transformer defines multiple patch marker schemas");

        String englishReadme = readUtf8(requiredFile(root.resolve("README.md")));
        String russianReadme = readUtf8(requiredFile(root.resolve("README_RU.md")));
        String languageHeader = "[English](README.md) | [Русский](README_RU.md)";
        require((englishReadme.startsWith("# StarsectorPrepatcher\n")
                        || englishReadme.startsWith("# StarsectorPrepatcher\r\n"))
                        && englishReadme.contains(languageHeader)
                        && englishReadme.contains("Current version: **" + expected + "**"),
                "README.md current version does not match mod_info.json " + expected);
        require((russianReadme.startsWith("# StarsectorPrepatcher\n")
                        || russianReadme.startsWith("# StarsectorPrepatcher\r\n"))
                        && russianReadme.contains(languageHeader)
                        && russianReadme.contains("Текущая версия: **" + expected + "**"),
                "README_RU.md identity/version does not match mod_info.json " + expected);
        for (String relative : List.of(
                "prepatcher.properties",
                "profiles/aggressive.properties",
                "profiles/debug.properties")) {
            String firstLine = readUtf8(requiredFile(root.resolve(relative))).split("\\R", 2)[0];
            require(firstLine.equals("# StarsectorPrepatcher " + expected),
                    relative + " version header does not match mod_info.json " + expected);
        }
    }

    private static void checkKnownDisabledStartupPatches(Path root) throws IOException {
        for (String relative : List.of(
                "prepatcher.properties",
                "profiles/safe.properties",
                "profiles/aggressive.properties",
                "profiles/debug.properties")) {
            String properties = readUtf8(requiredFile(root.resolve(relative)));
            requirePropertyValue(relative, properties, "patch.loadingTextReader", "false");
            requirePropertyValue(relative, properties, "patch.startupLogAggregation", "false");
        }
    }

    private static void checkAuditCadenceProfiles(Path root) throws IOException {
        Map<String, String> shared = Map.of(
                "economy.structureAuditMs", "5000",
                "market.structureAuditFrames", "600",
                "commodity.temporalAuditFrames", "300",
                "market.noOpIndustryAuditFrames", "600",
                "market.remote.constructionAuditBatches", "180",
                "coreWorlds.auditSystemsPerFrame", "64");
        for (String relative : List.of(
                "prepatcher.properties",
                "profiles/safe.properties",
                "profiles/aggressive.properties",
                "profiles/debug.properties")) {
            String properties = readUtf8(requiredFile(root.resolve(relative)));
            for (Map.Entry<String, String> entry : shared.entrySet()) {
                requirePropertyValue(relative, properties, entry.getKey(), entry.getValue());
            }
        }

        for (String relative : List.of(
                "prepatcher.properties",
                "profiles/aggressive.properties",
                "profiles/debug.properties")) {
            String properties = readUtf8(requiredFile(root.resolve(relative)));
            requirePropertyValue(relative, properties,
                    "market.scheduler.policyAuditBatches", "300");
        }
        requireNoProperty("profiles/safe.properties",
                readUtf8(requiredFile(root.resolve("profiles/safe.properties"))),
                "market.scheduler.policyAuditBatches");
    }

    private static void checkDiagnosticProfileIsolation(Path root) throws IOException {
        List<String> debugOnlyKeys = List.of(
                "observer.marketAdvanceSemanticRisks",
                "observer.marketConstructionDiagnostics",
                "observer.marketConstructionDiagnosticsMaxSamplesPerReason",
                "patch.directMarketObservation",
                "directMarket.timingSampleEvery",
                "directMarket.stackSampleEvery",
                "directMarket.maxStacksPerSite",
                "directMarket.reportIntervalSeconds",
                "directMarket.maxSites",
                "directMarket.unknownStackSamples",
                "fastForward.verbose",
                "fastForward.metrics",
                "logging.statsIntervalSeconds");
        for (String relative : List.of(
                "prepatcher.properties",
                "profiles/safe.properties",
                "profiles/aggressive.properties")) {
            String properties = readUtf8(requiredFile(root.resolve(relative)));
            for (String key : debugOnlyKeys) {
                requireNoProperty(relative, properties, key);
            }
        }

        String relative = "profiles/debug.properties";
        String debug = readUtf8(requiredFile(root.resolve(relative)));
        requirePropertyValue(relative, debug, "observer.marketAdvanceSemanticRisks", "true");
        requirePropertyValue(relative, debug, "observer.marketConstructionDiagnostics", "true");
        requirePropertyValue(relative, debug,
                "observer.marketConstructionDiagnosticsMaxSamplesPerReason", "64");
        requirePropertyValue(relative, debug, "patch.directMarketObservation", "true");
        requirePropertyValue(relative, debug, "directMarket.timingSampleEvery", "128");
        requirePropertyValue(relative, debug, "directMarket.stackSampleEvery", "2048");
        requirePropertyValue(relative, debug, "directMarket.maxStacksPerSite", "8");
        requirePropertyValue(relative, debug, "directMarket.reportIntervalSeconds", "15");
        requirePropertyValue(relative, debug, "directMarket.maxSites", "4096");
        requirePropertyValue(relative, debug, "directMarket.unknownStackSamples", "32");
        requirePropertyValue(relative, debug, "fastForward.verbose", "true");
        requirePropertyValue(relative, debug, "fastForward.metrics", "true");
        requirePropertyValue(relative, debug, "logging.statsIntervalSeconds", "30");

        String aggressiveRelative = "profiles/aggressive.properties";
        String aggressive = readUtf8(requiredFile(root.resolve(aggressiveRelative)));
        Map<String, String> aggressiveProperties = activeProperties(aggressiveRelative, aggressive);
        String defaultRelative = "prepatcher.properties";
        Map<String, String> defaultProperties = activeProperties(
                defaultRelative, readUtf8(requiredFile(root.resolve(defaultRelative))));
        require(defaultProperties.equals(aggressiveProperties),
                defaultRelative + " must exactly mirror all active properties from "
                        + aggressiveRelative);
        Map<String, String> debugProperties = activeProperties(relative, debug);
        for (Map.Entry<String, String> entry : aggressiveProperties.entrySet()) {
            require(entry.getValue().equals(debugProperties.get(entry.getKey())),
                    relative + " must inherit " + entry.getKey() + "=" + entry.getValue()
                            + " from " + aggressiveRelative + ", found "
                            + debugProperties.get(entry.getKey()));
        }
        Set<String> debugExtras = new LinkedHashSet<>(debugProperties.keySet());
        debugExtras.removeAll(aggressiveProperties.keySet());
        require(debugExtras.equals(new LinkedHashSet<>(debugOnlyKeys)),
                relative + " must equal the aggressive profile plus only the approved diagnostics; "
                        + "unexpected/missing extras=" + debugExtras);

        String configSource = readUtf8(requiredFile(root.resolve(
                "source/agent/com/starsector/prepatcher/agent/PrepatcherConfig.java")));
        require(configSource.contains("fastForwardVerbose = bool(\"fastForward.verbose\", false)"),
                "fastForward.verbose code default must remain false");
        require(configSource.contains(
                        "statsLogIntervalSeconds = integer(\"logging.statsIntervalSeconds\", 0,"),
                "logging.statsIntervalSeconds code default must remain zero");
    }

    private static Map<String, String> activeProperties(String relative, String properties) {
        Map<String, String> result = new LinkedHashMap<>();
        properties.lines().forEach(line -> {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                return;
            }
            int separator = trimmed.indexOf('=');
            require(separator > 0, relative + " has malformed active property: " + line);
            String key = trimmed.substring(0, separator).trim();
            String value = trimmed.substring(separator + 1).trim();
            require(!key.isEmpty() && !value.isEmpty(),
                    relative + " has malformed active property: " + line);
            require(result.put(key, value) == null,
                    relative + " has duplicate active " + key + " assignments");
        });
        return result;
    }

    private static void requireNoProperty(
            String relative, String properties, String key) {
        Pattern assignment = Pattern.compile(
                "(?m)^" + Pattern.quote(key) + "[ \\t]*=[ \\t]*([^\\s#]+)[ \\t]*$");
        require(!assignment.matcher(properties).find(),
                relative + " must leave debug-only property " + key + " unspecified");
    }

    private static void requirePropertyValue(String relative, String properties,
            String key, String expectedValue) {
        Pattern assignment = Pattern.compile(
                "(?m)^" + Pattern.quote(key) + "[ \\t]*=[ \\t]*([^\\s#]+)[ \\t]*$");
        Matcher matcher = assignment.matcher(properties);
        require(matcher.find(), relative + " has no active " + key + " assignment");
        require(matcher.group(1).equals(expectedValue),
                relative + " must keep known-disabled " + key + "=" + expectedValue
                        + ", found " + matcher.group(1));
        require(!matcher.find(), relative + " has duplicate active " + key + " assignments");
    }

    private static void checkReportLayout(Path root) throws IOException {
        String gitignore = readUtf8(requiredFile(root.resolve(".gitignore")));
        require(gitignore.lines().map(String::trim).anyMatch(".build/"::equals),
                ".gitignore must exclude .build/ reports");

        Map<String, String> scripts = Map.of(
                "verify-structural.ps1", "$reportDir = Join-Path $build 'reports'",
                "verify-structural.sh", "REPORT_DIR=\"$BUILD/reports\"");
        List<String> reports = List.of(
                "documentation-consistency.txt",
                "structural-verification.txt",
                "fast-forward-presentation-compatibility.txt",
                "fast-forward-presentation-runtime.txt",
                "fast-forward-presentation-actual-agent.txt",
                "direct-market-transformer.txt",
                "runtime-regression.txt",
                "temp-mod-actual-agent-smoke.txt",
                "commodity-temporal-agent-smoke.txt",
                "market-noop-actual-agent-smoke.txt",
                "temp-mod-xstream-save-smoke.txt",
                "hyperspace-verification.txt",
                "startup-smoke.txt",
                "faster-rendering-loader-smoke.txt");
        for (Map.Entry<String, String> script : scripts.entrySet()) {
            String text = readUtf8(requiredFile(root.resolve(script.getKey())));
            require(text.contains(script.getValue()),
                    script.getKey() + " must write reports below .build/reports");
            require(text.contains("structural-all-enabled.properties")
                            && text.contains("patch.loadingTextReader")
                            && text.contains("patch.startupLogAggregation"),
                    script.getKey() + " must generate the internal all-enabled structural config");
            for (String report : reports) {
                require(text.contains(report),
                        script.getKey() + " does not produce required report " + report);
            }
        }
    }

    private static void checkChecksums(Path root) throws IOException {
        Path manifest = requiredFile(root.resolve("SHA256SUMS.txt"));
        Map<String, String> entries = new LinkedHashMap<>();
        String previous = null;
        int lineNumber = 0;
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            lineNumber++;
            Matcher matcher = SHA256_ENTRY.matcher(stripBom(line, lineNumber - 1));
            require(matcher.matches(),
                    location(manifest, lineNumber) + " must be '<sha256>  <relative-path>'");
            String relative = matcher.group(2);
            require(relative.equals(relative.trim()) && !relative.contains("\\"),
                    location(manifest, lineNumber)
                            + " must use a trimmed forward-slash relative path");
            require(previous == null || previous.compareTo(relative) < 0,
                    location(manifest, lineNumber)
                            + " entries must be unique and sorted with ordinal ordering");
            entries.put(relative, matcher.group(1));
            previous = relative;
        }
        require(!entries.isEmpty(), "SHA256SUMS.txt is empty");

        Set<String> expected = checksumInputs(root);
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(entries.keySet());
        Set<String> unexpected = new LinkedHashSet<>(entries.keySet());
        unexpected.removeAll(expected);
        require(missing.isEmpty() && unexpected.isEmpty(),
                "SHA256SUMS.txt coverage differs; missing=" + missing
                        + ", unexpected=" + unexpected);

        for (Map.Entry<String, String> entry : entries.entrySet()) {
            Path file = requiredFile(root.resolve(entry.getKey()).normalize());
            require(file.startsWith(root),
                    "SHA256SUMS.txt path escapes mod root: " + entry.getKey());
            String actual = sha256(file);
            require(entry.getValue().equals(actual),
                    "SHA256 mismatch for " + entry.getKey() + ": expected "
                            + entry.getValue() + ", actual " + actual);
        }
    }

    private static Set<String> checksumInputs(Path root) throws IOException {
        Set<String> inputs = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(root)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("SHA256SUMS.txt"))
                    .map(path -> display(root, path))
                    .sorted()
                    .forEach(inputs::add);
        }
        for (String directory : List.of("agent", "docs", "jars", "media", "profiles", "source")) {
            Path base = root.resolve(directory);
            require(Files.isDirectory(base), "checksum input directory is missing: " + base);
            try (Stream<Path> files = Files.walk(base)) {
                files.filter(Files::isRegularFile)
                        .map(path -> display(root, path))
                        .sorted()
                        .forEach(inputs::add);
            }
        }
        Path logsReadme = requiredFile(root.resolve("logs/README.txt"));
        inputs.add(display(root, logsReadme));
        return inputs;
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("JVM does not provide SHA-256", impossible);
        }
        byte[] buffer = new byte[16 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            for (int count; (count = input.read(buffer)) >= 0;) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static int checkPatchValidationCoverage(Path root) throws IOException {
        Set<String> catalog = patchIds(requiredFile(root.resolve("docs/PATCHES.md")));
        Set<String> validation = patchIds(requiredFile(root.resolve("docs/VALIDATION.md")));
        require(!catalog.isEmpty(), "docs/PATCHES.md contains no patch.* identifiers");

        Set<String> missing = new LinkedHashSet<>(catalog);
        missing.removeAll(validation);
        Set<String> unknown = new LinkedHashSet<>(validation);
        unknown.removeAll(catalog);
        require(missing.isEmpty() && unknown.isEmpty(),
                "PATCHES/VALIDATION patch coverage differs; missing scenarios=" + missing
                        + ", unknown scenarios=" + unknown);
        return catalog.size();
    }

    private static Set<String> patchIds(Path path) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = PATCH_ID.matcher(readUtf8(path));
        while (matcher.find()) ids.add(matcher.group(1));
        return ids;
    }

    private static Set<Path> collectCurrentMarkdown(Path root) throws IOException {
        Set<Path> markdown = new LinkedHashSet<>();
        requiredFile(root.resolve("README.md"));
        requiredFile(root.resolve("BUILDING.md"));
        requiredFile(root.resolve("CHANGELOG.md"));
        try (Stream<Path> paths = Files.list(root)) {
            paths.filter(Files::isRegularFile)
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(DocumentationConsistencyTest::isMarkdown)
                    .sorted()
                    .forEach(markdown::add);
        }

        Path docs = root.resolve("docs");
        require(Files.isDirectory(docs), "docs directory is missing: " + docs);
        try (Stream<Path> paths = Files.walk(docs)) {
            paths.filter(Files::isRegularFile)
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(DocumentationConsistencyTest::isMarkdown)
                    .sorted()
                    .forEach(markdown::add);
        }
        return Collections.unmodifiableSet(markdown);
    }

    private static void checkForbiddenLayout(Path root) throws IOException {
        try (Stream<Path> rootFiles = Files.list(root)) {
            rootFiles.filter(Files::isRegularFile).forEach(path -> {
                String upper = path.getFileName().toString().toUpperCase(Locale.ROOT);
                require(!upper.equals("RELEASE_NOTES.MD"),
                        "obsolete RELEASE_NOTES.md is forbidden; use CHANGELOG.md: "
                                + path);
                require(!LEGACY_CANONICAL_DOC_NAMES.contains(upper),
                        "obsolete canonical document name is forbidden: " + path);
            });
        }

        Path docs = root.resolve("docs");
        try (Stream<Path> paths = Files.walk(docs)) {
            paths.filter(Files::isRegularFile)
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        String lower = name.toLowerCase(Locale.ROOT);
                        require(!name.equalsIgnoreCase("RELEASE_NOTES.md"),
                                "obsolete RELEASE_NOTES.md is forbidden: "
                                        + display(root, path));
                        require(!lower.endsWith(".txt"),
                                "raw *.txt reports belong in .build/reports, not docs: "
                                        + display(root, path));
                        require(!lower.contains("integrated"),
                                "current docs filename must not contain INTEGRATED: "
                                        + display(root, path));
                        String upper = name.toUpperCase(Locale.ROOT);
                        require(!LEGACY_CANONICAL_DOC_NAMES.contains(upper),
                                "obsolete canonical document name is forbidden: "
                                        + display(root, path));
                        if (path.getParent().equals(docs)) {
                            require(CURRENT_DOC_ROOT_NAMES.contains(upper),
                                    "docs contains a non-canonical document; keep release history in "
                                            + "CHANGELOG.md and raw evidence in .build/reports: "
                                            + display(root, path));
                        }
                    });
        }
    }

    private static void checkCurrentVersionSuffixes(Set<Path> markdown, Path changelog,
            Path modInfo, Version currentVersion) throws IOException {
        Pattern legacySuffix = Pattern.compile(
                "(?i)(?<![0-9])" + Pattern.quote(currentVersion.toString())
                        + "-[a-z0-9.-]*(?:exp|integrated)[a-z0-9.-]*(?![a-z0-9.-])");

        for (Path path : markdown) {
            if (!path.equals(changelog)) {
                assertNoLegacyVersionSuffix(path, readUtf8(path), legacySuffix);
            }
        }
        assertNoLegacyVersionSuffix(modInfo, readUtf8(modInfo), legacySuffix);
    }

    private static void assertNoLegacyVersionSuffix(Path path, String text, Pattern suffix) {
        Matcher matcher = suffix.matcher(text);
        if (matcher.find()) {
            throw new AssertionError("current version must be plain x.y.z outside "
                    + "CHANGELOG historical prose: " + path + " contains '"
                    + matcher.group() + "'");
        }
    }

    private static LinkGraph checkLinks(Path root, Set<Path> markdown) throws IOException {
        Map<Path, Set<Path>> edges = new HashMap<>();
        int relativeLinks = 0;
        for (Path source : markdown) {
            Set<Path> targets = new LinkedHashSet<>();
            edges.put(source, targets);
            for (String destination : markdownDestinations(readUtf8(source))) {
                String localPath = localPathPart(destination);
                if (localPath == null) continue;
                relativeLinks++;
                if (localPath.isEmpty()) continue; // A fragment in the current document.

                String decoded = decodePercentEscapes(unescapeMarkdown(localPath), source);
                Path resolved;
                try {
                    resolved = source.getParent().resolve(decoded).toAbsolutePath().normalize();
                } catch (InvalidPathException invalid) {
                    throw new AssertionError("invalid relative Markdown link in " + source
                            + ": " + destination, invalid);
                }
                require(resolved.startsWith(root),
                        "relative Markdown link escapes the mod root: " + display(root, source)
                                + " -> " + destination);
                require(Files.exists(resolved),
                        "broken relative Markdown link: " + display(root, source)
                                + " -> " + destination + " (resolved to " + resolved + ")");
                if (isMarkdown(resolved) && markdown.contains(resolved)) {
                    targets.add(resolved);
                }
            }
        }
        return new LinkGraph(edges, relativeLinks);
    }

    private static void checkReachability(Path root, Set<Path> markdown,
            Map<Path, Set<Path>> edges) {
        Path readme = root.resolve("README.md").toAbsolutePath().normalize();
        Set<Path> reached = new LinkedHashSet<>();
        ArrayDeque<Path> pending = new ArrayDeque<>();
        reached.add(readme);
        pending.add(readme);
        while (!pending.isEmpty()) {
            Path source = pending.removeFirst();
            for (Path target : edges.getOrDefault(source, Set.of())) {
                if (reached.add(target)) pending.addLast(target);
            }
        }

        Set<Path> unreachable = new LinkedHashSet<>(markdown);
        unreachable.removeAll(reached);
        require(unreachable.isEmpty(),
                "current Markdown is not reachable from README.md: "
                        + unreachable.stream().map(path -> display(root, path)).toList());
    }

    private static List<String> markdownDestinations(String markdown) {
        List<String> destinations = new ArrayList<>();
        boolean inFence = false;
        char fenceCharacter = 0;
        int fenceLength = 0;

        for (String line : markdown.split("\\R", -1)) {
            Fence fence = fenceAtStart(line);
            if (fence != null) {
                if (!inFence) {
                    inFence = true;
                    fenceCharacter = fence.character;
                    fenceLength = fence.length;
                } else if (fence.character == fenceCharacter && fence.length >= fenceLength) {
                    inFence = false;
                }
                continue;
            }
            if (inFence) continue;

            collectInlineDestinations(line, destinations);
            Matcher reference = REFERENCE_LINK.matcher(line);
            if (reference.matches()) {
                destinations.add(reference.group(1) != null
                        ? reference.group(1) : reference.group(2));
            }
        }
        return destinations;
    }

    private static void collectInlineDestinations(String line, List<String> destinations) {
        for (int cursor = 0; cursor + 1 < line.length(); cursor++) {
            if (line.charAt(cursor) != ']' || line.charAt(cursor + 1) != '('
                    || isEscaped(line, cursor)) continue;

            int depth = 1;
            int end = cursor + 2;
            for (; end < line.length(); end++) {
                char character = line.charAt(end);
                if (character == '\\') {
                    end++;
                    continue;
                }
                if (character == '(') depth++;
                if (character == ')' && --depth == 0) break;
            }
            require(depth == 0, "unterminated inline Markdown link: " + line);
            String destination = firstDestinationToken(line.substring(cursor + 2, end));
            require(!destination.isEmpty(), "empty inline Markdown link destination: " + line);
            destinations.add(destination);
            cursor = end;
        }
    }

    private static String firstDestinationToken(String contents) {
        String trimmed = contents.trim();
        if (trimmed.startsWith("<")) {
            int close = trimmed.indexOf('>');
            require(close >= 0, "unterminated angle-bracket Markdown destination: " + contents);
            return trimmed.substring(1, close);
        }
        int end = 0;
        boolean escaped = false;
        while (end < trimmed.length()) {
            char character = trimmed.charAt(end);
            if (!escaped && Character.isWhitespace(character)) break;
            if (!escaped && character == '\\') escaped = true;
            else escaped = false;
            end++;
        }
        return trimmed.substring(0, end);
    }

    /** Returns null for external/absolute links, empty for a same-document fragment. */
    private static String localPathPart(String destination) {
        String value = destination.trim();
        if (value.startsWith("#")) return "";
        if (value.startsWith("//") || value.startsWith("/") || value.startsWith("\\")
                || URI_SCHEME.matcher(value).find()) return null;
        int fragment = value.indexOf('#');
        int query = value.indexOf('?');
        int end = value.length();
        if (fragment >= 0) end = Math.min(end, fragment);
        if (query >= 0) end = Math.min(end, query);
        return value.substring(0, end);
    }

    private static String decodePercentEscapes(String value, Path source) {
        if (value.indexOf('%') < 0) return value;
        StringBuilder result = new StringBuilder();
        byte[] bytes = new byte[value.length()];
        for (int index = 0; index < value.length();) {
            if (value.charAt(index) != '%') {
                result.append(value.charAt(index++));
                continue;
            }
            int count = 0;
            while (index < value.length() && value.charAt(index) == '%') {
                require(index + 2 < value.length(),
                        "short percent escape in Markdown link from " + source + ": " + value);
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                require(high >= 0 && low >= 0,
                        "invalid percent escape in Markdown link from " + source + ": " + value);
                bytes[count++] = (byte) ((high << 4) | low);
                index += 3;
            }
            result.append(new String(bytes, 0, count, StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    private static String unescapeMarkdown(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\' && index + 1 < value.length()) {
                result.append(value.charAt(++index));
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static Fence fenceAtStart(String line) {
        int cursor = 0;
        while (cursor < line.length() && cursor < 3 && line.charAt(cursor) == ' ') cursor++;
        if (cursor >= line.length()) return null;
        char character = line.charAt(cursor);
        if (character != '`' && character != '~') return null;
        int end = cursor;
        while (end < line.length() && line.charAt(end) == character) end++;
        return end - cursor >= 3 ? new Fence(character, end - cursor) : null;
    }

    private static boolean isEscaped(String value, int index) {
        int slashes = 0;
        while (index > 0 && value.charAt(--index) == '\\') slashes++;
        return (slashes & 1) != 0;
    }

    private static boolean isMarkdown(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md");
    }

    private static Path requiredFile(Path path) {
        path = path.toAbsolutePath().normalize();
        require(Files.isRegularFile(path), "required file is missing: " + path);
        return path;
    }

    private static String readUtf8(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static int skipWhitespace(String value, int cursor) {
        while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) cursor++;
        return cursor;
    }

    private static String stripBom(String value, int lineIndex) {
        return lineIndex == 0 && !value.isEmpty() && value.charAt(0) == '\uFEFF'
                ? value.substring(1) : value;
    }

    private static String location(Path path, int line) {
        return path + ":" + line;
    }

    private static String display(Path root, Path path) {
        try {
            return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (IllegalArgumentException differentRoots) {
            return path.toString();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Version implements Comparable<Version> {
        private final BigInteger major;
        private final BigInteger minor;
        private final BigInteger patch;

        private Version(String major, String minor, String patch) {
            this.major = new BigInteger(major);
            this.minor = new BigInteger(minor);
            this.patch = new BigInteger(patch);
        }

        private static Version parse(String value, String description) {
            Matcher matcher = STRICT_VERSION.matcher(value);
            require(matcher.matches(), description + " must be strict x.y.z without suffix: " + value);
            return new Version(matcher.group(1), matcher.group(2), matcher.group(3));
        }

        @Override
        public int compareTo(Version other) {
            int result = major.compareTo(other.major);
            if (result == 0) result = minor.compareTo(other.minor);
            if (result == 0) result = patch.compareTo(other.patch);
            return result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Version)) return false;
            Version version = (Version) other;
            return major.equals(version.major)
                    && minor.equals(version.minor)
                    && patch.equals(version.patch);
        }

        @Override
        public int hashCode() {
            int result = major.hashCode();
            result = 31 * result + minor.hashCode();
            return 31 * result + patch.hashCode();
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    private static final class ParsedString {
        private final String value;
        private final int end;

        private ParsedString(String value, int end) {
            this.value = value;
            this.end = end;
        }
    }

    private static final class ReleaseHeading {
        private final Version version;
        private final int line;

        private ReleaseHeading(Version version, int line) {
            this.version = version;
            this.line = line;
        }
    }

    private static final class LinkGraph {
        private final Map<Path, Set<Path>> edges;
        private final int relativeLinkCount;

        private LinkGraph(Map<Path, Set<Path>> edges, int relativeLinkCount) {
            this.edges = edges;
            this.relativeLinkCount = relativeLinkCount;
        }
    }

    private static final class Fence {
        private final char character;
        private final int length;

        private Fence(char character, int length) {
            this.character = character;
            this.length = length;
        }
    }
}
