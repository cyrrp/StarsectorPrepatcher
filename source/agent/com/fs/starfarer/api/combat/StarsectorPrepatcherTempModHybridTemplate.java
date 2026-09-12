package com.fs.starfarer.api.combat;

import com.fs.starfarer.api.StarsectorPrepatcherHooks;
import com.fs.starfarer.api.StarsectorPrepatcherTempModHooks;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bytecode template copied into MutableStatWithTempMods by the unified transformer.
 *
 * <p>This class is never instantiated or defined in the game loader. Keeping the
 * implementation as ordinary Java gives the verifier normal stack-map frames while
 * the transformer still retains every vanilla method body and the target class ABI.</p>
 */
public final class StarsectorPrepatcherTempModHybridTemplate extends MutableStat {
  private static final int FLAG_VALID = 1;
  private static final int FLAG_EXPOSED = 2;
  private static final int FLAG_DISABLED = 4;
  private static final int FLAG_OWNER_CHECKED = 8;

  private LinkedHashMap<String, MutableStatWithTempMods.TemporaryStatMod> tempMods;

  private transient float spp$tempModHybridDeferredDays;
  private transient float spp$tempModHybridTimeToNext;
  private transient float spp$tempModHybridScheduledMin;
  private transient int spp$tempModHybridScheduledMinCount;
  private transient int spp$tempModHybridKnownSize;
  private transient int spp$tempModHybridFlags;

  private StarsectorPrepatcherTempModHybridTemplate(float base) {
    super(base);
  }

  // These placeholders are never copied. Their call sites are remapped to the
  // exact retained vanilla methods on MutableStatWithTempMods.
  private void spp$originalTempModAdvance(float days) {
    throw new AssertionError("template placeholder");
  }

  private Map<String, MutableStatWithTempMods.TemporaryStatMod> spp$rawGetMods() {
    throw new AssertionError("template placeholder");
  }

  private MutableStatWithTempMods.TemporaryStatMod spp$rawGetMod(String source, float duration) {
    throw new AssertionError("template placeholder");
  }

  private void spp$rawRemoveTemporaryMod(String source) {
    throw new AssertionError("template placeholder");
  }

  private boolean spp$rawHasMod(String source) {
    throw new AssertionError("template placeholder");
  }

  private Object spp$rawWriteReplace() {
    throw new AssertionError("template placeholder");
  }

  public void advance(float days) {
    LinkedHashMap<String, MutableStatWithTempMods.TemporaryStatMod> mods = tempMods;
    if (mods == null || mods.isEmpty()) {
      spp$tempModHybridResetForEmpty();
      return;
    }
    if (spp$tempModHybridUseVanilla()) {
      spp$originalTempModAdvance(days);
      return;
    }

    // The scheduler is monotonic only for finite positive deltas. Preserve
    // the retained vanilla body for all awkward edge values.
    if (!(days > 0f) || !Float.isFinite(days)) {
      spp$tempModHybridSynchronize();
      if ((spp$tempModHybridFlags & (FLAG_EXPOSED | FLAG_DISABLED)) != 0) {
        spp$originalTempModAdvance(days);
        return;
      }
      spp$originalTempModAdvance(days);
      spp$tempModHybridRecomputeSchedule();
      return;
    }

    // New/load-from-vanilla state: one ordinary map pass both performs this
    // frame's vanilla subtraction and creates the first deadline schedule.
    if ((spp$tempModHybridFlags & FLAG_VALID) == 0) {
      spp$tempModHybridApplyElapsed(days, false, false);
      return;
    }

    float newDeferred = spp$tempModHybridDeferredDays + days;
    if (!Float.isFinite(newDeferred)) {
      spp$tempModHybridSynchronize();
      if ((spp$tempModHybridFlags & (FLAG_EXPOSED | FLAG_DISABLED)) != 0) {
        spp$originalTempModAdvance(days);
        return;
      }
      spp$originalTempModAdvance(days);
      spp$tempModHybridRecomputeSchedule();
      return;
    }

    spp$tempModHybridDeferredDays = newDeferred;
    // This repeated float subtraction mirrors vanilla for the nearest expiry.
    spp$tempModHybridTimeToNext -= days;
    if (spp$tempModHybridScheduledMinCount <= 0 || spp$tempModHybridTimeToNext > 0f) { return; }

    // Do not compare aggregate deferredDays with scheduledMin: addition and
    // repeated subtraction round differently and that comparison can expire
    // a modifier one frame early. The countdown is authoritative.
    spp$tempModHybridApplyElapsed(spp$tempModHybridDeferredDays, true, false);
  }

