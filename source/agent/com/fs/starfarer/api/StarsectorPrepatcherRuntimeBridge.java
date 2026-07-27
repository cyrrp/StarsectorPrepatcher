package com.fs.starfarer.api;

import com.starsector.prepatcher.agent.PrepatcherConfig;
import com.starsector.prepatcher.agent.PrepatcherLog;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.BiFunction;

/** Loader-neutral initialization and native AoTD ABI boundary. */
public final class StarsectorPrepatcherRuntimeBridge {
    /** AoTD SchedulerBridge.DIRTY_STRUCTURE; kept local to avoid a mod-loader link. */
    private static final int AOTD_DIRTY_STRUCTURE = 1;

    public static final int AOTD_CONTRACT_ABI = 1;
    public static final long AOTD_CAPABILITY_CONTRACT_HANDSHAKE = 1L;
    public static final long AOTD_CAPABILITY_NATIVE_DELIVERY_EVENTS = 1L << 1;
    public static final long AOTD_CAPABILITY_NATIVE_MUTATION_BOUNDARIES = 1L << 2;
    public static final long AOTD_CAPABILITY_MARKET_GENERATIONS = 1L << 3;
    public static final long AOTD_CAPABILITY_CLEAN_DEFICIT_SEMANTICS = 1L << 4;
    public static final long AOTD_CAPABILITY_AUTHORITATIVE_MARKET_STATE = 1L << 5;
    public static final long AOTD_CAPABILITY_PURE_PRICE_OFFLOAD = 1L << 6;
    public static final long AOTD_CAPABILITY_GLOBAL_PHASE_COORDINATION = 1L << 7;
    public static final long AOTD_CAPABILITY_RUNTIME_EPOCH_COORDINATION = 1L << 8;

    private static final String AOTD_MOD_ID = "aotd_theory_of_toolbox";
    private static final long AOTD_SUPPORTED_CAPABILITIES =
            AOTD_CAPABILITY_CONTRACT_HANDSHAKE
                    | AOTD_CAPABILITY_NATIVE_DELIVERY_EVENTS
                    | AOTD_CAPABILITY_NATIVE_MUTATION_BOUNDARIES
                    | AOTD_CAPABILITY_MARKET_GENERATIONS
                    | AOTD_CAPABILITY_CLEAN_DEFICIT_SEMANTICS
                    | AOTD_CAPABILITY_AUTHORITATIVE_MARKET_STATE
                    | AOTD_CAPABILITY_PURE_PRICE_OFFLOAD
                    | AOTD_CAPABILITY_GLOBAL_PHASE_COORDINATION
                    | AOTD_CAPABILITY_RUNTIME_EPOCH_COORDINATION;
    private static final Object AOTD_CONTRACT_LOCK = new Object();
    private static final Object AOTD_MARKET_LOCK = new Object();
    private static final ReferenceQueue<Object> AOTD_MARKET_QUEUE = new ReferenceQueue<>();
    private static final Map<IdentityWeakReference, AoTDMarketState> AOTD_MARKET_STATES =
            new HashMap<>();
    private static final AtomicLong AOTD_DELIVERY_SEQUENCE = new AtomicLong();
    private static final AtomicLong AOTD_MUTATION_SEQUENCE = new AtomicLong();
    private static final AtomicLong AOTD_GLOBAL_SEQUENCE = new AtomicLong();
    private static final Object AOTD_GLOBAL_LOCK = new Object();
    private static long aotdGlobalToken;
    private static int aotdGlobalDepth;
    private static int aotdGlobalReasonMask;
    private static long aotdGlobalGeneration;
    private static long aotdCampaignEpoch = 1L;
    private static long aotdEconomyEpoch = 1L;
    private static long aotdGlobalCampaignEpoch;
    private static long aotdGlobalEconomyEpoch;
    private static long aotdStaleGlobalBoundaries;
    private static final LinkedHashSet<Long> AOTD_STALE_GLOBAL_TOKENS =
            new LinkedHashSet<>();
    private static final int AOTD_STALE_GLOBAL_TOKEN_LIMIT = 64;
    private static final AtomicLong AOTD_DELIVERY_LISTENER_FAILURES = new AtomicLong();
    private static final LongAdder AOTD_CONDITION_ONLY_DELIVERIES_IGNORED = new LongAdder();
    private static final LongAdder AOTD_OTHER_NON_ECONOMY_DELIVERIES_IGNORED = new LongAdder();
    private static final AtomicLong AOTD_STALE_MARKET_BOUNDARY_CLOSES = new AtomicLong();
    private static final AtomicLong AOTD_DUPLICATE_MARKET_BOUNDARY_CLOSES = new AtomicLong();
    private static final AtomicLong AOTD_MARKET_BOUNDARY_TOKEN_MISMATCHES = new AtomicLong();
    private static final AtomicLong AOTD_UNKNOWN_MARKET_BOUNDARY_CLOSES = new AtomicLong();
    private static final LinkedHashSet<Long> AOTD_STALE_MARKET_TOKENS =
            new LinkedHashSet<>();
    private static final int AOTD_STALE_MARKET_TOKEN_LIMIT = 256;

