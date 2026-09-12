package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.WeakHashMap;

/** Campaign route/pathfinding indexes for jump points and star systems. */
final class StarsectorPrepatcherRouteIndex {

  static volatile Map<LocationAPI, RouteJumpIndex> ROUTE_JUMP_INDEXES =
      Collections.synchronizedMap(new WeakHashMap<>());
  static volatile Map<Object, RouteSystemIndex> ROUTE_SYSTEM_INDEXES = Collections.synchronizedMap(new WeakHashMap<>());
  // ---------------------------------------------------------------------
  // Route/pathfinding indexes.
  //
  // O0Oo.getNextStep() and getLastLegDistance() preserve all of their original
  // filtering, distance and tie-breaking code. These hooks only replace the
  // candidate source: an O(all hyperspace jump points) list becomes the same
  // ordered subset whose first destination belongs to the requested system.
  // Cache misses and malformed/custom data fall back to the vanilla full list.
  // ---------------------------------------------------------------------

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static List routeJumpPointsForSystem(LocationAPI hyperspace, Object system) {
    if (hyperspace == null) return null;
    List live = hyperspace.getJumpPoints();
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (c == null || !c.routeJumpPointIndex || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)
        || c.routeIndexTtlMs <= 0 || system == null || live == null || !(live instanceof RandomAccess)
        || live.size() < 32) {
      StarsectorPrepatcherMetrics.ROUTE_JUMP_INDEX_FALLBACKS.increment();
      return live;
    }