  public Map<String, MutableStatWithTempMods.TemporaryStatMod> getMods() {
    if (!spp$tempModHybridUseVanilla()) {
      spp$tempModHybridSynchronize();
    }
    Map<String, MutableStatWithTempMods.TemporaryStatMod> result = spp$rawGetMods();
    if ((spp$tempModHybridFlags & (FLAG_EXPOSED | FLAG_DISABLED)) == 0) {
      spp$tempModHybridFlags |= FLAG_EXPOSED;
      spp$tempModHybridDeferredDays = 0f;
      spp$tempModHybridFlags &= ~FLAG_VALID;
      spp$tempModHybridKnownSize = result.size();
      StarsectorPrepatcherHooks.markCommodityTemporalStatExposed(this);
      StarsectorPrepatcherTempModHooks.recordHybridExternalExposure();
    }
    return result;
  }

  private MutableStatWithTempMods.TemporaryStatMod getMod(String source, float duration) {
    if (spp$tempModHybridUseVanilla()) { return spp$rawGetMod(source, duration); }
    spp$tempModHybridSynchronize();
    if ((spp$tempModHybridFlags & (FLAG_EXPOSED | FLAG_DISABLED)) != 0) { return spp$rawGetMod(source, duration); }

    MutableStatWithTempMods.TemporaryStatMod previous = tempMods == null ? null : tempMods.get(source);
    boolean existed = previous != null;
    float oldTime = existed ? previous.timeRemaining : Float.NaN;
    MutableStatWithTempMods.TemporaryStatMod result = spp$rawGetMod(source, duration);
    spp$tempModHybridAfterSet(existed, oldTime, duration);
    return result;
  }

  public void removeTemporaryMod(String source) {
    if (spp$tempModHybridUseVanilla()) {
      spp$rawRemoveTemporaryMod(source);
      return;
    }
    spp$tempModHybridSynchronize();
    if ((spp$tempModHybridFlags & (FLAG_EXPOSED | FLAG_DISABLED)) != 0) {
      spp$rawRemoveTemporaryMod(source);
      return;
    }

    MutableStatWithTempMods.TemporaryStatMod previous = tempMods == null ? null : tempMods.get(source);
    float oldTime = previous == null ? Float.NaN : previous.timeRemaining;
    spp$rawRemoveTemporaryMod(source);
    if (previous != null) spp$tempModHybridAfterRemoval(oldTime);
  }

  public boolean hasMod(String source) {
    // Expiry is processed synchronously in advance(), so membership does not
    // require materializing the remaining time of surviving modifiers.
    return spp$rawHasMod(source);
  }

  protected Object writeReplace() {
    if (!spp$tempModHybridUseVanilla()) {
      spp$tempModHybridSynchronize();
    }
    Object result = spp$rawWriteReplace();
    if (tempMods == null || tempMods.isEmpty()) spp$tempModHybridResetForEmpty();
    return result;
  }