    // Synchronous, finally-cleared UI call context. Object[] avoids adding a
    // nested runtime payload class and is retained only by the campaign thread
    // for the duration of reportPlayerOpenedMarket(). Layout: token, market, parent.
    private static final ThreadLocal<Object[]> AOTD_OPENING_MARKET_CONTEXT =
            new ThreadLocal<>();
    private static final AtomicLong AOTD_OPENING_MARKET_SEQUENCE = new AtomicLong();
    private static final LongAdder AOTD_OPENING_MARKET_BEGINS = new LongAdder();
    private static final LongAdder AOTD_OPENING_MARKET_CONSUMES = new LongAdder();
    private static final LongAdder AOTD_OPENING_MARKET_TOKEN_MISMATCHES = new LongAdder();

    private static volatile boolean aotdContractRegistered;
    private static volatile String aotdForkVersion = "";
    private static volatile long aotdDeclaredCapabilities;
    private static volatile long aotdNegotiatedCapabilities;
    private static volatile Consumer<Object> aotdDeliveryListener;
    private static volatile String aotdDeliveryListenerStatus = "unregistered";
    private static volatile BiFunction<Object, Object, Object> aotdDeficitResolver;
    private static volatile boolean aotdCleanDeficitConfigured;

    private StarsectorPrepatcherRuntimeBridge() {}

    public static void configure(Object rawConfig, Path modRoot) {
        if (!(rawConfig instanceof PrepatcherConfig config)) {
            String actual = rawConfig == null ? "null" : rawConfig.getClass().getName();
            throw new IllegalArgumentException("Unexpected prepatcher configuration type: " + actual);
        }
        aotdCleanDeficitConfigured = config.aotdCleanDeficitPath;
        StarsectorPrepatcherHooks.configure(config, modRoot);
        StarsectorPrepatcherCoreWorldsRuntime.configure(config);
        StarsectorPrepatcherHyperspaceHooks.configure(config);
        StarsectorPrepatcherPresentationHooks.configure(config);
    }

    /** Compatibility overload for forks without delivery callbacks. */
    public static long registerAoTDForkContract(
            String modId, int abiVersion, String forkVersion, long declaredCapabilities) {
        return registerAoTDForkContract(
                modId, abiVersion, forkVersion, declaredCapabilities, null, null);
    }

    /** Compatibility overload for forks without deficit resolution. */
    public static long registerAoTDForkContract(
            String modId, int abiVersion, String forkVersion, long declaredCapabilities,
            Consumer<Object> deliveryListener) {
        return registerAoTDForkContract(modId, abiVersion, forkVersion,
                declaredCapabilities, deliveryListener, null);
    }

