package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherCoreWorldsRuntime;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import org.lwjgl.util.vector.Vector2f;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Semantic, complexity and retention regression for the incremental core-worlds index. */
public final class CoreWorldsRuntimeRegressionTest {
    private static final String ENGINE_STATUS =
            "starsector.prepatcher.patchStatus.com.fs.starfarer.campaign."
                    + "CampaignEngine.coreWorldsExtentCache";
    private static final String LOCATION_STATUS =
            "starsector.prepatcher.patchStatus.com.fs.starfarer.campaign."
                    + "BaseLocation.coreWorldsExtentCache";
    private static final String MIN_KEY = "$coreWorldsMin";
    private static final String MAX_KEY = "$coreWorldsMax";
    private static final String CENTER_KEY = "$coreWorldsCenter";

    private CoreWorldsRuntimeRegressionTest() {}

    public static void main(String[] args) throws Exception {
        String oldEngineStatus = System.getProperty(ENGINE_STATUS);
        String oldLocationStatus = System.getProperty(LOCATION_STATUS);
        Path configFile = Files.createTempFile("prepatcher-core-worlds", ".properties");
        try {
            System.setProperty(ENGINE_STATUS, "APPLIED");
            System.setProperty(LOCATION_STATUS, "APPLIED");
            Files.writeString(configFile, String.join("\n",
                    "patch.coreWorldsExtentCache=true",
                    "coreWorlds.skipFastForwardIterations=true",
                    "coreWorlds.checkMemoryExpiry=true",
                    "coreWorlds.validationFrames=1",
                    "coreWorlds.auditSystemsPerFrame=8", ""));
            PrepatcherConfig config = PrepatcherConfig.load(configFile);
            assertCapabilityGateFailsClosed(config);
            System.setProperty(ENGINE_STATUS, "APPLIED");
            System.setProperty(LOCATION_STATUS, "APPLIED");
            configure(config);

            runIncrementalAndSemanticRegression();
            assertStaticStateShape();
            assertLifecycleResetReplacesIndexContainers();
            assertNoStaticGameObjectRetention();
            System.out.println("OK core-worlds runtime one-full-snapshot/O(C+B)-steady-state"
                    + "/topology-events/tag-events/direct-tag-audit/location/repair"
                    + "/fast-forward/expiry/recovery/capability-fail-closed"
                    + "/container-release/weak-retention");
        } finally {
            StarsectorPrepatcherCoreWorldsRuntime.resetForTests();
            restoreProperty(ENGINE_STATUS, oldEngineStatus);
            restoreProperty(LOCATION_STATUS, oldLocationStatus);
            Files.deleteIfExists(configFile);
        }
    }


    private static void assertCapabilityGateFailsClosed(PrepatcherConfig config)
            throws Exception {
        System.setProperty(ENGINE_STATUS, "APPLIED");
        System.setProperty(LOCATION_STATUS, "SKIPPED_STRUCTURAL");
        configure(config);
        Method ready = StarsectorPrepatcherCoreWorldsRuntime.class
                .getDeclaredMethod("mutationHooksReady");
        ready.setAccessible(true);
        require(Boolean.FALSE.equals(ready.invoke(null)),
                "incomplete cross-class mutation capability did not fail closed");
        Field capability = StarsectorPrepatcherCoreWorldsRuntime.class
                .getDeclaredField("mutationHookCapability");
        capability.setAccessible(true);
        require(capability.getInt(null) < 0,
                "terminal mutation-hook failure was not cached as fail-closed");
    }

    private static void assertStaticStateShape() throws Exception {
        for (Field field : StarsectorPrepatcherCoreWorldsRuntime.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            Class<?> type = field.getType();
            require(!SectorAPI.class.isAssignableFrom(type)
                            && !StarSystemAPI.class.isAssignableFrom(type)
                            && !MemoryAPI.class.isAssignableFrom(type)
                            && !Vector2f.class.isAssignableFrom(type),
                    "runtime has direct static game-object field: " + field);
        }
        Class<?> entry = Class.forName(
                "com.fs.starfarer.api.StarsectorPrepatcherCoreWorldsRuntime$SystemEntry");
        for (Field field : entry.getDeclaredFields()) {
            require(field.getType() == WeakReference.class || field.getType() == boolean.class,
                    "SystemEntry gained a strong game-object field: " + field);
        }
    }