  /** Returns true when this instance must use the retained vanilla implementation. */
  private boolean spp$tempModHybridUseVanilla() {
    int flags = spp$tempModHybridFlags;
    if ((flags & FLAG_OWNER_CHECKED) == 0) {
      flags |= FLAG_OWNER_CHECKED;
      // Subclasses may override or observe private implementation details.
      if ((Class<?>) getClass() != MutableStatWithTempMods.class) {
        float deferred = spp$tempModHybridDeferredDays;
        spp$tempModHybridDeferredDays = 0f;
        spp$tempModHybridTimeToNext = Float.POSITIVE_INFINITY;
        spp$tempModHybridScheduledMin = Float.POSITIVE_INFINITY;
        spp$tempModHybridScheduledMinCount = 0;
        spp$tempModHybridKnownSize = -1;
        spp$tempModHybridFlags = (flags | FLAG_DISABLED) & ~FLAG_VALID;
        if (deferred != 0f) spp$originalTempModAdvance(deferred);
        StarsectorPrepatcherTempModHooks.recordHybridSubclassFallback();
        return true;
      }
      spp$tempModHybridFlags = flags;
    }
    if ((flags & (FLAG_EXPOSED | FLAG_DISABLED)) != 0) return true;

    LinkedHashMap<String, MutableStatWithTempMods.TemporaryStatMod> mods = tempMods;
    if (mods != null && (spp$tempModHybridFlags & FLAG_VALID) == 0 && mods.getClass() != LinkedHashMap.class) {
      spp$tempModHybridDisableAndFlush();
      return true;
    }
    if (mods != null && (spp$tempModHybridFlags & FLAG_VALID) != 0 && spp$tempModHybridKnownSize != mods.size()) {
      spp$tempModHybridDisableAndFlush();
      return true;
    }
    return false;
  }

  /** Materializes pending time before mutation, observation or serialization. */
  private void spp$tempModHybridSynchronize() {
    if ((spp$tempModHybridFlags & (FLAG_EXPOSED | FLAG_DISABLED)) != 0) return;
    LinkedHashMap<String, MutableStatWithTempMods.TemporaryStatMod> mods = tempMods;
    if (mods == null || mods.isEmpty()) {
      spp$tempModHybridResetForEmpty();
      return;
    }
    if (mods.getClass() != LinkedHashMap.class) {
      spp$tempModHybridDisableAndFlush();
      return;
    }
    if ((spp$tempModHybridFlags & FLAG_VALID) != 0 && spp$tempModHybridKnownSize != mods.size()) {
      spp$tempModHybridDisableAndFlush();
      return;
    }

    if (spp$tempModHybridDeferredDays != 0f) {
      spp$tempModHybridApplyElapsed(spp$tempModHybridDeferredDays, false, true);
    } else if ((spp$tempModHybridFlags & FLAG_VALID) == 0) {
      spp$tempModHybridRecomputeSchedule();
    }
  }

  /**
   * Periodic integrity audit used only by the aggressive commodity active set.
   * It never exposes the live map. Deferred time is materialized once and a
   * same-size direct mutation of TemporaryStatMod state is folded into a fresh
   * nearest-expiry schedule. Exposed/disabled instances remain on vanilla.
   */
  private void spp$tempModHybridAuditForCommodity() {
    if (spp$tempModHybridUseVanilla()) return;
    LinkedHashMap<String, MutableStatWithTempMods.TemporaryStatMod> mods = tempMods;
    if (mods == null || mods.isEmpty()) {
      spp$tempModHybridResetForEmpty();
      return;
    }
    if (mods.getClass() != LinkedHashMap.class) {
      spp$tempModHybridDisableAndFlush();
      return;
    }
    if ((spp$tempModHybridFlags & FLAG_VALID) != 0 && spp$tempModHybridKnownSize != mods.size()) {
      spp$tempModHybridDisableAndFlush();
      return;
    }
    if (spp$tempModHybridDeferredDays != 0f) {
      spp$tempModHybridApplyElapsed(spp$tempModHybridDeferredDays, false, true);
    } else {
      spp$tempModHybridRecomputeSchedule();
    }
  }

  /** Flushes through retained vanilla code and permanently disables this instance. */
  private void spp$tempModHybridDisableAndFlush() {
    float deferred = spp$tempModHybridDeferredDays;
    spp$tempModHybridDeferredDays = 0f;
    spp$tempModHybridTimeToNext = Float.POSITIVE_INFINITY;
    spp$tempModHybridScheduledMin = Float.POSITIVE_INFINITY;
    spp$tempModHybridScheduledMinCount = 0;
    spp$tempModHybridKnownSize = -1;
    spp$tempModHybridFlags = (spp$tempModHybridFlags | FLAG_DISABLED) & ~FLAG_VALID;
    if (deferred != 0f) spp$originalTempModAdvance(deferred);
    StarsectorPrepatcherHooks.markCommodityTemporalStatExposed(this);
    StarsectorPrepatcherTempModHooks.recordHybridFailureFallback();
  }

