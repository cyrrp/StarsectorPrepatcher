package com.starsector.prepatcher.agent.templates;

import com.fs.starfarer.api.StarsectorPrepatcherHooks;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;

/**
 * Compile-time bytecode template copied into BaseIndustry by the unified
 * transformer. This class is never instantiated at runtime.
 */
public abstract class BaseIndustryDormantTemplate extends BaseIndustry {
  private static final int STATE_UNKNOWN = 0;
  private static final int STATE_DISABLED = 1;
  private static final int STATE_ACTIVE = 2;
  private static final int STATE_DORMANT = 3;

  /** Replaced by the transformer with market.noOpIndustryAuditFrames. */
  private static final int AUDIT_FRAMES_PLACEHOLDER = 0x5A17CAFE;

  private transient int spp$marketNoOpState;
  private transient int spp$marketNoOpCountdown;

  private void spp$baseIndustryRawAdvance(float amount) {
    throw new AssertionError("template placeholder");
  }

  @Override
  public void advance(float amount) {
    int state = spp$marketNoOpState;
    if (state == STATE_DORMANT && !building && !wasDisrupted) {
      int countdown = spp$marketNoOpCountdown;
      if (countdown > 0) {
        spp$marketNoOpCountdown = countdown - 1;
        return;
      }
    }

    // Keep the complete vanilla method as the source of truth. The fast
    // path only skips calls after a full call proved the instance dormant.
    spp$baseIndustryRawAdvance(amount);

    state = spp$marketNoOpState;
    if (state == STATE_DISABLED) return;
    if (state == STATE_UNKNOWN && !StarsectorPrepatcherHooks.isBaseIndustryDormantFastPathEligible(this)) {
      spp$marketNoOpState = STATE_DISABLED;
      spp$marketNoOpCountdown = 0;
      return;
    }

    if (!building && !wasDisrupted) {
      spp$marketNoOpState = STATE_DORMANT;
      spp$marketNoOpCountdown = AUDIT_FRAMES_PLACEHOLDER;
    } else {
      spp$marketNoOpState = STATE_ACTIVE;
      spp$marketNoOpCountdown = 0;
    }
  }
}
