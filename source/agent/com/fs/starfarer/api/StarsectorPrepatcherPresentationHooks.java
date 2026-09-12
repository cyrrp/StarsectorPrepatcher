package com.fs.starfarer.api;

import com.starsector.prepatcher.agent.PrepatcherConfig;

import com.fs.graphics.anim.AnimationManager;
import com.fs.graphics.particle.DynamicParticleGroup;
import com.fs.graphics.util.Fader;
import com.fs.starfarer.api.SoundPlayerAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.impl.campaign.terrain.AuroraRenderer;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamEntityPlugin2;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2;
import com.fs.starfarer.api.util.FaderUtil;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.JitterUtil;
import com.fs.starfarer.api.util.WarpingSpriteRendererUtil;
import com.fs.starfarer.campaign.ActionIndicator;
import com.fs.starfarer.campaign.BackgroundAndStars;
import com.fs.starfarer.campaign.CampaignEngine;
import com.fs.starfarer.campaign.DynamicRingBand;
import com.fs.starfarer.campaign.E;
import com.fs.starfarer.campaign.JumpPoint;
import com.fs.starfarer.campaign.SensorContactIndicatorManager;
import com.fs.starfarer.campaign.fleet.CampaignFleetView;
import com.fs.starfarer.campaign.fleet.CampaignFloatingText;
import com.fs.starfarer.campaign.fleet.FleetAbilityRenderer;
import com.fs.starfarer.combat.entities.terrain.Planet;
import org.lwjgl.util.vector.Vector2f;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Runtime helpers for structurally matched fast-forward presentation coalescing. */
public final class StarsectorPrepatcherPresentationHooks {
  // Disabled until the target-loader bridge configures the payload. A missed
  // initialization or frame marker therefore preserves vanilla call cadence.
  private static volatile boolean enabled;
  private static volatile boolean metrics;
  private static volatile boolean simulationTime;
  private static final boolean TEST_MODE = bool("starsector.prepatcher.presentation.testMode", false);

  private static final ThreadLocal<FrameState> STATE = ThreadLocal.withInitial(FrameState::new);
  private static final ThreadLocal<Boolean> TEST_FAST_FORWARD = ThreadLocal.withInitial(() -> Boolean.FALSE);

  private static final AtomicLong FRAMES = new AtomicLong();
  private static final AtomicLong SUBSTEPS = new AtomicLong();
  private static final AtomicLong SKIPPED_ACTION = new AtomicLong();
  private static final AtomicLong SKIPPED_LOCATION = new AtomicLong();
  private static final AtomicLong SKIPPED_TEXT = new AtomicLong();
  private static final AtomicLong SKIPPED_ENTITY_UI = new AtomicLong();
  private static final AtomicLong SKIPPED_FLEET = new AtomicLong();
  private static final AtomicLong SKIPPED_ANIMATIONS = new AtomicLong();
  private static final AtomicLong SKIPPED_SENSOR = new AtomicLong();
  private static final AtomicLong SKIPPED_CELESTIAL = new AtomicLong();
  private static final AtomicLong SKIPPED_AURORA = new AtomicLong();
  private static final AtomicLong SKIPPED_SLIPSTREAM = new AtomicLong();
  private static final AtomicLong SKIPPED_SOUND = new AtomicLong();
  private static final AtomicLong SKIPPED_JITTER = new AtomicLong();
  private static final AtomicLong SKIPPED_GATE = new AtomicLong();
  private static final AtomicLong SKIPPED_EMITTERS = new AtomicLong();
  private static final AtomicLong FLAG_MISMATCHES = new AtomicLong();

  private StarsectorPrepatcherPresentationHooks() {}

  static void configure(PrepatcherConfig config) {
    enabled = config.fastForwardEnabled && config.fastForwardFrameMarker;
    metrics = config.fastForwardMetrics;
    simulationTime = config.fastForwardVisualTimeSimulation;
  }

  /**
   * The supplied obfuscated build contains a public method literally named "new".
   * Java source cannot call that identifier, so the structural presentation bridge uses a cached
   * MethodHandle adapted to (Object,float)void. Resolution is lazy and occurs only
   * when an entity actually owns a selection indicator.
   */
  private static final class SelectionIndicatorBridge {
    static final MethodHandle ADVANCE = resolve();

    private static MethodHandle resolve() {
      try {
        Class<?> type = Class.forName("com.fs.starfarer.campaign.ui.oOO0", false,
            StarsectorPrepatcherPresentationHooks.class.getClassLoader());
        Method method = type.getDeclaredMethod("new", float.class);
        method.trySetAccessible();
        return MethodHandles.lookup().unreflect(method)
            .asType(MethodType.methodType(void.class, Object.class, float.class));
      } catch (Throwable failure) {
        throw new ExceptionInInitializerError(failure);
      }
    }
  }