    private static void runIncrementalAndSemanticRegression() throws Exception {
        Fixture fixture = new Fixture(128);
        SystemHandle first = fixture.add(true, -10f, 20f);
        SystemHandle second = fixture.add(true, 30f, -40f);
        SystemHandle third = fixture.add(true, 5f, 10f);
        while (fixture.systems.size() < 128) {
            int index = fixture.systems.size();
            fixture.add(false, index + 1f, index * 0.25f);
        }

        StarsectorPrepatcherCoreWorldsRuntime.update(fixture.sector);
        require(fixture.snapshotCalls == 1,
                "initial index build must request exactly one defensive system snapshot");
        require(fixture.memoryState.setCalls == 3,
                "initial index build must publish exactly three memory values");
        fixture.assertPublishedMatchesVanilla("initial publication");

        long beforeChecks = fixture.totalHasTagCalls();
        for (int i = 0; i < 20; i++) {
            StarsectorPrepatcherCoreWorldsRuntime.update(fixture.sector);
        }
        long steadyChecks = fixture.totalHasTagCalls() - beforeChecks;
        require(fixture.snapshotCalls == 1,
                "steady state called SectorAPI.getStarSystems() again: "
                        + fixture.snapshotCalls);
        require(fixture.memoryState.setCalls == 3,
                "unchanged steady state republished vectors");
        require(steadyChecks <= 20L * (3L + 8L),
                "steady-state tag checks exceeded O(C+B): " + steadyChecks);
        require(steadyChecks < 20L * fixture.systems.size() / 4L,
                "steady state resembles a full-sector scan: " + steadyChecks);

        first.state.location.x = -75f;
        updateAndRequirePublish(fixture, "direct core-system coordinate mutation");
        require(fixture.snapshotCalls == 1,
                "coordinate validation recreated the sector snapshot");

        SystemHandle added = fixture.add(true, 180f, 220f);
        StarsectorPrepatcherCoreWorldsRuntime.starSystemAdded(fixture.sector, added.api);
        updateAndRequirePublish(fixture, "createStarSystem event");
        require(fixture.snapshotCalls == 1,
                "system-add event forced a full-sector snapshot");

        fixture.remove(added);
        StarsectorPrepatcherCoreWorldsRuntime.starSystemRemoved(fixture.sector, added.api);
        updateAndRequirePublish(fixture, "removeStarSystem event");
        require(fixture.snapshotCalls == 1,
                "system-remove event forced a full-sector snapshot");

        SystemHandle apiTag = fixture.systems.get(20);
        apiTag.state.core = true;
        apiTag.state.location.set(-240f, 55f);
        StarsectorPrepatcherCoreWorldsRuntime.locationTagAdded(apiTag.api, "theme_core");
        updateAndRequirePublish(fixture, "addTag(theme_core) event");
        require(fixture.snapshotCalls == 1,
                "tag-add event forced a full-sector snapshot");

        apiTag.state.core = false;
        StarsectorPrepatcherCoreWorldsRuntime.locationTagRemoved(apiTag.api, "theme_core");
        updateAndRequirePublish(fixture, "removeTag(theme_core) event");

        SystemHandle clearTag = fixture.systems.get(21);
        clearTag.state.core = true;
        clearTag.state.location.set(75f, -260f);
        StarsectorPrepatcherCoreWorldsRuntime.locationTagAdded(clearTag.api, "theme_core");
        updateAndRequirePublish(fixture, "pre-clear core membership");
        clearTag.state.core = false;
        StarsectorPrepatcherCoreWorldsRuntime.locationTagsCleared(clearTag.api);
        updateAndRequirePublish(fixture, "clearTags event");

        // Simulate a caller retaining BaseLocation.getTags() and editing the live HashSet.
        // No mutation hook is sent; the bounded rotating audit must discover this without
        // calling SectorAPI.getStarSystems() again.
        SystemHandle directTag = fixture.systems.get(90);
        directTag.state.location.set(320f, 0f);
        directTag.state.core = true;
        int writesBeforeDirectAudit = fixture.memoryState.setCalls;
        int maxAuditFrames = fixture.systems.size() / 8 + 4;
        for (int i = 0; i < maxAuditFrames
                && fixture.memoryState.setCalls == writesBeforeDirectAudit; i++) {
            StarsectorPrepatcherCoreWorldsRuntime.update(fixture.sector);
        }
        require(fixture.memoryState.setCalls == writesBeforeDirectAudit + 3,
                "bounded direct-tag audit did not discover a new core system");
        fixture.assertPublishedMatchesVanilla("direct live-tag addition audit");
        require(fixture.snapshotCalls == 1,
                "bounded direct-tag audit recreated the full system snapshot");

        // A direct removal is caught immediately because known core entries are the O(C)
        // geometry validation set.
        second.state.core = false;
        updateAndRequirePublish(fixture, "direct live-tag removal from known core system");
        require(fixture.snapshotCalls == 1,
                "known-core tag removal forced a full-sector snapshot");

        Vector2f publishedMin = (Vector2f) fixture.memoryState.values.get(MIN_KEY);
        publishedMin.x = 99_999f;
        updateAndRequirePublish(fixture, "mutated published memory vector repair");

        first.state.location.x = -500f;
        fixture.fastForward = true;
        int writesBeforeFastForward = fixture.memoryState.setCalls;
        StarsectorPrepatcherCoreWorldsRuntime.update(fixture.sector);
        require(fixture.memoryState.setCalls == writesBeforeFastForward,
                "intact extra fast-forward substep was not skipped");
        fixture.fastForward = false;
        updateAndRequirePublish(fixture, "first outer-frame update after fast-forward");

        fixture.memoryState.expires.put(MIN_KEY, 5f);
        int writesBeforeExpiry = fixture.memoryState.setCalls;
        StarsectorPrepatcherCoreWorldsRuntime.update(fixture.sector);
        require(fixture.memoryState.setCalls == writesBeforeExpiry + 3,
                "timed memory expiry did not force a republish");
        require(!fixture.memoryState.expires.containsKey(MIN_KEY),
                "MemoryAPI.set did not clear the timed expiry");
        fixture.assertPublishedMatchesVanilla("timed-expiry repair");

        // Simulate a missed topology hook followed by an authoritative theme_core mutation.
        // The tag hook requests one recovery rebuild; repeated steady-state updates must not.
        SystemHandle missed = fixture.add(true, 640f, 15f);
        StarsectorPrepatcherCoreWorldsRuntime.locationTagAdded(missed.api, "theme_core");
        int snapshotsBeforeRecovery = fixture.snapshotCalls;
        updateAndRequirePublish(fixture, "missed-topology recovery rebuild");
        require(fixture.snapshotCalls == snapshotsBeforeRecovery + 1,
                "anomalous missing membership did not trigger exactly one recovery snapshot");
        for (int i = 0; i < 10; i++) {
            StarsectorPrepatcherCoreWorldsRuntime.update(fixture.sector);
        }
        require(fixture.snapshotCalls == snapshotsBeforeRecovery + 1,
                "recovery snapshot repeated in steady state");

        String stats = statsAndReset();
        require(counter(stats, "coreWorldsSystemScans") == 2L,
                "expected initial+recovery full snapshots, stats=" + stats);
        require(counter(stats, "coreWorldsSystemsVisited")
                        == 128L + fixture.systems.size(),
                "unexpected total systems visited by full snapshots, stats=" + stats);
        require(counter(stats, "coreWorldsMembershipAuditChecks") > 0L,
                "bounded membership audit did not run, stats=" + stats);
        require(counter(stats, "coreWorldsSystemAddEvents") == 1L,
                "system-add event counter mismatch, stats=" + stats);
        require(counter(stats, "coreWorldsSystemRemoveEvents") == 1L,
                "system-remove event counter mismatch, stats=" + stats);
    }