    /** Registers the fork contract and its loader-neutral callbacks. */
    public static long registerAoTDForkContract(
            String modId, int abiVersion, String forkVersion, long declaredCapabilities,
            Consumer<Object> deliveryListener,
            BiFunction<Object, Object, Object> deficitResolver) {
        if (!AOTD_MOD_ID.equals(modId) || abiVersion != AOTD_CONTRACT_ABI
                || forkVersion == null || forkVersion.isBlank()
                || (declaredCapabilities & AOTD_CAPABILITY_CONTRACT_HANDSHAKE) == 0L) {
            System.setProperty("starsector.prepatcher.aotdContract", "rejected");
            PrepatcherLog.warn("Rejected AoTD fork contract: modId=" + modId
                    + ", abi=" + abiVersion + ", fork=" + forkVersion
                    + ", declared=0x" + Long.toHexString(declaredCapabilities));
            return 0L;
        }
        long negotiated = declaredCapabilities & AOTD_SUPPORTED_CAPABILITIES;
        if ((negotiated & AOTD_CAPABILITY_NATIVE_DELIVERY_EVENTS) != 0L
                && deliveryListener == null) {
            negotiated &= ~AOTD_CAPABILITY_NATIVE_DELIVERY_EVENTS;
        }
        if ((negotiated & AOTD_CAPABILITY_CLEAN_DEFICIT_SEMANTICS) != 0L
                && (!aotdCleanDeficitConfigured || deficitResolver == null)) {
            negotiated &= ~AOTD_CAPABILITY_CLEAN_DEFICIT_SEMANTICS;
        }
        synchronized (AOTD_CONTRACT_LOCK) {
            if (aotdContractRegistered
                    && (!aotdForkVersion.equals(forkVersion)
                    || aotdDeclaredCapabilities != declaredCapabilities)) {
                System.setProperty("starsector.prepatcher.aotdContract",
                        "conflicting-registration");
                PrepatcherLog.warn("Conflicting AoTD fork contract registration: existing="
                        + aotdForkVersion + "/0x"
                        + Long.toHexString(aotdDeclaredCapabilities)
                        + ", incoming=" + forkVersion + "/0x"
                        + Long.toHexString(declaredCapabilities));
                return 0L;
            }
            aotdContractRegistered = true;
            aotdForkVersion = forkVersion;
            aotdDeclaredCapabilities = declaredCapabilities;
            aotdNegotiatedCapabilities = negotiated;
            aotdDeliveryListener = deliveryListener;
            aotdDeliveryListenerStatus = deliveryListener == null
                    ? "not-negotiated" : "active";
            aotdDeficitResolver = deficitResolver;
        }
        System.setProperty("starsector.prepatcher.aotdContract", "active");
        System.setProperty("starsector.prepatcher.aotdForkVersion", forkVersion);
        System.setProperty("starsector.prepatcher.aotdDeclaredCapabilities",
                "0x" + Long.toHexString(declaredCapabilities));
        System.setProperty("starsector.prepatcher.aotdNegotiatedCapabilities",
                "0x" + Long.toHexString(negotiated));
        PrepatcherLog.info("AoTD fork contract active: abi=" + abiVersion
                + ", fork=" + forkVersion
                + ", declared=0x" + Long.toHexString(declaredCapabilities)
                + ", negotiated=0x" + Long.toHexString(negotiated));
        return negotiated;
    }

    public static long getAoTDNegotiatedCapabilities() {
        return aotdNegotiatedCapabilities;
    }

