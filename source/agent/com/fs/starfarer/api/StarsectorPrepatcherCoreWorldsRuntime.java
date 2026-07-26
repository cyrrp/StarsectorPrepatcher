package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.util.Misc;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import com.starsector.prepatcher.agent.PrepatcherLog;
import org.lwjgl.util.vector.Vector2f;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * Incremental runtime for the three-class core-worlds extent contract.
 *
 * <p>The public sector snapshot is materialized only for initial/recovery reconciliation. Normal
 * updates consume authoritative CampaignEngine/BaseLocation mutation events, scan the small current
 * core set for mutable coordinates, and audit a bounded slice of non-core systems for direct edits
 * through BaseLocation.getTags().</p>
 */
public final class StarsectorPrepatcherCoreWorldsRuntime {
    private static final String MIN_KEY = "$coreWorldsMin";
    private static final String MAX_KEY = "$coreWorldsMax";
    private static final String CENTER_KEY = "$coreWorldsCenter";
    private static final String CORE_TAG = "theme_core";
    private static final Object LOCK = new Object();

    private static final String CAMPAIGN_ENGINE_STATUS =
            "starsector.prepatcher.patchStatus.com.fs.starfarer.campaign."
                    + "CampaignEngine.coreWorldsExtentCache";
    private static final String BASE_LOCATION_STATUS =
            "starsector.prepatcher.patchStatus.com.fs.starfarer.campaign."
                    + "BaseLocation.coreWorldsExtentCache";
    private static final int CAPABILITY_UNKNOWN = 0;
    private static final int CAPABILITY_READY = 1;
    private static final int CAPABILITY_FAILED = -1;

    private static volatile boolean enabled;
    private static volatile boolean skipFastForwardIterations;
    private static volatile boolean checkMemoryExpiry;
    private static volatile int validationFrames = 1;
    private static volatile int auditSystemsPerPass = 64;
    private static volatile int mutationHookCapability = CAPABILITY_UNKNOWN;

    private static WeakReference<SectorAPI> sectorRef = new WeakReference<>(null);
    private static WeakReference<Vector2f> minRef = new WeakReference<>(null);
    private static WeakReference<Vector2f> maxRef = new WeakReference<>(null);
    private static WeakReference<Vector2f> centerRef = new WeakReference<>(null);
    private static ArrayList<SystemEntry> allSystems = new ArrayList<>();
    private static ArrayList<SystemEntry> coreSystems = new ArrayList<>();
    private static int auditCursor;
    private static int minXBits;
    private static int minYBits;
    private static int maxXBits;
    private static int maxYBits;
    private static int centerXBits;
    private static int centerYBits;
    private static long outerFrames;
    private static boolean indexInitialized;
    private static boolean rebuildRequested;
    private static boolean initialized;

    private static final LongAdder CALLS = new LongAdder();
    /** One-time/recovery full snapshots; retained under the old metric name for compatibility. */
    private static final LongAdder SYSTEM_SCANS = new LongAdder();
    private static final LongAdder SYSTEMS_VISITED = new LongAdder();
    private static final LongAdder CORE_SYSTEM_CHECKS = new LongAdder();
    private static final LongAdder MEMBERSHIP_AUDIT_CHECKS = new LongAdder();
    private static final LongAdder MEMBERSHIP_AUDIT_SWEEPS = new LongAdder();
    private static final LongAdder MEMBERSHIP_CHANGES = new LongAdder();
    private static final LongAdder SYSTEM_ADD_EVENTS = new LongAdder();
    private static final LongAdder SYSTEM_REMOVE_EVENTS = new LongAdder();
    private static final LongAdder TAG_EVENTS = new LongAdder();
    private static final LongAdder REBUILD_REQUESTS = new LongAdder();
    private static final LongAdder CAPABILITY_FALLBACKS = new LongAdder();
    private static final LongAdder FAST_FORWARD_SKIPS = new LongAdder();
    private static final LongAdder FRAME_VALIDATION_SKIPS = new LongAdder();
    private static final LongAdder UNCHANGED_SKIPS = new LongAdder();
    private static final LongAdder PUBLISHES = new LongAdder();
    private static final LongAdder INTEGRITY_REPAIRS = new LongAdder();
    private static final LongAdder EVENT_FAILURES = new LongAdder();
    private static final LongAdder FALLBACKS = new LongAdder();

    private StarsectorPrepatcherCoreWorldsRuntime() {}