  /**
   * Applies elapsed time in LinkedHashMap order, expires due entries in that
   * same order, and rebuilds the next deadline in the same pass.
   */
  private void spp$tempModHybridApplyElapsed(float elapsed, boolean forceScheduledDeadline, boolean synchronization) {
    LinkedHashMap<String, MutableStatWithTempMods.TemporaryStatMod> mods = tempMods;
    if (mods == null || mods.isEmpty()) {
      spp$tempModHybridResetForEmpty();
      return;
    }

    float scheduled = spp$tempModHybridScheduledMin;
    boolean force =
        forceScheduledDeadline && (spp$tempModHybridFlags & FLAG_VALID) != 0 && spp$tempModHybridScheduledMinCount > 0;
    float next = Float.POSITIVE_INFINITY;
    int nextCount = 0;
    int scanned = 0;
    int expiredCount = 0;

    Iterator<MutableStatWithTempMods.TemporaryStatMod> iterator = mods.values().iterator();
    while (iterator.hasNext()) {
      MutableStatWithTempMods.TemporaryStatMod mod = iterator.next();
      float before = mod.timeRemaining;
      float after = before - elapsed;
      mod.timeRemaining = after;
      scanned++;

      boolean expired = after <= 0f;
      // The float countdown proves all tied minima are due on this exact
      // call even when aggregate subtraction leaves a tiny positive value.
      if (!expired && force && before == scheduled) expired = true;
      if (expired) {
        iterator.remove();
        unmodify(mod.source);
        expiredCount++;
        continue;
      }

      if (spp$tempModHybridTrackable(after)) {
        if (nextCount == 0 || after < next) {
          next = after;
          nextCount = 1;
        } else if (after == next) {
          nextCount++;
        }
      }
    }

    spp$tempModHybridDeferredDays = 0f;
    spp$tempModHybridScheduledMin = next;
    spp$tempModHybridTimeToNext = next;
    spp$tempModHybridScheduledMinCount = nextCount;
    spp$tempModHybridKnownSize = mods.size();
    spp$tempModHybridFlags |= FLAG_VALID;

    if (synchronization) {
      StarsectorPrepatcherTempModHooks.recordHybridSynchronizationSweep(scanned, expiredCount);
    } else if (forceScheduledDeadline) {
      StarsectorPrepatcherTempModHooks.recordHybridExpirySweep(scanned, expiredCount);
    } else {
      StarsectorPrepatcherTempModHooks.recordHybridInitialSweep(scanned, expiredCount);
    }
    if (mods.isEmpty()) spp$tempModHybridResetForEmpty();
  }

  private void spp$tempModHybridRecomputeSchedule() {
    LinkedHashMap<String, MutableStatWithTempMods.TemporaryStatMod> mods = tempMods;
    if (mods == null || mods.isEmpty()) {
      spp$tempModHybridResetForEmpty();
      return;
    }
    if (mods.getClass() != LinkedHashMap.class) {
      spp$tempModHybridDisableAndFlush();
      return;
    }

    float next = Float.POSITIVE_INFINITY;
    int nextCount = 0;
    int scanned = 0;
    for (MutableStatWithTempMods.TemporaryStatMod mod : mods.values()) {
      float remaining = mod.timeRemaining;
      scanned++;
      if (!spp$tempModHybridTrackable(remaining)) continue;
      if (nextCount == 0 || remaining < next) {
        next = remaining;
        nextCount = 1;
      } else if (remaining == next) {
        nextCount++;
      }
    }

    spp$tempModHybridDeferredDays = 0f;
    spp$tempModHybridScheduledMin = next;
    spp$tempModHybridTimeToNext = next;
    spp$tempModHybridScheduledMinCount = nextCount;
    spp$tempModHybridKnownSize = mods.size();
    spp$tempModHybridFlags |= FLAG_VALID;
    StarsectorPrepatcherTempModHooks.recordHybridScheduleRebuild(scanned);
  }

