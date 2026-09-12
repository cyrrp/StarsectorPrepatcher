package com.fs.starfarer.api;

import static com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge.AOTD_CAPABILITY_GLOBAL_PHASE_COORDINATION;
import static com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge.AOTD_CAPABILITY_NATIVE_MUTATION_BOUNDARIES;
import static com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge.AOTD_CAPABILITY_RUNTIME_EPOCH_COORDINATION;

import com.starsector.prepatcher.agent.PrepatcherLog;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** AoTD market/global boundary coordination and per-market runtime state. */
public final class StarsectorPrepatcherAotdRuntimeState {
  /** AoTD SchedulerBridge.DIRTY_STRUCTURE; kept local to avoid a mod-loader link. */
  private static final int AOTD_DIRTY_STRUCTURE = 1;

  private static final Object AOTD_MARKET_LOCK = new Object();
  private static final ReferenceQueue<Object> AOTD_MARKET_QUEUE = new ReferenceQueue<>();
  private static final Map<IdentityWeakReference, AoTDMarketState> AOTD_MARKET_STATES = new HashMap<>();
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
  private static final LinkedHashSet<Long> AOTD_STALE_GLOBAL_TOKENS = new LinkedHashSet<>();
  private static final int AOTD_STALE_GLOBAL_TOKEN_LIMIT = 64;
  private static final AtomicLong AOTD_STALE_MARKET_BOUNDARY_CLOSES = new AtomicLong();
  private static final AtomicLong AOTD_DUPLICATE_MARKET_BOUNDARY_CLOSES = new AtomicLong();
  private static final AtomicLong AOTD_MARKET_BOUNDARY_TOKEN_MISMATCHES = new AtomicLong();
  private static final AtomicLong AOTD_UNKNOWN_MARKET_BOUNDARY_CLOSES = new AtomicLong();
  private static final LinkedHashSet<Long> AOTD_STALE_MARKET_TOKENS = new LinkedHashSet<>();
  private static final int AOTD_STALE_MARKET_TOKEN_LIMIT = 256;

  private StarsectorPrepatcherAotdRuntimeState() {}

  static void recordMarketDelivery(Object market, float deliveredAmount, int origin) {
    synchronized (AOTD_MARKET_LOCK) {
      AoTDMarketState state = stateForLocked(market, true);
      state.deliveredGeneration = nextPositive(state.deliveredGeneration);
      state.lastDeliverySequence = AOTD_DELIVERY_SEQUENCE.incrementAndGet();
      state.lastDeliveredAmount = deliveredAmount;
      state.lastDeliveryOrigin = origin;
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
    StarsectorPrepatcherAotdContract.requireAoTDCapability(AOTD_CAPABILITY_NATIVE_MUTATION_BOUNDARIES);
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

    // The Hooks facade delegates exact replay to StarsectorPrepatcherMarketScheduler.
    // State is checked again afterwards because replay may invoke campaign
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

  public static void afterAoTDMarketMutation(long token, Object market, int dirtyMask, long sourceGeneration) {
    StarsectorPrepatcherAotdContract.requireAoTDCapability(AOTD_CAPABILITY_NATIVE_MUTATION_BOUNDARIES);
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

  public static void publishAoTDRuntimeEpoch(long campaignEpoch, long economyEpoch) {
    StarsectorPrepatcherAotdContract.requireAoTDCapability(AOTD_CAPABILITY_RUNTIME_EPOCH_COORDINATION);
    if (campaignEpoch <= 0L || economyEpoch <= 0L) {
      throw new IllegalArgumentException("AoTD epochs must be positive");
    }
    StarsectorPrepatcherUiEconomyRuntime.clearVanillaMarketOpenCoalescingToken();
    StarsectorPrepatcherUiEconomyRuntime.clearUiMarketMutationBatch();
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
      while (AOTD_MARKET_QUEUE.poll() != null) {}
    }
    System.setProperty("starsector.prepatcher.aotdCampaignEpoch", Long.toString(campaignEpoch));
    System.setProperty("starsector.prepatcher.aotdEconomyEpoch", Long.toString(economyEpoch));
    PrepatcherLog.info("AoTD runtime epoch published: campaign=" + campaignEpoch + ", economy=" + economyEpoch);
  }

  public static long getAoTDCampaignEpoch() { return aotdCampaignEpoch; }
  public static long getAoTDEconomyEpoch() { return aotdEconomyEpoch; }
  public static long getAoTDStaleGlobalBoundaryCount() {
    synchronized (AOTD_GLOBAL_LOCK) {
      return aotdStaleGlobalBoundaries;
    }
  }

  public static long beforeAoTDGlobalBoundary(int reasonMask, boolean hardFlush) {
    StarsectorPrepatcherAotdContract.requireAoTDCapability(AOTD_CAPABILITY_GLOBAL_PHASE_COORDINATION);
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
    StarsectorPrepatcherAotdContract.requireAoTDCapability(AOTD_CAPABILITY_GLOBAL_PHASE_COORDINATION);
    synchronized (AOTD_GLOBAL_LOCK) {
      if (token != 0L && AOTD_STALE_GLOBAL_TOKENS.remove(token)) { return; }
      if (token == 0L || token != aotdGlobalToken || aotdGlobalDepth <= 0) {
        PrepatcherLog.warn("Unbalanced AoTD global boundary: token=" + token);
        return;
      }
      if (aotdGlobalCampaignEpoch != aotdCampaignEpoch || aotdGlobalEconomyEpoch != aotdEconomyEpoch) {
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

  static int trackedMarketCount() {
    synchronized (AOTD_MARKET_LOCK) {
      expungeAoTDMarketsLocked();
      return AOTD_MARKET_STATES.size();
    }
  }

  static long staleMarketBoundaryCloses() {
    return AOTD_STALE_MARKET_BOUNDARY_CLOSES.get();
  }

  static long duplicateMarketBoundaryCloses() {
    return AOTD_DUPLICATE_MARKET_BOUNDARY_CLOSES.get();
  }

  static long marketBoundaryTokenMismatches() {
    return AOTD_MARKET_BOUNDARY_TOKEN_MISMATCHES.get();
  }

  static long unknownMarketBoundaryCloses() {
    return AOTD_UNKNOWN_MARKET_BOUNDARY_CLOSES.get();
  }

  private static void logMarketBoundaryIssue(String reason, long count, long token, Object market,
      AoTDMarketState state) {
    if (count > 4L && (count & (count - 1L)) != 0L) return;
    PrepatcherLog.warn("AoTD market mutation boundary " + reason + " (#" + count + "): token=" + token
        + ", activeToken=" + (state == null ? 0L : state.mutationToken) + ", depth="
        + (state == null ? 0 : state.mutationDepth) + ", marketClass="
        + (market == null ? "null" : market.getClass().getName()) + ", identityHash=" + System.identityHashCode(market)
        + ", campaignEpoch=" + aotdCampaignEpoch + ", economyEpoch=" + aotdEconomyEpoch);
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
      AOTD_MARKET_STATES.put(new IdentityWeakReference(market, AOTD_MARKET_QUEUE), state);
    }
    return state;
  }

  private static void expungeAoTDMarketsLocked() {
    IdentityWeakReference reference;
    while ((reference = (IdentityWeakReference) AOTD_MARKET_QUEUE.poll()) != null) {
      AOTD_MARKET_STATES.remove(reference);
    }
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
    public int hashCode() {
      return identityHash;
    }

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