    static void configure(PrepatcherConfig config) {
        enabled = config != null && config.coreWorldsExtentCache;
        skipFastForwardIterations = config != null
                && config.coreWorldsSkipFastForwardIterations;
        checkMemoryExpiry = config == null || config.coreWorldsCheckMemoryExpiry;
        validationFrames = config == null ? 1 : config.coreWorldsValidationFrames;
        auditSystemsPerPass = config == null ? 64 : config.coreWorldsAuditSystemsPerFrame;
        mutationHookCapability = CAPABILITY_UNKNOWN;
        resetForTests();
    }

    /** Called from transformed CoreScript.advance(float). */
    public static void update(SectorAPI sector) {
        CALLS.increment();
        if (!enabled) {
            Misc.computeCoreWorldsExtent();
            return;
        }
        if (!mutationHooksReady()) {
            CAPABILITY_FALLBACKS.increment();
            Misc.computeCoreWorldsExtent();
            return;
        }
        try {
            synchronized (LOCK) {
                updateChecked(sector);
            }
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            synchronized (LOCK) {
                resetState(null);
            }
            FALLBACKS.increment();
            try {
                PrepatcherLog.warn("Core-worlds extent cache failed; using vanilla computation: "
                        + failure.getClass().getName() + ": " + failure.getMessage());
            } catch (Throwable ignored) {
                // Preserve the vanilla fallback even when logging is unavailable.
            }
            Misc.computeCoreWorldsExtent();
        }
    }

    /** Epilogue of CampaignEngine.createStarSystem(String), after list insertion. */
    public static void starSystemAdded(SectorAPI sector, StarSystemAPI system) {
        if (!enabled || sector == null || system == null) return;
        try {
            synchronized (LOCK) {
                if (!indexInitialized || sectorRef.get() != sector) return;
                SystemEntry entry = findEntry(system);
                if (entry == null) {
                    entry = new SystemEntry(system);
                    allSystems.add(entry);
                }
                setCoreMembership(entry, system.hasTag(CORE_TAG));
                SYSTEM_ADD_EVENTS.increment();
            }
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            eventFailure();
        }
    }

    /** Epilogue of CampaignEngine.removeStarSystem(StarSystemAPI), after list removal. */
    public static void starSystemRemoved(SectorAPI sector, StarSystemAPI system) {
        if (!enabled || sector == null || system == null) return;
        try {
            synchronized (LOCK) {
                if (!indexInitialized || sectorRef.get() != sector) return;
                int index = findEntryIndex(system);
                if (index >= 0) removeEntryAt(index);
                SYSTEM_REMOVE_EVENTS.increment();
            }
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            eventFailure();
        }
    }

    /** Epilogue of BaseLocation.addTag(String). */
    public static void locationTagAdded(LocationAPI location, String tag) {
        if (!CORE_TAG.equals(tag) || !(location instanceof StarSystemAPI system)) return;
        tagChanged(system);
    }

    /** Epilogue of every normal return from BaseLocation.removeTag(String). */
    public static void locationTagRemoved(LocationAPI location, String tag) {
        if (!CORE_TAG.equals(tag) || !(location instanceof StarSystemAPI system)) return;
        tagChanged(system);
    }

    /** Epilogue of BaseLocation.clearTags(). */
    public static void locationTagsCleared(LocationAPI location) {
        if (!(location instanceof StarSystemAPI system)) return;
        tagChanged(system);
    }

    /** Authoritative CampaignEngine lifecycle boundary called by the cache-lifecycle runtime. */
    public static void campaignLifecycleReset() {
        synchronized (LOCK) {
            resetState(null);
        }
    }

    private static void tagChanged(StarSystemAPI system) {
        if (!enabled) return;
        try {
            synchronized (LOCK) {
                if (!indexInitialized || sectorRef.get() == null) return;
                SystemEntry entry = findEntry(system);
                if (entry == null) {
                    // A structurally installed CampaignEngine hook should have admitted every
                    // post-load system. Treat a missing entry as an anomaly and reconcile once.
                    requestRebuild();
                    return;
                }
                setCoreMembership(entry, system.hasTag(CORE_TAG));
                TAG_EVENTS.increment();
            }
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            eventFailure();
        }
    }