    private static void updateAndRequirePublish(Fixture fixture, String label) {
        int before = fixture.memoryState.setCalls;
        StarsectorPrepatcherCoreWorldsRuntime.update(fixture.sector);
        require(fixture.memoryState.setCalls == before + 3,
                label + " did not publish changed/repaired bounds: before=" + before
                        + " after=" + fixture.memoryState.setCalls);
        fixture.assertPublishedMatchesVanilla(label);
    }

    private static void assertLifecycleResetReplacesIndexContainers() throws Exception {
        Fixture fixture = new Fixture(32);
        fixture.add(true, 10f, 20f);
        for (int i = 1; i < 32; i++) fixture.add(false, i, -i);
        StarsectorPrepatcherCoreWorldsRuntime.update(fixture.sector);

        Field allField = StarsectorPrepatcherCoreWorldsRuntime.class
                .getDeclaredField("allSystems");
        Field coreField = StarsectorPrepatcherCoreWorldsRuntime.class
                .getDeclaredField("coreSystems");
        allField.setAccessible(true);
        coreField.setAccessible(true);
        Object oldAll = allField.get(null);
        Object oldCore = coreField.get(null);

        StarsectorPrepatcherCoreWorldsRuntime.campaignLifecycleReset();
        Object newAll = allField.get(null);
        Object newCore = coreField.get(null);
        require(oldAll != newAll && oldCore != newCore,
                "campaign lifecycle reset retained wrapper-list backing containers");
        require(newAll instanceof List<?> all && all.isEmpty(),
                "replacement all-system index is not empty");
        require(newCore instanceof List<?> core && core.isEmpty(),
                "replacement core-system index is not empty");
    }