    public static String getAoTDForkContractStatus() {
        if (!aotdContractRegistered) return "unregistered";
        int markets;
        synchronized (AOTD_MARKET_LOCK) {
            expungeAoTDMarketsLocked();
            markets = AOTD_MARKET_STATES.size();
        }
        return "active; abi=" + AOTD_CONTRACT_ABI
                + "; fork=" + aotdForkVersion
                + "; declared=0x" + Long.toHexString(aotdDeclaredCapabilities)
                + "; negotiated=0x" + Long.toHexString(aotdNegotiatedCapabilities)
                + "; trackedMarkets=" + markets
                + "; deficitResolver=" + (aotdDeficitResolver != null)
                + "; deliveryListener=" + aotdDeliveryListenerStatus
                + "; callbackFailures=" + AOTD_DELIVERY_LISTENER_FAILURES.get()
                + "; conditionOnlyDeliveriesIgnored="
                + AOTD_CONDITION_ONLY_DELIVERIES_IGNORED.sum()
                + "; otherNonEconomyDeliveriesIgnored="
                + AOTD_OTHER_NON_ECONOMY_DELIVERIES_IGNORED.sum()
                + "; staleMarketBoundaryCloses="
                + AOTD_STALE_MARKET_BOUNDARY_CLOSES.get()
                + "; duplicateMarketBoundaryCloses="
                + AOTD_DUPLICATE_MARKET_BOUNDARY_CLOSES.get()
                + "; marketBoundaryTokenMismatches="
                + AOTD_MARKET_BOUNDARY_TOKEN_MISMATCHES.get()
                + "; unknownMarketBoundaryCloses="
                + AOTD_UNKNOWN_MARKET_BOUNDARY_CLOSES.get()
                + "; openingMarketBegins=" + AOTD_OPENING_MARKET_BEGINS.sum()
                + "; openingMarketConsumes=" + AOTD_OPENING_MARKET_CONSUMES.sum()
                + "; openingMarketTokenMismatches="
                + AOTD_OPENING_MARKET_TOKEN_MISMATCHES.sum();
    }

    /** Called by the clean BaseIndustry wrapper. Null means use preserved vanilla code. */
    public static Object resolveAoTDMaxDeficit(Object industry, String[] commodityIds) {
        if ((aotdNegotiatedCapabilities & AOTD_CAPABILITY_CLEAN_DEFICIT_SEMANTICS) == 0L) {
            return null;
        }
        BiFunction<Object, Object, Object> resolver = aotdDeficitResolver;
        if (resolver == null) {
            throw new IllegalStateException("AoTD clean deficit capability is active without a resolver");
        }
        Object result = resolver.apply(industry, commodityIds);
        if (result == null) {
            throw new IllegalStateException("AoTD deficit resolver returned null");
        }
        return result;
    }

    /** Called by Hooks only after a real Market.advance callback returned. */
    public static void publishAoTDMarketTimeDelivered(
            Object market, float deliveredAmount, int origin) {
        if (market == null || !aotdContractRegistered) return;
        Consumer<Object> listener;
        synchronized (AOTD_MARKET_LOCK) {
            AoTDMarketState state = stateForLocked(market, true);
            state.deliveredGeneration = nextPositive(state.deliveredGeneration);
            state.lastDeliverySequence = AOTD_DELIVERY_SEQUENCE.incrementAndGet();
            state.lastDeliveredAmount = deliveredAmount;
            state.lastDeliveryOrigin = origin;
            listener = aotdDeliveryListener;
        }
        if ((aotdNegotiatedCapabilities & AOTD_CAPABILITY_NATIVE_DELIVERY_EVENTS) == 0L
                || listener == null) return;
        try {
            listener.accept(market);
        } catch (LinkageError failure) {
            long failures = AOTD_DELIVERY_LISTENER_FAILURES.incrementAndGet();
            boolean disabled = false;
            synchronized (AOTD_CONTRACT_LOCK) {
                if (aotdDeliveryListener == listener) {
                    aotdDeliveryListener = null;
                    aotdNegotiatedCapabilities &= ~AOTD_CAPABILITY_NATIVE_DELIVERY_EVENTS;
                    aotdDeliveryListenerStatus = "disabled-linkage:"
                            + failure.getClass().getName();
                    disabled = true;
                }
            }
            if (disabled) {
                System.setProperty("starsector.prepatcher.aotdContract",
                        "delivery-listener-disabled");
                System.setProperty("starsector.prepatcher.aotdNegotiatedCapabilities",
                        "0x" + Long.toHexString(aotdNegotiatedCapabilities));
                PrepatcherLog.error("AoTD delivery listener disabled after linkage failure (#"
                        + failures + "); native delivery events capability was removed for "
                        + "this session. AoTD will observe the live capability mask and "
                        + "fall back to generation resynchronization.", failure);
            }
        } catch (Throwable failure) {
            long failures = AOTD_DELIVERY_LISTENER_FAILURES.incrementAndGet();
            if (failures <= 4L || (failures & (failures - 1L)) == 0L) {
                PrepatcherLog.warn("AoTD delivery listener failed open (#" + failures
                        + "): " + failure);
            }
        }
    }