    private static void updateChecked(SectorAPI sector) {
        if (sector == null) {
            Misc.computeCoreWorldsExtent();
            return;
        }
        if (sectorRef.get() != sector) {
            resetState(sector);
        }
        if (!indexInitialized || rebuildRequested) {
            rebuildIndex(sector);
        }

        MemoryAPI memory = sector.getMemoryWithoutUpdate();
        boolean fastForwardIteration = sector.isFastForwardIteration();
        boolean memoryIntact = initialized && memoryObjectsIntact(memory);
        if (!memoryIntact) {
            if (initialized) INTEGRITY_REPAIRS.increment();
            computeAndPublish(memory, true);
            return;
        }

        if (skipFastForwardIterations && fastForwardIteration) {
            FAST_FORWARD_SKIPS.increment();
            return;
        }

        if (!fastForwardIteration) {
            outerFrames++;
            if (validationFrames > 1 && outerFrames % validationFrames != 0) {
                FRAME_VALIDATION_SKIPS.increment();
                return;
            }
            if (auditSystemsPerPass > 0) auditMembershipBatch();
        }

        if (checkMemoryExpiry && hasExpiry(memory)) {
            INTEGRITY_REPAIRS.increment();
            computeAndPublish(memory, true);
            return;
        }
        computeAndPublish(memory, false);
    }

    /**
     * The only full-sector traversal in the optimized path. It runs once per campaign identity
     * and again only after an anomalous missed mutation event or runtime failure recovery.
     */
    private static void rebuildIndex(SectorAPI sector) {
        List<StarSystemAPI> systems = sector.getStarSystems();
        if (systems == null) {
            throw new IllegalStateException("SectorAPI.getStarSystems() returned null");
        }
        // Build off to the side and publish the two lists only after the complete snapshot has
        // been validated. Recovery therefore cannot expose a partially rebuilt membership index.
        int visited = systems.size();
        ArrayList<SystemEntry> rebuiltAll = new ArrayList<>(visited);
        ArrayList<SystemEntry> rebuiltCore = new ArrayList<>();
        for (StarSystemAPI system : systems) {
            if (system == null) {
                throw new IllegalStateException("SectorAPI.getStarSystems() contained null");
            }
            SystemEntry entry = new SystemEntry(system);
            entry.core = system.hasTag(CORE_TAG);
            rebuiltAll.add(entry);
            if (entry.core) rebuiltCore.add(entry);
        }
        allSystems = rebuiltAll;
        coreSystems = rebuiltCore;
        auditCursor = 0;
        indexInitialized = true;
        rebuildRequested = false;
        SYSTEM_SCANS.increment();
        SYSTEMS_VISITED.add(visited);
    }

    /**
     * Audits only a bounded slice of the retained weak system index. This detects callers that
     * mutate BaseLocation.getTags() directly and therefore bypass addTag/removeTag hooks without
     * recreating SectorAPI.getStarSystems()'s O(S) defensive copy on every frame.
     */
    private static void auditMembershipBatch() {
        int remaining = Math.min(auditSystemsPerPass, allSystems.size());
        while (remaining > 0 && !allSystems.isEmpty()) {
            if (auditCursor >= allSystems.size()) {
                auditCursor = 0;
                MEMBERSHIP_AUDIT_SWEEPS.increment();
            }
            SystemEntry entry = allSystems.get(auditCursor);
            StarSystemAPI system = entry.system.get();
            if (system == null) {
                removeEntryAt(auditCursor);
                remaining--;
                continue;
            }
            // Core entries are checked every update by computeAndPublish(). Spend the bounded
            // audit budget on discovering new core membership among non-core systems.
            if (!entry.core) {
                MEMBERSHIP_AUDIT_CHECKS.increment();
                if (system.hasTag(CORE_TAG)) {
                    setCoreMembership(entry, true);
                }
            }
            auditCursor++;
            remaining--;
        }
        if (!allSystems.isEmpty() && auditCursor >= allSystems.size()) {
            auditCursor = 0;
            MEMBERSHIP_AUDIT_SWEEPS.increment();
        }
    }