  public static void beginOuterFrame(int totalSteps) {
    FrameState state = STATE.get();
    state.totalSteps = Math.max(1, totalSteps);
    state.index = -1;
    state.active = enabled && state.totalSteps > 1;
    state.flagMatches = true;
    if (metrics) FRAMES.incrementAndGet();
  }

  public static void beforeSubstep(int index, int totalSteps) {
    FrameState state = STATE.get();
    int sanitizedTotal = Math.max(1, totalSteps);
    if (state.totalSteps != sanitizedTotal) {
      // A changed loop shape means the marker contract no longer matches.
      // Latch fail-open for the rest of this outer frame.
      state.flagMatches = false;
      state.totalSteps = sanitizedTotal;
      state.active = enabled && sanitizedTotal > 1;
    }
    state.index = index;
    boolean currentFlagMatches = validateFastForwardFlag(index);
    state.flagMatches &= currentFlagMatches;
    if (!currentFlagMatches && metrics) FLAG_MISMATCHES.incrementAndGet();
    if (metrics) SUBSTEPS.incrementAndGet();
  }

  public static void endOuterFrame() {
    FrameState state = STATE.get();
    state.totalSteps = 1;
    state.index = -1;
    state.active = false;
    state.flagMatches = true;
    if (metrics) {
      long frames = FRAMES.get();
      if (frames > 0 && frames % 600L == 0L) {
        System.out.println("[StarsectorPrepatcher presentation metrics] " + metricsSnapshot());
      }
    }
  }

  public static void advanceActionIndicator(ActionIndicator target, float amount) {
    requireNonNull(target, "ActionIndicator");
    if (shouldRunPresentation()) target.advance(effectiveAmount(amount));
    else skipped(SKIPPED_ACTION);
  }

  public static void advanceLocationLightFader(Fader target, float amount) {
    requireNonNull(target, "location light Fader");
    if (shouldRunPresentation()) target.advance(effectiveAmount(amount));
    else skipped(SKIPPED_LOCATION);
  }

  public static void advanceBackground(BackgroundAndStars target, float amount) {
    requireNonNull(target, "BackgroundAndStars");
    if (shouldRunPresentation()) target.advance(effectiveAmount(amount));
    else skipped(SKIPPED_LOCATION);
  }

  public static void advanceParticleGroup(DynamicParticleGroup target, float amount) {
    requireNonNull(target, "DynamicParticleGroup");
    if (shouldRunPresentation()) target.advance(effectiveAmount(amount));
    else skipped(SKIPPED_LOCATION);
  }

  public static void advanceFloatingText(CampaignFloatingText target, float amount) {
    requireNonNull(target, "CampaignFloatingText");
    if (shouldRunPresentation()) target.advance(effectiveAmount(amount));
    else skipped(SKIPPED_TEXT);
  }

  public static void advanceSelectionIndicator(Object target, float amount) {
    requireNonNull(target, "selection indicator");
    if (!shouldRunPresentation()) {
      skipped(SKIPPED_ENTITY_UI);
      return;
    }
    try {
      SelectionIndicatorBridge.ADVANCE.invokeExact(target, effectiveAmount(amount));
    } catch (RuntimeException | Error failure) {
      throw failure;
    } catch (Throwable failure) {
      throw new IllegalStateException("selection indicator bridge failed", failure);
    }
  }

  public static void advanceFleetView(CampaignFleetView target, float amount) {
    requireNonNull(target, "CampaignFleetView");
    if (shouldRunPresentation()) target.advance(effectiveAmount(amount));
    else skipped(SKIPPED_FLEET);
  }

  public static void clearFleetView(CampaignFleetView target) {
    requireNonNull(target, "CampaignFleetView");
    if (shouldRunPresentation()) target.clear();
    else skipped(SKIPPED_FLEET);
  }

  public static void updateFleetAbilityLayers(FleetAbilityRenderer target) {
    requireNonNull(target, "FleetAbilityRenderer");
    if (shouldRunPresentation()) target.updateLayers();
    else skipped(SKIPPED_FLEET);
  }

  public static void advanceSensorRangeIndicator(E target, float amount) {
    requireNonNull(target, "fleet sensor range indicator");
    if (shouldRunPresentation()) target.Ò00000(effectiveAmount(amount));
    else skipped(SKIPPED_FLEET);
  }

  public static void advanceFleetPulseFader(Fader target, float amount) {
    requireNonNull(target, "fleet no-combat pulse fader");
    if (shouldRunPresentation()) target.advance(effectiveAmount(amount));
    else skipped(SKIPPED_FLEET);
  }