    /** Verifies that process-lifetime metadata contains no strong campaign-object edges. */
    private static void assertNoStaticGameObjectRetention() throws Exception {
        StarsectorPrepatcherCoreWorldsRuntime.resetForTests();
        List<GcProbe> probes = createGcFixture();
        for (int attempt = 0; attempt < 120 && hasLiveProbe(probes); attempt++) {
            System.gc();
            System.runFinalization();
            byte[][] pressure = new byte[8][];
            for (int i = 0; i < pressure.length; i++) {
                pressure[i] = new byte[256 * 1024];
            }
            Thread.sleep(10L);
        }
        List<String> survivors = new ArrayList<>();
        for (GcProbe probe : probes) {
            if (probe.reference.get() != null) survivors.add(probe.label);
        }
        require(survivors.isEmpty(),
                "incremental core-worlds static state retained game objects: " + survivors);
        StarsectorPrepatcherCoreWorldsRuntime.resetForTests();
    }

    private static List<GcProbe> createGcFixture() {
        Fixture fixture = new Fixture(16);
        SystemHandle core = fixture.add(true, 100f, 200f);
        for (int i = 1; i < 16; i++) fixture.add(false, i, -i);
        StarsectorPrepatcherCoreWorldsRuntime.update(fixture.sector);
        Object publishedMin = fixture.memoryState.values.get(MIN_KEY);
        require(publishedMin instanceof Vector2f,
                "GC fixture did not publish the minimum vector");
        return List.of(
                new GcProbe("sector", new WeakReference<>(fixture.sector)),
                new GcProbe("memory", new WeakReference<>(fixture.memory)),
                new GcProbe("system-list", new WeakReference<>(fixture.systems)),
                new GcProbe("core-system", new WeakReference<>(core.api)),
                new GcProbe("published-min", new WeakReference<>(publishedMin)));
    }

    private static boolean hasLiveProbe(List<GcProbe> probes) {
        for (GcProbe probe : probes) {
            if (probe.reference.get() != null) return true;
        }
        return false;
    }

    private static void configure(PrepatcherConfig config) throws Exception {
        Method configure = StarsectorPrepatcherCoreWorldsRuntime.class
                .getDeclaredMethod("configure", PrepatcherConfig.class);
        configure.setAccessible(true);
        configure.invoke(null, config);
        StarsectorPrepatcherCoreWorldsRuntime.resetForTests();
    }

    private static String statsAndReset() throws Exception {
        Method stats = StarsectorPrepatcherCoreWorldsRuntime.class
                .getDeclaredMethod("statsAndResetFragment");
        stats.setAccessible(true);
        return (String) stats.invoke(null);
    }

    private static long counter(String stats, String name) {
        String marker = name + "=";
        int start = stats.indexOf(marker);
        require(start >= 0, "missing counter " + name + " in " + stats);
        start += marker.length();
        int end = stats.indexOf(',', start);
        if (end < 0) end = stats.length();
        return Long.parseLong(stats.substring(start, end).trim());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> method.getDeclaringClass().getSimpleName()
                    + "@" + Integer.toHexString(System.identityHashCode(proxy));
            default -> null;
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        return null;
    }