    public static long getAoTDMarketDeliveredGeneration(Object market) {
        return readMarketLong(market, 0);
    }

    public static long getAoTDMarketLastDeliverySequence(Object market) {
        return readMarketLong(market, 1);
    }

    public static float getAoTDMarketLastDeliveredAmount(Object market) {
        if (market == null) return 0f;
        synchronized (AOTD_MARKET_LOCK) {
            AoTDMarketState state = stateForLocked(market, false);
            return state == null ? 0f : state.lastDeliveredAmount;
        }
    }

    public static long getAoTDMarketStructuralGeneration(Object market) {
        return readMarketLong(market, 2);
    }

    private static long readMarketLong(Object market, int field) {
        if (market == null) return 0L;
        synchronized (AOTD_MARKET_LOCK) {
            AoTDMarketState state = stateForLocked(market, false);
            if (state == null) return 0L;
            return switch (field) {
                case 0 -> state.deliveredGeneration;
                case 1 -> state.lastDeliverySequence;
                case 2 -> state.structuralGeneration;
                default -> 0L;
            };
        }
    }

    public static long beforeAoTDMarketMutation(Object market, int reasonMask) {
        requireAoTDCapability(AOTD_CAPABILITY_NATIVE_MUTATION_BOUNDARIES);
        if (market == null) return 0L;

        // A nested source boundary shares the already-flushed outer temporal cut.
        // Avoid re-entering the scheduler replay path for every helper mutation.
        synchronized (AOTD_MARKET_LOCK) {
            AoTDMarketState state = stateForLocked(market, true);
            if (state.mutationDepth > 0) {
                state.mutationReasonMask |= reasonMask;
                state.mutationDepth++;
                return state.mutationToken;
            }
        }

        // Exact replay belongs to Hooks because it owns scheduler state. The
        // state is checked again afterwards because replay may invoke campaign
        // callbacks that open and close their own source boundary.
        StarsectorPrepatcherHooks.flushPendingMarketBeforeAoTDMutation(market);
        synchronized (AOTD_MARKET_LOCK) {
            AoTDMarketState state = stateForLocked(market, true);
            if (state.mutationDepth == 0) {
                state.mutationToken = AOTD_MUTATION_SEQUENCE.incrementAndGet();
                state.mutationReasonMask = reasonMask;
            } else {
                state.mutationReasonMask |= reasonMask;
            }
            state.mutationDepth++;
            return state.mutationToken;
        }
    }

    public static void afterAoTDMarketMutation(
            long token, Object market, int dirtyMask, long sourceGeneration) {
        requireAoTDCapability(AOTD_CAPABILITY_NATIVE_MUTATION_BOUNDARIES);
        if (market == null || token == 0L) return;
        synchronized (AOTD_MARKET_LOCK) {
            if (AOTD_STALE_MARKET_TOKENS.remove(token)) {
                AOTD_STALE_MARKET_BOUNDARY_CLOSES.incrementAndGet();
                return;
            }
            AoTDMarketState state = stateForLocked(market, false);
            if (state == null) {
                long count = AOTD_UNKNOWN_MARKET_BOUNDARY_CLOSES.incrementAndGet();
                logMarketBoundaryIssue("UNKNOWN_MARKET", count, token, market, null);
                return;
            }
            if (state.mutationDepth <= 0) {
                long count = AOTD_DUPLICATE_MARKET_BOUNDARY_CLOSES.incrementAndGet();
                logMarketBoundaryIssue("DUPLICATE_CLOSE", count, token, market, state);
                return;
            }
            if (state.mutationToken != token) {
                long count = AOTD_MARKET_BOUNDARY_TOKEN_MISMATCHES.incrementAndGet();
                logMarketBoundaryIssue("TOKEN_MISMATCH", count, token, market, state);
                return;
            }
            state.mutationDirtyMask |= dirtyMask;
            state.lastSourceGeneration = sourceGeneration;
            state.mutationDepth--;
            if (state.mutationDepth == 0) {
                if ((state.mutationDirtyMask & AOTD_DIRTY_STRUCTURE) != 0) {
                    state.structuralGeneration = nextPositive(state.structuralGeneration);
                }
                state.lastCommittedDirtyMask = state.mutationDirtyMask;
                state.mutationDirtyMask = 0;
                state.mutationReasonMask = 0;
                state.mutationToken = 0L;
            }
        }
    }

