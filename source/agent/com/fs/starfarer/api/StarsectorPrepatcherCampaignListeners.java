package com.fs.starfarer.api;

import com.fs.starfarer.campaign.CampaignEngine;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Campaign change-listener bookkeeping across map open/close transitions. */
final class StarsectorPrepatcherCampaignListeners {

  static volatile Map<Object, CampaignListenerState> CAMPAIGN_LISTENER_STATES =
      Collections.synchronizedMap(new WeakHashMap<>());
  // ---------------------------------------------------------------------
  // Closed-map campaign bookkeeping.
  //
  // CampaignEngine.advance() calls readdChangeListeners() every frame. The
  // vanilla method walks hyperspace and every star system and only writes the
  // same ObjectRepository listener reference again. Creation/removal APIs
  // already update listeners immediately, so retain the public vanilla method
  // unchanged and throttle only the call site in advance(). Structural changes
  // trigger an immediate refresh; a periodic audit covers direct list mutation
  // by mods.
  // ---------------------------------------------------------------------

  public static void readdChangeListenersIfNeeded(Object engine, List<?> systems, Object hyperspace) {
    if (engine == null) return;
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (c == null || !c.campaignListenerThrottle
        || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, engine)) {
      invokeReaddChangeListeners(engine);
      return;
    }

    int size = -1;
    Object first = null;
    Object last = null;
    long now = 0L;
    boolean refresh = true;
    boolean compatibilityFallback = false;
    Map<Object, CampaignListenerState> listenerStates = CAMPAIGN_LISTENER_STATES;
    try {
      size = systems == null ? -1 : systems.size();
      first = size > 0 ? systems.get(0) : null;
      last = size > 0 ? systems.get(size - 1) : null;
      now = System.nanoTime();
      long auditNs = c.campaignListenerAuditMs * 1_000_000L;

      synchronized (listenerStates) {
        if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, engine)) {
          compatibilityFallback = true;
        } else {
          CampaignListenerState state = listenerStates.get(engine);
          if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, engine)) {
            compatibilityFallback = true;
          } else {
            refresh = state == null || state.systems != systems || state.hyperspace != hyperspace
                || state.systemCount != size || state.firstSystem != first || state.lastSystem != last
                || (auditNs > 0L && now - state.refreshedAtNanos >= auditNs);
          }
        }
      }
    } catch (Throwable ex) {
      compatibilityFallback = true;
    }

    if (compatibilityFallback) {
      // Keep the original call outside the catch boundary so an exception
      // from game/mod code cannot cause a retry.
      invokeReaddChangeListeners(engine);
      return;
    }

    if (!refresh && StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, engine)) {
      StarsectorPrepatcherMetrics.CAMPAIGN_LISTENER_SKIPS.increment();
      return;
    }

    // Keep exceptions from the vanilla method exact: do not catch and retry it.
    invokeReaddChangeListeners(engine);
    try {
      synchronized (listenerStates) {
        if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, engine)) return;
        CampaignListenerState state = listenerStates.computeIfAbsent(engine, ignored -> new CampaignListenerState());
        if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, engine)) {
          listenerStates.clear();
        } else {
          state.systems = systems;
          state.hyperspace = hyperspace;
          state.systemCount = size;
          state.firstSystem = first;
          state.lastSystem = last;
          state.refreshedAtNanos = now;
        }
      }
    } catch (Throwable ignored) {
      // A failed bookkeeping update merely causes another vanilla refresh on
      // the next frame; it must not affect campaign execution.
    }
    StarsectorPrepatcherMetrics.CAMPAIGN_LISTENER_RUNS.increment();
  }
  static void invokeReaddChangeListeners(Object engine) {
    try {
      CampaignListenerAccess.ACCESS.get(engine.getClass()).readdChangeListeners.invoke(engine);
    } catch (InvocationTargetException ex) {
      StarsectorPrepatcherHooks.throwUnchecked(ex.getCause());
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException("Unable to invoke CampaignEngine.readdChangeListeners()", ex);
    }
  }
  static final class CampaignListenerAccess {
    static final ClassValue<CampaignListenerAccess> ACCESS = new ClassValue<>() {
      @Override
      protected CampaignListenerAccess computeValue(Class<?> type) {
        try {
          Method refresh = type.getMethod("readdChangeListeners");
          refresh.setAccessible(true);
          return new CampaignListenerAccess(refresh);
        } catch (ReflectiveOperationException ex) {
          throw new IllegalStateException("Unexpected CampaignEngine layout: " + type.getName(), ex);
        }
      }
    };

    final Method readdChangeListeners;

    CampaignListenerAccess(Method readdChangeListeners) {
      this.readdChangeListeners = readdChangeListeners;
    }
  }
  static final class CampaignListenerState {
    Object systems;
    Object hyperspace;
    Object firstSystem;
    Object lastSystem;
    int systemCount;
    long refreshedAtNanos;
  }
}