    private static void assertVector(Object value, float x, float y, String label) {
        require(value instanceof Vector2f, label + " is not Vector2f: " + value);
        Vector2f vector = (Vector2f) value;
        require(Float.floatToRawIntBits(vector.x) == Float.floatToRawIntBits(x)
                        && Float.floatToRawIntBits(vector.y) == Float.floatToRawIntBits(y),
                label + " expected=(" + x + "," + y + ") actual=("
                        + vector.x + "," + vector.y + ")");
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture {
        final MemoryState memoryState = new MemoryState();
        final MemoryAPI memory;
        final ArrayList<SystemHandle> systems = new ArrayList<>();
        final SectorAPI sector;
        int snapshotCalls;
        boolean fastForward;

        Fixture(int expectedSystems) {
            memory = proxy(MemoryAPI.class, (proxy, method, argv) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return objectMethod(proxy, method, argv);
                }
                return switch (method.getName()) {
                    case "get" -> memoryState.values.get((String) argv[0]);
                    case "set" -> {
                        String key = (String) argv[0];
                        memoryState.values.put(key, argv[1]);
                        memoryState.expires.remove(key);
                        memoryState.setCalls++;
                        yield null;
                    }
                    case "getExpire" -> memoryState.expires.getOrDefault(
                            (String) argv[0], -1f);
                    default -> defaultValue(method.getReturnType());
                };
            });
            sector = proxy(SectorAPI.class, (proxy, method, argv) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return objectMethod(proxy, method, argv);
                }
                return switch (method.getName()) {
                    case "getMemoryWithoutUpdate" -> memory;
                    case "getStarSystems" -> {
                        snapshotCalls++;
                        ArrayList<StarSystemAPI> snapshot = new ArrayList<>(systems.size());
                        for (SystemHandle handle : systems) snapshot.add(handle.api);
                        yield snapshot;
                    }
                    case "isFastForwardIteration" -> fastForward;
                    default -> defaultValue(method.getReturnType());
                };
            });
            systems.ensureCapacity(expectedSystems);
        }

        SystemHandle add(boolean core, float x, float y) {
            SystemState state = new SystemState(core, x, y);
            StarSystemAPI api = proxy(StarSystemAPI.class, (proxy, method, argv) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return objectMethod(proxy, method, argv);
                }
                return switch (method.getName()) {
                    case "hasTag" -> {
                        state.hasTagCalls++;
                        yield state.core && "theme_core".equals(argv[0]);
                    }
                    case "getLocation" -> state.location;
                    default -> defaultValue(method.getReturnType());
                };
            });
            SystemHandle handle = new SystemHandle(state, api);
            systems.add(handle);
            return handle;
        }

        void remove(SystemHandle handle) {
            require(systems.remove(handle), "fixture system removal failed");
        }

        long totalHasTagCalls() {
            long total = 0L;
            for (SystemHandle handle : systems) total += handle.state.hasTagCalls;
            return total;
        }

        void assertPublishedMatchesVanilla(String label) {
            float minX = 0f;
            float minY = 0f;
            float maxX = 0f;
            float maxY = 0f;
            for (SystemHandle handle : systems) {
                if (!handle.state.core) continue;
                Vector2f location = handle.state.location;
                minX = Math.min(minX, location.x);
                minY = Math.min(minY, location.y);
                maxX = Math.max(maxX, location.x);
                maxY = Math.max(maxY, location.y);
            }
            assertVector(memoryState.values.get(MIN_KEY), minX, minY, label + " min");
            assertVector(memoryState.values.get(MAX_KEY), maxX, maxY, label + " max");
            assertVector(memoryState.values.get(CENTER_KEY),
                    (minX + maxX) * 0.5f, (minY + maxY) * 0.5f,
                    label + " center");
        }
    }

    private static final class MemoryState {
        final Map<String, Object> values = new HashMap<>();
        final Map<String, Float> expires = new HashMap<>();
        int setCalls;
    }

    private static final class SystemState {
        boolean core;
        final Vector2f location;
        long hasTagCalls;

        SystemState(boolean core, float x, float y) {
            this.core = core;
            this.location = new Vector2f(x, y);
        }
    }

    private record SystemHandle(SystemState state, StarSystemAPI api) {}
    private record GcProbe(String label, WeakReference<Object> reference) {}
}