  public static void advanceSensorContactIndicator(SensorContactIndicatorManager.SensorContactIndicator target,
      float amount) {
    requireNonNull(target, "SensorContactIndicator");
    if (shouldRunPresentation()) target.advance(effectiveAmount(amount));
    else skipped(SKIPPED_SENSOR);
  }

  public static void advanceAnimationManager(AnimationManager target, float amount) {
    requireNonNull(target, "AnimationManager");
    if (shouldRunPresentation()) target.advanceAll(effectiveAmount(amount));
    else skipped(SKIPPED_ANIMATIONS);
  }

  /** Experimental: these faders participate in visibility/despawn timing. */
  public static void advanceSensorFader(Fader target, float amount) {
    requireNonNull(target, "sensor Fader");
    if (shouldRunPresentation()) target.advance(effectiveAmount(amount));
    else skipped(SKIPPED_SENSOR);
  }

  public static void advancePlanetGraphics(Planet target, float amount) {
    requireNonNull(target, "planet graphics");
    if (shouldRunPresentation()) target.advance(effectiveAmount(amount));
    else skipped(SKIPPED_CELESTIAL);
  }

  public static void advanceDynamicRingBand(DynamicRingBand target, float amount) {
    requireNonNull(target, "DynamicRingBand");
    if (shouldRunPresentation()) target.advance(effectiveAmount(amount));
    else skipped(SKIPPED_CELESTIAL);
  }

  public static void advanceJumpRingData(JumpPoint.RingData target, float amount) {
    requireNonNull(target, "JumpPoint.RingData");
    if (shouldRunPresentation()) target.advance(effectiveAmount(amount));
    else skipped(SKIPPED_CELESTIAL);
  }

  public static void advanceJumpCoronaData(JumpPoint.CoronaData target, float amount) {
    requireNonNull(target, "JumpPoint.CoronaData");
    if (shouldRunPresentation()) target.advance(effectiveAmount(amount));
    else skipped(SKIPPED_CELESTIAL);
  }

  public static void advanceAurora(AuroraRenderer target, float amount) {
    requireNonNull(target, "AuroraRenderer");
    if (shouldRunPresentation()) target.advance(effectiveAmount(amount));
    else skipped(SKIPPED_AURORA);
  }

  public static void addSlipstreamTerrainParticles(SlipstreamTerrainPlugin2 target) {
    requireNonNull(target, "SlipstreamTerrainPlugin2");
    if (shouldRunPresentation()) target.addParticles();
    else skipped(SKIPPED_SLIPSTREAM);
  }

  public static void advanceSlipstreamTerrainParticles(SlipstreamTerrainPlugin2 target, float amount) {
    requireNonNull(target, "SlipstreamTerrainPlugin2");
    if (shouldRunPresentation()) target.advanceParticles(effectiveAmount(amount));
    else skipped(SKIPPED_SLIPSTREAM);
  }

  public static void addSlipstreamEntityParticles(SlipstreamEntityPlugin2 target) {
    requireNonNull(target, "SlipstreamEntityPlugin2");
    if (shouldRunPresentation()) target.addParticles();
    else skipped(SKIPPED_SLIPSTREAM);
  }

  public static void advanceSlipstreamEntityParticles(SlipstreamEntityPlugin2 target, float amount) {
    requireNonNull(target, "SlipstreamEntityPlugin2");
    if (shouldRunPresentation()) target.advanceParticles(effectiveAmount(amount));
    else skipped(SKIPPED_SLIPSTREAM);
  }

  public static void suppressMusic(CampaignUIAPI target, float amount) {
    requireNonNull(target, "CampaignUIAPI");
    if (shouldRunPresentation()) target.suppressMusic(amount);
    else skipped(SKIPPED_SOUND);
  }

  public static void playLoop(SoundPlayerAPI target, String id, Object source, float pitch, float volume,
      Vector2f location, Vector2f velocity) {
    requireNonNull(target, "SoundPlayerAPI");
    if (shouldRunPresentation()) target.playLoop(id, source, pitch, volume, location, velocity);
    else skipped(SKIPPED_SOUND);
  }

  public static void playLoopWithRange(SoundPlayerAPI target, String id, Object source, float pitch, float volume,
      Vector2f location, Vector2f velocity, float minRange, float maxRange) {
    requireNonNull(target, "SoundPlayerAPI");
    if (shouldRunPresentation()) {
      target.playLoop(id, source, pitch, volume, location, velocity, minRange, maxRange);
    } else skipped(SKIPPED_SOUND);
  }

  public static void applyLowPassFilter(SoundPlayerAPI target, float gain, float gainHF) {
    requireNonNull(target, "SoundPlayerAPI");
    if (shouldRunPresentation()) target.applyLowPassFilter(gain, gainHF);
    else skipped(SKIPPED_SOUND);
  }