    /** O(C) where C is the number of currently core-tagged systems. */
    private static void computeAndPublish(MemoryAPI memory, boolean force) {
        float minX = 0f;
        float minY = 0f;
        float maxX = 0f;
        float maxY = 0f;
        int checked = 0;
        for (int i = 0; i < coreSystems.size();) {
            SystemEntry entry = coreSystems.get(i);
            StarSystemAPI system = entry.system.get();
            if (system == null || !system.hasTag(CORE_TAG)) {
                entry.core = false;
                coreSystems.remove(i);
                MEMBERSHIP_CHANGES.increment();
                continue;
            }
            checked++;
            Vector2f location = system.getLocation();
            if (location == null) {
                throw new IllegalStateException("core star system has no hyperspace location");
            }
            minX = Math.min(minX, location.x);
            minY = Math.min(minY, location.y);
            maxX = Math.max(maxX, location.x);
            maxY = Math.max(maxY, location.y);
            i++;
        }
        CORE_SYSTEM_CHECKS.add(checked);

        float centerX = (minX + maxX) * 0.5f;
        float centerY = (minY + maxY) * 0.5f;
        int newMinXBits = Float.floatToRawIntBits(minX);
        int newMinYBits = Float.floatToRawIntBits(minY);
        int newMaxXBits = Float.floatToRawIntBits(maxX);
        int newMaxYBits = Float.floatToRawIntBits(maxY);
        int newCenterXBits = Float.floatToRawIntBits(centerX);
        int newCenterYBits = Float.floatToRawIntBits(centerY);
        boolean changed = !initialized
                || minXBits != newMinXBits || minYBits != newMinYBits
                || maxXBits != newMaxXBits || maxYBits != newMaxYBits
                || centerXBits != newCenterXBits || centerYBits != newCenterYBits;
        if (!force && !changed) {
            UNCHANGED_SKIPS.increment();
            return;
        }

        Vector2f min = new Vector2f(minX, minY);
        Vector2f max = new Vector2f(maxX, maxY);
        Vector2f center = new Vector2f(centerX, centerY);
        memory.set(MIN_KEY, min);
        memory.set(MAX_KEY, max);
        memory.set(CENTER_KEY, center);

        minRef = new WeakReference<>(min);
        maxRef = new WeakReference<>(max);
        centerRef = new WeakReference<>(center);
        minXBits = newMinXBits;
        minYBits = newMinYBits;
        maxXBits = newMaxXBits;
        maxYBits = newMaxYBits;
        centerXBits = newCenterXBits;
        centerYBits = newCenterYBits;
        initialized = true;
        PUBLISHES.increment();
    }

    private static void setCoreMembership(SystemEntry entry, boolean core) {
        if (entry.core == core) return;
        entry.core = core;
        if (core) {
            coreSystems.add(entry);
        } else {
            coreSystems.remove(entry);
        }
        MEMBERSHIP_CHANGES.increment();
    }

    private static SystemEntry findEntry(StarSystemAPI system) {
        int index = findEntryIndex(system);
        return index < 0 ? null : allSystems.get(index);
    }

    private static int findEntryIndex(StarSystemAPI system) {
        for (int i = 0; i < allSystems.size(); i++) {
            StarSystemAPI current = allSystems.get(i).system.get();
            if (current == system) return i;
        }
        return -1;
    }

    private static void removeEntryAt(int index) {
        SystemEntry entry = allSystems.remove(index);
        if (entry.core) {
            entry.core = false;
            coreSystems.remove(entry);
        }
        if (index < auditCursor) auditCursor--;
        if (auditCursor < 0 || auditCursor >= allSystems.size()) auditCursor = 0;
    }

    private static boolean memoryObjectsIntact(MemoryAPI memory) {
        return vectorIntact(memory.get(MIN_KEY), minRef.get(), minXBits, minYBits)
                && vectorIntact(memory.get(MAX_KEY), maxRef.get(), maxXBits, maxYBits)
                && vectorIntact(memory.get(CENTER_KEY), centerRef.get(),
                        centerXBits, centerYBits);
    }

    private static boolean vectorIntact(Object actual, Vector2f expected,
                                        int expectedXBits, int expectedYBits) {
        if (actual != expected || !(actual instanceof Vector2f vector)) return false;
        return Float.floatToRawIntBits(vector.x) == expectedXBits
                && Float.floatToRawIntBits(vector.y) == expectedYBits;
    }

    private static boolean hasExpiry(MemoryAPI memory) {
        return memory.getExpire(MIN_KEY) != -1f
                || memory.getExpire(MAX_KEY) != -1f
                || memory.getExpire(CENTER_KEY) != -1f;
    }

    private static boolean mutationHooksReady() {
        int cached = mutationHookCapability;
        if (cached != CAPABILITY_UNKNOWN) return cached == CAPABILITY_READY;

        String engine = System.getProperty(CAMPAIGN_ENGINE_STATUS);
        String location = System.getProperty(BASE_LOCATION_STATUS);
        if (successfulStatus(engine) && successfulStatus(location)) {
            mutationHookCapability = CAPABILITY_READY;
            return true;
        }
        if (terminalFailureStatus(engine) || terminalFailureStatus(location)) {
            mutationHookCapability = CAPABILITY_FAILED;
            try {
                PrepatcherLog.warn("Core-worlds incremental index disabled because mutation hooks "
                        + "are incomplete: CampaignEngine=" + engine
                        + ", BaseLocation=" + location + "; using vanilla computation");
            } catch (Throwable ignored) {
                // Capability failure still remains fail-closed when logging is unavailable.
            }
        }
        return false;
    }