    /** Opens the exact CampaignEngine market-open call context. */
    public static long beginAoTDOpeningMarket(Object market) {
        if (market == null) return 0L;
        long token = AOTD_OPENING_MARKET_SEQUENCE.updateAndGet(
                StarsectorPrepatcherRuntimeBridge::nextPositive);
        Object[] parent = AOTD_OPENING_MARKET_CONTEXT.get();
        AOTD_OPENING_MARKET_CONTEXT.set(
                new Object[] {Long.valueOf(token), market, parent});
        AOTD_OPENING_MARKET_BEGINS.increment();
        return token;
    }

    /**
     * Returns the market once and clears the strong market reference immediately.
     * The wrapper's finally block later removes the remaining token frame.
     */
    public static Object consumeAoTDOpeningMarket() {
        Object[] context = AOTD_OPENING_MARKET_CONTEXT.get();
        if (context == null) return null;
        Object market = context[1];
        context[1] = null;
        if (market != null) AOTD_OPENING_MARKET_CONSUMES.increment();
        return market;
    }

    /** Closes the context without retaining the market after abnormal exit. */
    public static void endAoTDOpeningMarket(long token) {
        if (token == 0L) return;
        Object[] context = AOTD_OPENING_MARKET_CONTEXT.get();
        if (context == null) return;
        Object rawToken = context[0];
        long actual = rawToken instanceof Long ? ((Long) rawToken).longValue() : 0L;
        context[1] = null;
        if (actual != token) {
            AOTD_OPENING_MARKET_TOKEN_MISMATCHES.increment();
            AOTD_OPENING_MARKET_CONTEXT.remove();
            return;
        }
        Object parent = context[2];
        context[0] = null;
        context[2] = null;
        if (parent instanceof Object[]) {
            AOTD_OPENING_MARKET_CONTEXT.set((Object[]) parent);
        } else {
            AOTD_OPENING_MARKET_CONTEXT.remove();
        }
    }