    try {
      long now = System.nanoTime();
      RouteJumpIndex index;
      Map<LocationAPI, RouteJumpIndex> routeJumpIndexes = ROUTE_JUMP_INDEXES;
      synchronized (routeJumpIndexes) {
        if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
          index = null;
        } else {
          index = routeJumpIndexes.get(hyperspace);
          if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
            index = null;
          } else if (index == null || !index.matches(live, now, c.routeIndexTtlMs)) {
            index = RouteJumpIndex.build(live, now);
            if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
              index = null;
            } else {
              routeJumpIndexes.put(hyperspace, index);
              if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
                routeJumpIndexes.clear();
                index = null;
              } else {
                StarsectorPrepatcherMetrics.ROUTE_JUMP_INDEX_BUILDS.increment();
              }
            }
          }
          if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
            routeJumpIndexes.clear();
            index = null;
          }
        }
      }
      if (index == null) {
        StarsectorPrepatcherMetrics.ROUTE_JUMP_INDEX_FALLBACKS.increment();
        return live;
      }
      if (index.failed) {
        StarsectorPrepatcherMetrics.ROUTE_JUMP_INDEX_FALLBACKS.increment();
        return live;
      }
      List candidates = index.bySystem.get(system);
      if (candidates == null) {
        // A full-list fallback on a miss prevents stale indexes from ever
        // hiding a newly retargeted modded jump point.
        StarsectorPrepatcherMetrics.ROUTE_JUMP_INDEX_FALLBACKS.increment();
        return live;
      }
      StarsectorPrepatcherMetrics.ROUTE_JUMP_INDEX_HITS.increment();
      return candidates;
    } catch (Throwable ex) {
      StarsectorPrepatcherMetrics.ROUTE_JUMP_INDEX_FALLBACKS.increment();
      return live;
    }
  }
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static List routeSystemsForAnchor(Object engine, Object anchor) {
    if (engine == null || anchor == null) return vanillaStarSystems(engine);
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (c == null || !c.routeJumpPointIndex
        || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, engine) || c.routeIndexTtlMs <= 0) {
      return vanillaStarSystems(engine);
    }

    try {
      CampaignRouteAccess access = CampaignRouteAccess.ACCESS.get(engine.getClass());
      List systems = access.backingSystems(engine);
      if (systems instanceof RandomAccess && systems.size() >= 32) {
        long now = System.nanoTime();
        RouteSystemIndex index;
        Map<Object, RouteSystemIndex> routeSystemIndexes = ROUTE_SYSTEM_INDEXES;
        synchronized (routeSystemIndexes) {
          if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, engine)) {
            index = null;
          } else {
            index = routeSystemIndexes.get(engine);
            if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, engine)) {
              index = null;
            } else if (index == null || !index.matches(systems, now, c.routeIndexTtlMs)) {
              index = RouteSystemIndex.build(systems, now);
              if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, engine)) {
                index = null;
              } else {
                routeSystemIndexes.put(engine, index);
                if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, engine)) {
                  routeSystemIndexes.clear();
                  index = null;
                } else {
                  StarsectorPrepatcherMetrics.ROUTE_SYSTEM_INDEX_BUILDS.increment();
                }
              }
            }
          }
          if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, engine)) {
            routeSystemIndexes.clear();
            index = null;
          }
        }
        if (index != null && !index.failed) {
          List candidates = index.byAnchor.get(anchor);
          if (candidates != null) {
            StarsectorPrepatcherMetrics.ROUTE_SYSTEM_INDEX_HITS.increment();
            return candidates;
          }
        }
      }
    } catch (Throwable ignored) {
      // Fall through. The public getter below is deliberately independent
      // from backing-field discovery, so compatibility fallback stays live.
    }
    StarsectorPrepatcherMetrics.ROUTE_SYSTEM_INDEX_FALLBACKS.increment();
    return vanillaStarSystems(engine);
  }
  @SuppressWarnings("rawtypes")
  static List vanillaStarSystems(Object engine) {
    if (engine == null) return Collections.emptyList();
    try {
      return (List) CampaignRouteGetter.GETTER.get(engine.getClass()).invoke(engine);
    } catch (InvocationTargetException ex) {
      StarsectorPrepatcherHooks.throwUnchecked(ex.getCause());
      return Collections.emptyList(); // unreachable
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException("Unable to invoke CampaignEngine.getStarSystems()", ex);
    }
  }
  static final class CampaignRouteGetter {
    static final ClassValue<Method> GETTER = new ClassValue<>() {
      @Override
      protected Method computeValue(Class<?> type) {
        try {
          Method getter = type.getMethod("getStarSystems");
          getter.setAccessible(true);
          return getter;
        } catch (ReflectiveOperationException ex) {
          throw new IllegalStateException("Unexpected CampaignEngine route layout: " + type.getName(), ex);
        }
      }
    };
  }
  static final class CampaignRouteAccess {
    static final ClassValue<CampaignRouteAccess> ACCESS = new ClassValue<>() {
      @Override
      protected CampaignRouteAccess computeValue(Class<?> type) {
        Method getter = CampaignRouteGetter.GETTER.get(type);
        ArrayList<Field> candidates = new ArrayList<>();
        Class<?> current = type;
        while (current != null) {
          for (Field field : current.getDeclaredFields()) {
            if (List.class.isAssignableFrom(field.getType())) {
              field.setAccessible(true);
              candidates.add(field);
            }
          }
          current = current.getSuperclass();
        }
        return new CampaignRouteAccess(candidates, getter);
      }
    };

    final List<Field> listFields;
    final Method getStarSystems;
    volatile Field resolvedStarSystems;

    CampaignRouteAccess(List<Field> listFields, Method getStarSystems) {
      this.listFields = listFields;
      this.getStarSystems = getStarSystems;
    }

    @SuppressWarnings("rawtypes")
    List backingSystems(Object engine) throws ReflectiveOperationException {
      Field resolved = resolvedStarSystems;
      if (resolved != null) return (List) resolved.get(engine);
      synchronized (this) {
        resolved = resolvedStarSystems;
        if (resolved != null) return (List) resolved.get(engine);
        List visible = (List) getStarSystems.invoke(engine);
        Field match = null;
        for (Field candidate : listFields) {
          Object value = candidate.get(engine);
          if (!(value instanceof List list) || !sameIdentityContents(list, visible)) continue;
          if (match != null) { throw new IllegalStateException("Ambiguous CampaignEngine star-system List fields"); }
          match = candidate;
        }
        if (match == null) { throw new IllegalStateException("CampaignEngine backing star-system List was not found"); }
        resolvedStarSystems = match;
        return (List) match.get(engine);
      }
    }

    @SuppressWarnings("rawtypes")
    private static boolean sameIdentityContents(List candidate, List visible) {
      if (candidate == null || visible == null || candidate.size() != visible.size()) return false;
      for (int i = 0; i < candidate.size(); i++) {
        if (candidate.get(i) != visible.get(i)) return false;
      }
      return true;
    }
  }
  static final class RouteJumpIndex {
    final int size;
    final Object first;
    final Object last;
    long validatedAtNanos;
    final IdentityHashMap<Object, List<SectorEntityToken>> bySystem;
    final Object[] entries;
    final Object[] destinationSystems;
    final boolean failed;

    RouteJumpIndex(int size, Object first, Object last, long builtAtNanos,
        IdentityHashMap<Object, List<SectorEntityToken>> bySystem, Object[] entries, Object[] destinationSystems,
        boolean failed) {
      this.size = size;
      this.first = first;
      this.last = last;
      this.validatedAtNanos = builtAtNanos;
      this.bySystem = bySystem;
      this.entries = entries;
      this.destinationSystems = destinationSystems;
      this.failed = failed;
    }

    boolean matches(List<?> live, long now, int ttlMs) {
      int currentSize = live.size();
      Object currentFirst = currentSize > 0 ? live.get(0) : null;
      Object currentLast = currentSize > 0 ? live.get(currentSize - 1) : null;
      long ttlNs = ttlMs * 1_000_000L;
      if (size != currentSize || first != currentFirst || last != currentLast) return false;
      if (ttlNs <= 0L) return false;
      if (now - validatedAtNanos < ttlNs) return true;
      if (failed) return false;

      // Size/edge checks do not catch middle replacement or a mod retargeting
      // an existing jump point. Periodically validate the full relation while
      // keeping ordinary cache hits O(1); a mismatch rebuilds the index.
      try {
        for (int i = 0; i < currentSize; i++) {
          Object raw = live.get(i);
          if (raw != entries[i] || destinationSystem(raw) != destinationSystems[i]) return false;
        }
      } catch (Throwable ignored) {
        return false;
      }
      validatedAtNanos = now;
      return true;
    }

    @SuppressWarnings("rawtypes")
    static RouteJumpIndex build(List live, long now) {
      int size = live.size();
      Object first = size > 0 ? live.get(0) : null;
      Object last = size > 0 ? live.get(size - 1) : null;
      IdentityHashMap<Object, List<SectorEntityToken>> bySystem = new IdentityHashMap<>();
      Object[] entries = new Object[size];
      Object[] destinationSystems = new Object[size];
      try {
        for (int i = 0; i < size; i++) {
          Object raw = live.get(i);
          entries[i] = raw;
          Object location = destinationSystem(raw);
          destinationSystems[i] = location;
          if (location == null) continue;
          bySystem.computeIfAbsent(location, ignored -> new ArrayList<>(2)).add((SectorEntityToken) raw);
        }
        return new RouteJumpIndex(size, first, last, now, bySystem, entries, destinationSystems, false);
      } catch (Throwable ex) {
        return new RouteJumpIndex(size, first, last, now, bySystem, entries, destinationSystems, true);
      }
    }

    private static Object destinationSystem(Object raw) {
      if (!(raw instanceof JumpPointAPI jumpPoint)) {
        throw new IllegalStateException("Non-jump-point entry in hyperspace jump list");
      }
      List<JumpPointAPI.JumpDestination> destinations = jumpPoint.getDestinations();
      if (destinations == null || destinations.isEmpty()) return null;
      JumpPointAPI.JumpDestination firstDestination = destinations.get(0);
      if (firstDestination == null || firstDestination.getDestination() == null) {
        throw new IllegalStateException("Malformed jump-point destination");
      }
      return firstDestination.getDestination().getContainingLocation();
    }
  }
  static final class RouteSystemIndex {
    final int size;
    final Object first;
    final Object last;
    long validatedAtNanos;
    final IdentityHashMap<Object, List<Object>> byAnchor;
    final Object[] entries;
    final Object[] anchors;
    final boolean failed;

    RouteSystemIndex(int size, Object first, Object last, long builtAtNanos,
        IdentityHashMap<Object, List<Object>> byAnchor, Object[] entries, Object[] anchors, boolean failed) {
      this.size = size;
      this.first = first;
      this.last = last;
      this.validatedAtNanos = builtAtNanos;
      this.byAnchor = byAnchor;
      this.entries = entries;
      this.anchors = anchors;
      this.failed = failed;
    }

    boolean matches(List<?> live, long now, int ttlMs) {
      int currentSize = live.size();
      Object currentFirst = currentSize > 0 ? live.get(0) : null;
      Object currentLast = currentSize > 0 ? live.get(currentSize - 1) : null;
      long ttlNs = ttlMs * 1_000_000L;
      if (size != currentSize || first != currentFirst || last != currentLast) return false;
      if (ttlNs <= 0L) return false;
      if (now - validatedAtNanos < ttlNs) return true;
      if (failed) return false;

      try {
        for (int i = 0; i < currentSize; i++) {
          Object raw = live.get(i);
          if (raw != entries[i] || !(raw instanceof StarSystemAPI system) || system.getHyperspaceAnchor() != anchors[i])
            return false;
        }
      } catch (Throwable ignored) {
        return false;
      }
      validatedAtNanos = now;
      return true;
    }

    @SuppressWarnings("rawtypes")
    static RouteSystemIndex build(List systems, long now) {
      int size = systems.size();
      Object first = size > 0 ? systems.get(0) : null;
      Object last = size > 0 ? systems.get(size - 1) : null;
      IdentityHashMap<Object, List<Object>> byAnchor = new IdentityHashMap<>(Math.max(32, size * 2));
      Object[] entries = new Object[size];
      Object[] anchors = new Object[size];
      try {
        for (int i = 0; i < size; i++) {
          Object raw = systems.get(i);
          entries[i] = raw;
          if (!(raw instanceof StarSystemAPI system)) {
            throw new IllegalStateException("Non-system entry in CampaignEngine star-system list");
          }
          SectorEntityToken anchor = system.getHyperspaceAnchor();
          anchors[i] = anchor;
          if (anchor != null) {
            byAnchor.computeIfAbsent(anchor, ignored -> new ArrayList<>(1)).add(raw);
          }
        }
        return new RouteSystemIndex(size, first, last, now, byAnchor, entries, anchors, false);
      } catch (Throwable ex) {
        return new RouteSystemIndex(size, first, last, now, byAnchor, entries, anchors, true);
      }
    }
  }
}