    private static boolean successfulStatus(String status) {
        return "APPLIED".equals(status) || "ALREADY_APPLIED".equals(status);
    }

    private static boolean terminalFailureStatus(String status) {
        return status != null && !successfulStatus(status);
    }

    private static void requestRebuild() {
        if (!rebuildRequested) REBUILD_REQUESTS.increment();
        rebuildRequested = true;
    }

    private static void eventFailure() {
        EVENT_FAILURES.increment();
        try {
            synchronized (LOCK) {
                requestRebuild();
            }
        } catch (Throwable ignored) {
            // The next update still has its exception/fallback boundary.
        }
    }

    private static void resetState(SectorAPI sector) {
        sectorRef = new WeakReference<>(sector);
        minRef = new WeakReference<>(null);
        maxRef = new WeakReference<>(null);
        centerRef = new WeakReference<>(null);
        // Drop both backing arrays at campaign/lifecycle boundaries instead of retaining the
        // largest sector's wrapper capacity for the rest of the process.
        allSystems = new ArrayList<>();
        coreSystems = new ArrayList<>();
        auditCursor = 0;
        minXBits = minYBits = maxXBits = maxYBits = centerXBits = centerYBits = 0;
        outerFrames = 0;
        indexInitialized = false;
        rebuildRequested = false;
        initialized = false;
    }

    public static void resetForTests() {
        synchronized (LOCK) {
            resetState(null);
        }
    }

    static String statsAndResetFragment() {
        int tracked;
        int core;
        synchronized (LOCK) {
            tracked = allSystems.size();
            core = coreSystems.size();
        }
        return ", coreWorldsCalls=" + CALLS.sumThenReset()
                + ", coreWorldsSystemScans=" + SYSTEM_SCANS.sumThenReset()
                + ", coreWorldsSystemsVisited=" + SYSTEMS_VISITED.sumThenReset()
                + ", coreWorldsCoreSystemChecks=" + CORE_SYSTEM_CHECKS.sumThenReset()
                + ", coreWorldsMembershipAuditChecks="
                + MEMBERSHIP_AUDIT_CHECKS.sumThenReset()
                + ", coreWorldsMembershipAuditSweeps="
                + MEMBERSHIP_AUDIT_SWEEPS.sumThenReset()
                + ", coreWorldsMembershipChanges=" + MEMBERSHIP_CHANGES.sumThenReset()
                + ", coreWorldsSystemAddEvents=" + SYSTEM_ADD_EVENTS.sumThenReset()
                + ", coreWorldsSystemRemoveEvents=" + SYSTEM_REMOVE_EVENTS.sumThenReset()
                + ", coreWorldsTagEvents=" + TAG_EVENTS.sumThenReset()
                + ", coreWorldsRebuildRequests=" + REBUILD_REQUESTS.sumThenReset()
                + ", coreWorldsCapabilityFallbacks=" + CAPABILITY_FALLBACKS.sumThenReset()
                + ", coreWorldsFastForwardSkips=" + FAST_FORWARD_SKIPS.sumThenReset()
                + ", coreWorldsFrameValidationSkips="
                + FRAME_VALIDATION_SKIPS.sumThenReset()
                + ", coreWorldsUnchangedSkips=" + UNCHANGED_SKIPS.sumThenReset()
                + ", coreWorldsPublishes=" + PUBLISHES.sumThenReset()
                + ", coreWorldsIntegrityRepairs=" + INTEGRITY_REPAIRS.sumThenReset()
                + ", coreWorldsEventFailures=" + EVENT_FAILURES.sumThenReset()
                + ", coreWorldsFallbacks=" + FALLBACKS.sumThenReset()
                + ", coreWorldsTrackedSystems=" + tracked
                + ", coreWorldsTrackedCoreSystems=" + core;
    }

    private static final class SystemEntry {
        final WeakReference<StarSystemAPI> system;
        boolean core;

        SystemEntry(StarSystemAPI system) {
            this.system = new WeakReference<>(system);
        }
    }
}