    public static void publishAoTDRuntimeEpoch(long campaignEpoch, long economyEpoch) {
        requireAoTDCapability(AOTD_CAPABILITY_RUNTIME_EPOCH_COORDINATION);
        if (campaignEpoch <= 0L || economyEpoch <= 0L) {
            throw new IllegalArgumentException("AoTD epochs must be positive");
        }
        boolean changed;
        synchronized (AOTD_GLOBAL_LOCK) {
            changed = aotdCampaignEpoch != campaignEpoch || aotdEconomyEpoch != economyEpoch;
            if (!changed) return;
            if (aotdGlobalDepth > 0 && aotdGlobalToken != 0L) {
                aotdStaleGlobalBoundaries++;
                AOTD_STALE_GLOBAL_TOKENS.add(aotdGlobalToken);
                while (AOTD_STALE_GLOBAL_TOKENS.size() > AOTD_STALE_GLOBAL_TOKEN_LIMIT) {
                    Long oldest = AOTD_STALE_GLOBAL_TOKENS.iterator().next();
                    AOTD_STALE_GLOBAL_TOKENS.remove(oldest);
                }
            }
            aotdCampaignEpoch = campaignEpoch;
            aotdEconomyEpoch = economyEpoch;
            aotdGlobalToken = 0L;
            aotdGlobalDepth = 0;
            aotdGlobalReasonMask = 0;
            aotdGlobalCampaignEpoch = 0L;
            aotdGlobalEconomyEpoch = 0L;
        }
        synchronized (AOTD_MARKET_LOCK) {
            for (AoTDMarketState state : AOTD_MARKET_STATES.values()) {
                if (state != null && state.mutationDepth > 0 && state.mutationToken != 0L) {
                    AOTD_STALE_MARKET_TOKENS.add(state.mutationToken);
                }
            }
            while (AOTD_STALE_MARKET_TOKENS.size() > AOTD_STALE_MARKET_TOKEN_LIMIT) {
                Long oldest = AOTD_STALE_MARKET_TOKENS.iterator().next();
                AOTD_STALE_MARKET_TOKENS.remove(oldest);
            }
            AOTD_MARKET_STATES.clear();
            while (AOTD_MARKET_QUEUE.poll() != null) { }
        }
        System.setProperty("starsector.prepatcher.aotdCampaignEpoch",
                Long.toString(campaignEpoch));
        System.setProperty("starsector.prepatcher.aotdEconomyEpoch",
                Long.toString(economyEpoch));
        PrepatcherLog.info("AoTD runtime epoch published: campaign=" + campaignEpoch
                + ", economy=" + economyEpoch);
    }

    public static long getAoTDCampaignEpoch() { return aotdCampaignEpoch; }
    public static long getAoTDEconomyEpoch() { return aotdEconomyEpoch; }
    public static long getAoTDStaleGlobalBoundaryCount() {
        synchronized (AOTD_GLOBAL_LOCK) { return aotdStaleGlobalBoundaries; }
    }

    public static long beforeAoTDGlobalBoundary(int reasonMask, boolean hardFlush) {
        requireAoTDCapability(AOTD_CAPABILITY_GLOBAL_PHASE_COORDINATION);
        synchronized (AOTD_GLOBAL_LOCK) {
            if (aotdGlobalDepth > 0) {
                aotdGlobalDepth++;
                aotdGlobalReasonMask |= reasonMask;
                return aotdGlobalToken;
            }
        }
        if (hardFlush) {
            StarsectorPrepatcherHooks.flushMarketSchedulerBeforeSave();
        }
        synchronized (AOTD_GLOBAL_LOCK) {
            if (aotdGlobalDepth == 0) {
                aotdGlobalToken = AOTD_GLOBAL_SEQUENCE.incrementAndGet();
                aotdGlobalReasonMask = reasonMask;
                aotdGlobalCampaignEpoch = aotdCampaignEpoch;
                aotdGlobalEconomyEpoch = aotdEconomyEpoch;
            } else {
                aotdGlobalReasonMask |= reasonMask;
            }
            aotdGlobalDepth++;
            return aotdGlobalToken;
        }
    }

    public static void afterAoTDGlobalBoundary(long token, long generation) {
        requireAoTDCapability(AOTD_CAPABILITY_GLOBAL_PHASE_COORDINATION);
        synchronized (AOTD_GLOBAL_LOCK) {
            if (token != 0L && AOTD_STALE_GLOBAL_TOKENS.remove(token)) {
                return;
            }
            if (token == 0L || token != aotdGlobalToken || aotdGlobalDepth <= 0) {
                PrepatcherLog.warn("Unbalanced AoTD global boundary: token=" + token);
                return;
            }
            if (aotdGlobalCampaignEpoch != aotdCampaignEpoch
                    || aotdGlobalEconomyEpoch != aotdEconomyEpoch) {
                aotdStaleGlobalBoundaries++;
                aotdGlobalDepth = 0;
                aotdGlobalToken = 0L;
                aotdGlobalReasonMask = 0;
                aotdGlobalCampaignEpoch = 0L;
                aotdGlobalEconomyEpoch = 0L;
                PrepatcherLog.warn("Rejected stale AoTD global boundary after epoch change");
                return;
            }
            aotdGlobalDepth--;
            if (aotdGlobalDepth == 0) {
                aotdGlobalGeneration = Math.max(aotdGlobalGeneration, generation);
                aotdGlobalToken = 0L;
                aotdGlobalReasonMask = 0;
            }
        }
    }