  /** Incremental schedule update after creating or refreshing one modifier. */
  private void spp$tempModHybridAfterSet(boolean existed, float oldTime, float newTime) {
    if ((spp$tempModHybridFlags & (FLAG_EXPOSED | FLAG_DISABLED)) != 0) return;
    LinkedHashMap<String, MutableStatWithTempMods.TemporaryStatMod> mods = tempMods;
    if (mods == null || mods.isEmpty()) {
      spp$tempModHybridResetForEmpty();
      return;
    }
    spp$tempModHybridKnownSize = mods.size();
    if ((spp$tempModHybridFlags & FLAG_VALID) == 0 || spp$tempModHybridDeferredDays != 0f) {
      spp$tempModHybridRecomputeSchedule();
      return;
    }

    boolean oldWasMin = existed && spp$tempModHybridTrackable(oldTime) && spp$tempModHybridScheduledMinCount > 0
        && oldTime == spp$tempModHybridScheduledMin;
    if (oldWasMin) {
      if (newTime == oldTime) return;
      if (spp$tempModHybridTrackable(newTime) && newTime < oldTime) {
        spp$tempModHybridSetSoleMinimum(newTime);
        return;
      }
      if (spp$tempModHybridScheduledMinCount > 1) {
        spp$tempModHybridScheduledMinCount--;
        return;
      }
      spp$tempModHybridRecomputeSchedule();
      return;
    }

    if (!spp$tempModHybridTrackable(newTime)) return;
    if (spp$tempModHybridScheduledMinCount == 0 || newTime < spp$tempModHybridScheduledMin) {
      spp$tempModHybridSetSoleMinimum(newTime);
    } else if (newTime == spp$tempModHybridScheduledMin) {
      spp$tempModHybridScheduledMinCount++;
    }
  }

  private void spp$tempModHybridAfterRemoval(float removedTime) {
    if ((spp$tempModHybridFlags & (FLAG_EXPOSED | FLAG_DISABLED)) != 0) return;
    LinkedHashMap<String, MutableStatWithTempMods.TemporaryStatMod> mods = tempMods;
    if (mods == null || mods.isEmpty()) {
      spp$tempModHybridResetForEmpty();
      return;
    }
    spp$tempModHybridKnownSize = mods.size();
    if ((spp$tempModHybridFlags & FLAG_VALID) == 0 || spp$tempModHybridDeferredDays != 0f) {
      spp$tempModHybridRecomputeSchedule();
      return;
    }
    if (spp$tempModHybridTrackable(removedTime) && spp$tempModHybridScheduledMinCount > 0
        && removedTime == spp$tempModHybridScheduledMin) {
      if (spp$tempModHybridScheduledMinCount > 1) {
        spp$tempModHybridScheduledMinCount--;
      } else {
        spp$tempModHybridRecomputeSchedule();
      }
    }
  }

  private void spp$tempModHybridSetSoleMinimum(float value) {
    spp$tempModHybridScheduledMin = value;
    spp$tempModHybridTimeToNext = value;
    spp$tempModHybridScheduledMinCount = 1;
    spp$tempModHybridFlags |= FLAG_VALID;
  }

  private void spp$tempModHybridResetForEmpty() {
    spp$tempModHybridDeferredDays = 0f;
    spp$tempModHybridScheduledMin = Float.POSITIVE_INFINITY;
    spp$tempModHybridTimeToNext = Float.POSITIVE_INFINITY;
    spp$tempModHybridScheduledMinCount = 0;
    spp$tempModHybridKnownSize = tempMods == null ? 0 : tempMods.size();
    // Preserve owner/exposure/disabled mode while making empty state valid.
    spp$tempModHybridFlags |= FLAG_VALID;
  }

  private static boolean spp$tempModHybridTrackable(float value) {
    return !Float.isNaN(value) && value != Float.POSITIVE_INFINITY;
  }
}