  public static void advanceGateFader(FaderUtil target, float amount) {
    requireNonNull(target, "gate FaderUtil");
    if (shouldRunPresentation()) target.advance(effectiveAmount(amount));
    else skipped(SKIPPED_GATE);
  }

  public static void advanceGateWarp(WarpingSpriteRendererUtil target, float amount) {
    requireNonNull(target, "gate warp renderer");
    if (shouldRunPresentation()) target.advance(effectiveAmount(amount));
    else skipped(SKIPPED_GATE);
  }

  public static void updateGateJitter(JitterUtil target) {
    requireNonNull(target, "JitterUtil");
    if (shouldRunPresentation()) target.updateSeed();
    else skipped(SKIPPED_JITTER);
  }

  /** Experimental: changes visual RNG/cadence, but not campaign mechanics. */
  public static void advanceEmitterInterval(IntervalUtil target, float amount) {
    requireNonNull(target, "particle IntervalUtil");
    if (shouldRunPresentation()) target.advance(effectiveAmount(amount));
    else skipped(SKIPPED_EMITTERS);
  }

  public static String metricsSnapshot() {
    return "frames=" + FRAMES.get() + ", substeps=" + SUBSTEPS.get() + ", skippedAction=" + SKIPPED_ACTION.get()
        + ", skippedLocation=" + SKIPPED_LOCATION.get() + ", skippedText=" + SKIPPED_TEXT.get() + ", skippedEntityUi="
        + SKIPPED_ENTITY_UI.get() + ", skippedFleet=" + SKIPPED_FLEET.get() + ", skippedAnimations="
        + SKIPPED_ANIMATIONS.get() + ", skippedSensor=" + SKIPPED_SENSOR.get() + ", skippedCelestial="
        + SKIPPED_CELESTIAL.get() + ", skippedAurora=" + SKIPPED_AURORA.get() + ", skippedSlipstream="
        + SKIPPED_SLIPSTREAM.get() + ", skippedSound=" + SKIPPED_SOUND.get() + ", skippedJitter=" + SKIPPED_JITTER.get()
        + ", skippedGate=" + SKIPPED_GATE.get() + ", skippedEmitters=" + SKIPPED_EMITTERS.get() + ", flagMismatches="
        + FLAG_MISMATCHES.get();
  }

  public static void testSetFastForwardFlag(boolean value) {
    if (!TEST_MODE) throw new IllegalStateException("presentation test mode is not enabled");
    TEST_FAST_FORWARD.set(value);
  }

  public static boolean testShouldRunPresentation() {
    if (!TEST_MODE) throw new IllegalStateException("presentation test mode is not enabled");
    return shouldRunPresentation();
  }

  public static float testEffectiveAmount(float amount) {
    if (!TEST_MODE) throw new IllegalStateException("presentation test mode is not enabled");
    return effectiveAmount(amount);
  }

  public static boolean testResolveSelectionIndicatorBridge() {
    if (!TEST_MODE) throw new IllegalStateException("presentation test mode is not enabled");
    return SelectionIndicatorBridge.ADVANCE != null;
  }

  private static boolean shouldRunPresentation() {
    FrameState state = STATE.get();
    if (!enabled || !state.active || !state.flagMatches) return true;
    if (state.index < 0 || state.index >= state.totalSteps) return true;
    return state.index == state.totalSteps - 1;
  }

  private static float effectiveAmount(float amount) {
    FrameState state = STATE.get();
    if (simulationTime && state.active && state.flagMatches && state.index == state.totalSteps - 1) {
      return amount * state.totalSteps;
    }
    return amount;
  }

  private static boolean validateFastForwardFlag(int index) {
    try {
      boolean actual;
      if (TEST_MODE) {
        actual = TEST_FAST_FORWARD.get();
      } else {
        CampaignEngine engine = CampaignEngine.getInstance();
        if (engine == null) return false;
        actual = engine.isFastForwardIteration();
      }
      return actual == (index > 0);
    } catch (Throwable ignored) {
      return false;
    }
  }

  private static void requireNonNull(Object value, String name) {
    if (value == null) throw new NullPointerException(name);
  }

  private static void skipped(AtomicLong counter) {
    if (metrics) counter.incrementAndGet();
  }

  private static boolean bool(String key, boolean defaultValue) {
    String value = System.getProperty(key);
    if (value == null || value.isBlank()) return defaultValue;
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "1", "true", "yes", "on" -> true;
      case "0", "false", "no", "off" -> false;
      default -> defaultValue;
    };
  }

  private static final class FrameState {
    int totalSteps = 1;
    int index = -1;
    boolean active;
    boolean flagMatches = true;
  }
}