    /** Called by the MarketAPI-aware Hooks layer before the loader-neutral ABI. */
    public static void recordAoTDNonEconomyDelivery(boolean conditionOnly) {
        if (conditionOnly) AOTD_CONDITION_ONLY_DELIVERIES_IGNORED.increment();
        else AOTD_OTHER_NON_ECONOMY_DELIVERIES_IGNORED.increment();
    }

    private static void logMarketBoundaryIssue(
            String reason, long count, long token, Object market, AoTDMarketState state) {
        if (count > 4L && (count & (count - 1L)) != 0L) return;
        PrepatcherLog.warn("AoTD market mutation boundary " + reason + " (#" + count
                + "): token=" + token
                + ", activeToken=" + (state == null ? 0L : state.mutationToken)
                + ", depth=" + (state == null ? 0 : state.mutationDepth)
                + ", marketClass=" + (market == null ? "null" : market.getClass().getName())
                + ", identityHash=" + System.identityHashCode(market)
                + ", campaignEpoch=" + aotdCampaignEpoch
                + ", economyEpoch=" + aotdEconomyEpoch);
    }

    private static void requireAoTDCapability(long capability) {
        if ((aotdNegotiatedCapabilities & capability) != capability) {
            throw new IllegalStateException(
                    "AoTD capability was not negotiated: 0x" + Long.toHexString(capability));
        }
    }

    private static long nextPositive(long value) {
        long next = value + 1L;
        return next <= 0L ? 1L : next;
    }

    private static AoTDMarketState stateForLocked(Object market, boolean create) {
        expungeAoTDMarketsLocked();
        IdentityWeakReference lookup = new IdentityWeakReference(market);
        AoTDMarketState state = AOTD_MARKET_STATES.get(lookup);
        if (state == null && create) {
            state = new AoTDMarketState();
            AOTD_MARKET_STATES.put(
                    new IdentityWeakReference(market, AOTD_MARKET_QUEUE), state);
        }
        return state;
    }

    private static void expungeAoTDMarketsLocked() {
        IdentityWeakReference reference;
        while ((reference = (IdentityWeakReference) AOTD_MARKET_QUEUE.poll()) != null) {
            AOTD_MARKET_STATES.remove(reference);
        }
    }

    /** Loader-neutral registration endpoint used by the mod call-site transformer. */
    public static void registerDirectMarketCallSite(long siteId, String metadata) {
        StarsectorPrepatcherHooks.registerDirectMarketCallSite(siteId, metadata);
    }

    /** Writes pending-vs-delivered scheduler state without forcing a flush. */
    public static String dumpMarketSchedulerBaseline(String reason) {
        return StarsectorPrepatcherHooks.dumpMarketSchedulerBaseline(reason);
    }

    private static final class AoTDMarketState {
        long deliveredGeneration;
        long lastDeliverySequence;
        float lastDeliveredAmount;
        int lastDeliveryOrigin;
        long structuralGeneration;
        long lastSourceGeneration;
        long mutationToken;
        int mutationDepth;
        int mutationReasonMask;
        int mutationDirtyMask;
        int lastCommittedDirtyMask;
    }

    private static final class IdentityWeakReference extends WeakReference<Object> {
        private final int identityHash;

        IdentityWeakReference(Object referent) {
            super(referent);
            identityHash = System.identityHashCode(referent);
        }

        IdentityWeakReference(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            identityHash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() { return identityHash; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityWeakReference reference)) return false;
            Object left = get();
            Object right = reference.get();
            return left != null && left == right;
        }
    }
}
