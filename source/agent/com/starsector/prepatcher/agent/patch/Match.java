package com.starsector.prepatcher.agent.patch;

/** Outcome of {@link StructuredPatch#match}: a proven plan, or a refusal with its reason. */
public final class Match<P> {
  public final P plan;
  public final String reason;
  public final boolean alreadyApplied;

  private Match(P plan, String reason, boolean alreadyApplied) {
    this.plan = plan;
    this.reason = reason;
    this.alreadyApplied = alreadyApplied;
  }

  public static <P> Match<P> plan(P plan) {
    return new Match<>(plan, null, false);
  }

  /** The complete result of this patch is already present and verified. */
  public static <P> Match<P> alreadyApplied(String reason) {
    return new Match<>(null, reason, true);
  }

  /** The class does not carry the structure this patch requires. */
  public static <P> Match<P> incompatible(String reason) {
    return new Match<>(null, reason, false);
  }
}
