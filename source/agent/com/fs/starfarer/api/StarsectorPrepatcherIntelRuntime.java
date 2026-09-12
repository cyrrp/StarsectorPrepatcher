package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.campaign.CampaignEngine;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import org.lwjgl.util.vector.Vector2f;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Map label spatial index, intel icon caches, and comm-relay candidate index. */
final class StarsectorPrepatcherIntelRuntime {

  static volatile Map<IntelInfoPlugin, IntelCache> INTEL_CACHE = Collections.synchronizedMap(new WeakHashMap<>());
  static volatile IdentityHashMap<LinkedHashMap<?, ?>, LabelIndex> LABEL_INDEXES = new IdentityHashMap<>();
  static volatile IdentityHashMap<List<?>, IntelEntityIndex> INTEL_ENTITY_INDEXES = new IdentityHashMap<>();
  static volatile Map<Object, CommRelaySystemIndex> COMM_RELAY_SYSTEM_INDEXES =
      Collections.synchronizedMap(new WeakHashMap<>());
  static void maintainLabelIndexes(PrepatcherConfig c, long generation, long now) {
    IdentityHashMap<LinkedHashMap<?, ?>, LabelIndex> indexes = LABEL_INDEXES;
    synchronized (indexes) {
      if (indexes != LABEL_INDEXES || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) return;
      Iterator<Map.Entry<LinkedHashMap<?, ?>, LabelIndex>> iterator = indexes.entrySet().iterator();
      while (iterator.hasNext()) {
        LabelIndex index = iterator.next().getValue();
        if (index.activeUsers == 0
            && StarsectorPrepatcherCampaignCaches.idleExpired(now, index.lastAccessNanos, c.labelOwnerIdleTtlMs)) {
          iterator.remove();
          StarsectorPrepatcherMetrics.LABEL_OWNER_IDLE_EVICTIONS.increment();
        }
      }
    }
  }
  static void maintainIntelEntityIndexes(PrepatcherConfig c, long generation, long now) {
    IdentityHashMap<List<?>, IntelEntityIndex> indexes = INTEL_ENTITY_INDEXES;
    synchronized (indexes) {
      if (indexes != INTEL_ENTITY_INDEXES || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation))
        return;
      Iterator<Map.Entry<List<?>, IntelEntityIndex>> iterator = indexes.entrySet().iterator();
      while (iterator.hasNext()) {
        IntelEntityIndex index = iterator.next().getValue();
        if (index.activeUsers == 0 && StarsectorPrepatcherCampaignCaches.idleExpired(now, index.lastAccessNanos,
            c.intelEntityIndexOwnerIdleTtlMs)) {
          iterator.remove();
          StarsectorPrepatcherMetrics.INTEL_ENTITY_OWNER_IDLE_EVICTIONS.increment();
        }
      }
    }
  }
  /**
   * Returns only spatially plausible systems, in the exact original
   * getStarSystems() order. The original IntelManager loop still performs all
   * distance, relay tag, memory and Random selection logic. Index misses and
   * malformed/modded structures return the full vanilla list.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static List commRelayCandidateSystems(CampaignEngine engine, Vector2f playerLocation, float unitsPerLy,
      float maxRangeLy) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (engine == null) return Collections.emptyList();
    if (c == null || !c.commRelaySystemIndex || c.commRelayIndexTtlMs <= 0 || playerLocation == null
        || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, engine)) {
      StarsectorPrepatcherMetrics.COMM_RELAY_INDEX_FALLBACKS.increment();
      return engine.getStarSystems();
    }
    float radius = unitsPerLy * maxRangeLy;
    if (!(radius > 0f) || !Float.isFinite(radius) || !Float.isFinite(playerLocation.x)
        || !Float.isFinite(playerLocation.y)) {
      StarsectorPrepatcherMetrics.COMM_RELAY_INDEX_FALLBACKS.increment();
      return engine.getStarSystems();
    }

    try {
      // Reuse the same structurally-discovered backing list as route
      // indexing. Candidate results are still copied into the current
      // scratch scope, so no live engine collection escapes to Intel code.
      List systems =
          StarsectorPrepatcherRouteIndex.CampaignRouteAccess.ACCESS.get(engine.getClass()).backingSystems(engine);
      StarsectorPrepatcherMetrics.COMM_RELAY_SYSTEMS_TOTAL.add(systems.size());
      if (systems.size() < 96) {
        StarsectorPrepatcherMetrics.COMM_RELAY_INDEX_FALLBACKS.increment();
        return engine.getStarSystems();
      }

      long now = System.nanoTime();
      CommRelaySystemIndex index;
      Map<Object, CommRelaySystemIndex> indexes = COMM_RELAY_SYSTEM_INDEXES;
      synchronized (indexes) {
        if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, engine)) {
          StarsectorPrepatcherMetrics.COMM_RELAY_INDEX_FALLBACKS.increment();
          return engine.getStarSystems();
        }
        index = indexes.get(engine);
        if (index == null || !index.matches(systems, now, c.commRelayIndexTtlMs, radius)) {
          index = CommRelaySystemIndex.build(systems, now, radius);
          if (StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, engine)) {
            indexes.put(engine, index);
            if (!index.failed) StarsectorPrepatcherMetrics.COMM_RELAY_INDEX_BUILDS.increment();
          }
        }
      }
      if (index.failed) {
        StarsectorPrepatcherMetrics.COMM_RELAY_INDEX_FALLBACKS.increment();
        return engine.getStarSystems();
      }

      StarsectorPrepatcherScratch.Scratch scratch = StarsectorPrepatcherScratch.SCRATCH.get();
      if (scratch.scopeDepth <= 0) {
        StarsectorPrepatcherMetrics.COMM_RELAY_INDEX_FALLBACKS.increment();
        return engine.getStarSystems();
      }
      StarsectorPrepatcherScratch.ScratchFrame frame = scratch.scopeFrame();
      ArrayList entryScratch = frame.pooledList();
      ArrayList result = frame.pooledList();
      index.collect(playerLocation.x, playerLocation.y, radius, entryScratch);
      entryScratch.sort(COMM_RELAY_ENTRY_ORDER);
      for (Object raw : entryScratch) {
        result.add(((CommRelaySystemEntry) raw).system);
      }
      if (result.size() >= systems.size() * 3 / 4) {
        StarsectorPrepatcherMetrics.COMM_RELAY_INDEX_FALLBACKS.increment();
        return engine.getStarSystems();
      }
      StarsectorPrepatcherMetrics.COMM_RELAY_INDEX_HITS.increment();
      StarsectorPrepatcherMetrics.COMM_RELAY_SYSTEMS_CANDIDATE.add(result.size());
      return result;
    } catch (Throwable ignored) {
      StarsectorPrepatcherMetrics.COMM_RELAY_INDEX_FALLBACKS.increment();
      return engine.getStarSystems();
    }
  }
  static final java.util.Comparator<Object> COMM_RELAY_ENTRY_ORDER =
      (left, right) -> Integer.compare(((CommRelaySystemEntry) left).ordinal, ((CommRelaySystemEntry) right).ordinal);
  // Label candidate spatial index. Original method still performs exact
  // visibility and distance checks; this only narrows the candidate set.

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static Collection nearbyLabelIcons(LinkedHashMap icons, Object mapOwner, Object targetIcon) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (c == null || !c.labelSpatialCandidates || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)
        || icons.size() < 64) {
      return icons.values();
    }
    LabelIndex leasedIndex = null;
    try {
      LabelIndex index;
      long now = StarsectorPrepatcherCampaignCaches.nanoTime();
      IdentityHashMap<LinkedHashMap<?, ?>, LabelIndex> labelIndexes = LABEL_INDEXES;
      synchronized (labelIndexes) {
        if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) return icons.values();
        index = labelIndexes.get(icons);
        long ttlNs = c.labelIndexTtlMs * 1_000_000L;
        if (index == null || index.size != icons.size() || (ttlNs > 0L && now - index.builtAtNanos > ttlNs)) {
          index = LabelIndex.build(icons, now);
          if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) return icons.values();
          if (StarsectorPrepatcherCampaignCaches.evictIdleAndLruForInsert(labelIndexes, icons, now,
              c.labelOwnerIdleTtlMs, 32, StarsectorPrepatcherMetrics.LABEL_OWNER_IDLE_EVICTIONS,
              StarsectorPrepatcherMetrics.LABEL_OWNER_LIMIT_EVICTIONS)) {
            labelIndexes.put(icons, index);
          }
        }
        index.lastAccessNanos = now;
        index.activeUsers++;
        leasedIndex = index;
        if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
          labelIndexes.clear();
          return icons.values();
        }
      }
      SectorEntityToken token = iconToken(targetIcon);
      if (token == null || token.getLocation() == null || index.failed) return icons.values();
      Vector2f location = token.getLocation();
      int cx = StarsectorPrepatcherHooks.floorCell(location.x, LabelIndex.CELL_X);
      int cy = StarsectorPrepatcherHooks.floorCell(location.y, LabelIndex.CELL_Y);
      ArrayList<Object> result = StarsectorPrepatcherScratch.SCRATCH.get().scopeFrame().labelCandidates;
      result.clear();
      result.addAll(index.fallback);
      for (int dx = -1; dx <= 1; dx++) {
        for (int dy = -1; dy <= 1; dy++) {
          List<Object> bucket = index.buckets.get(StarsectorPrepatcherHooks.cellKey(cx + dx, cy + dy));
          if (bucket != null) result.addAll(bucket);
        }
      }
      StarsectorPrepatcherScratch.SCRATCH.get().scopeFrame().observeRetainedList(result);
      StarsectorPrepatcherMetrics.LABEL_TOTAL.add(icons.size());
      StarsectorPrepatcherMetrics.LABEL_CANDIDATES.add(result.size());
      return result;
    } catch (Throwable ex) {
      return icons.values();
    } finally {
      if (leasedIndex != null) leasedIndex.activeUsers--;
    }
  }
  static SectorEntityToken iconToken(Object icon) throws Exception {
    Method method = IconAccess.TOKEN_METHODS.get(icon.getClass());
    return (SectorEntityToken) method.invoke(icon);
  }
  static final class IconAccess {
    private static final ClassValue<Method> TOKEN_METHODS = new ClassValue<>() {
      @Override
      protected Method computeValue(Class<?> type) {
        Class<?> current = type;
        while (current != null) {
          try {
            Method method = current.getDeclaredMethod("int");
            method.setAccessible(true);
            return method;
          } catch (NoSuchMethodException ignored) {
            current = current.getSuperclass();
          }
        }
        throw new IllegalStateException("Unable to find map-icon token accessor for " + type.getName());
      }
    };
  }
  static final class LabelIndex {
    static final float CELL_X = 10_000f;
    static final float CELL_Y = 3_000f;
    final int size;
    final long builtAtNanos;
    volatile long lastAccessNanos;
    volatile int activeUsers;
    final Map<Long, List<Object>> buckets;
    final List<Object> fallback;
    final boolean failed;

    private LabelIndex(int size, long builtAtNanos, Map<Long, List<Object>> buckets, List<Object> fallback,
        boolean failed) {
      this.size = size;
      this.builtAtNanos = builtAtNanos;
      this.lastAccessNanos = builtAtNanos;
      this.buckets = buckets;
      this.fallback = fallback;
      this.failed = failed;
    }

    static LabelIndex build(LinkedHashMap<?, ?> icons, long now) {
      Map<Long, List<Object>> buckets = new HashMap<>();
      List<Object> fallback = new ArrayList<>();
      try {
        for (Object icon : icons.values()) {
          try {
            SectorEntityToken token = iconToken(icon);
            Vector2f location = token == null ? null : token.getLocation();
            if (location == null || !Float.isFinite(location.x) || !Float.isFinite(location.y)) {
              fallback.add(icon);
              continue;
            }
            int cx = StarsectorPrepatcherHooks.floorCell(location.x, CELL_X);
            int cy = StarsectorPrepatcherHooks.floorCell(location.y, CELL_Y);
            buckets.computeIfAbsent(StarsectorPrepatcherHooks.cellKey(cx, cy), ignored -> new ArrayList<>()).add(icon);
          } catch (Throwable ex) {
            fallback.add(icon);
          }
        }
        return new LabelIndex(icons.size(), now, buckets, fallback, false);
      } catch (ConcurrentModificationException ex) {
        return new LabelIndex(icons.size(), now, Collections.emptyMap(), new ArrayList<>(), true);
      }
    }
  }
  // Intel callbacks

  public static SectorEntityToken getMapLocationCached(IntelInfoPlugin plugin, SectorMapAPI map) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (c == null || !c.intelCallbackCache || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)
        || c.intelMapLocationCacheMs <= 0) {
      return plugin.getMapLocation(map);
    }
    long now = System.nanoTime();
    long ttl = c.intelMapLocationCacheMs * 1_000_000L;
    IntelCache entry;
    Map<IntelInfoPlugin, IntelCache> intelCache = INTEL_CACHE;
    synchronized (intelCache) {
      if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
        entry = null;
      } else {
        entry = intelCache.computeIfAbsent(plugin, ignored -> new IntelCache());
        // WeakHashMap key operations may invoke user-defined hashCode.
        // A re-entrant lifecycle change must not leave that late insert.
        if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
          intelCache.clear();
          entry = null;
        }
      }
      if (entry != null && entry.mapForLocation == map && entry.locationValid && now - entry.locationAtNanos <= ttl) {
        StarsectorPrepatcherMetrics.MAP_LOCATION_HITS.increment();
        return entry.location;
      }
    }
    if (entry == null) return plugin.getMapLocation(map);
    SectorEntityToken value = plugin.getMapLocation(map);
    synchronized (intelCache) {
      entry.mapForLocation = map;
      entry.location = value;
      entry.locationAtNanos = now;
      entry.locationValid = true;
    }
    StarsectorPrepatcherMetrics.MAP_LOCATION_MISSES.increment();
    return value;
  }
  @SuppressWarnings("rawtypes")
  public static List getArrowDataCached(IntelInfoPlugin plugin, SectorMapAPI map) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (c == null || !c.intelArrowRendering || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)
        || c.intelArrowDataCacheMs <= 0) {
      return plugin.getArrowData(map);
    }
    long now = System.nanoTime();
    long ttl = c.intelArrowDataCacheMs * 1_000_000L;
    IntelCache entry;
    Map<IntelInfoPlugin, IntelCache> intelCache = INTEL_CACHE;
    synchronized (intelCache) {
      if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
        entry = null;
      } else {
        entry = intelCache.computeIfAbsent(plugin, ignored -> new IntelCache());
        if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
          intelCache.clear();
          entry = null;
        }
      }
      if (entry != null && entry.mapForArrows == map && entry.arrowsValid && now - entry.arrowsAtNanos <= ttl) {
        StarsectorPrepatcherMetrics.ARROW_HITS.increment();
        return entry.arrows;
      }
    }
    if (entry == null) return plugin.getArrowData(map);
    List value = plugin.getArrowData(map);
    synchronized (intelCache) {
      entry.mapForArrows = map;
      entry.arrows = value;
      entry.arrowsAtNanos = now;
      entry.arrowsValid = true;
    }
    StarsectorPrepatcherMetrics.ARROW_MISSES.increment();
    return value;
  }
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static boolean fastContains(Collection collection, Object value) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.intelReconciliation || collection.size() < 16) { return collection.contains(value); }
    StarsectorPrepatcherScratch.ScratchFrame scratch = StarsectorPrepatcherScratch.SCRATCH.get().scopeFrame();
    if (scratch.containsSource != collection || scratch.containsSourceSize != collection.size()) {
      scratch.containsSet.clear();
      scratch.containsSet.addAll(collection);
      scratch.observeRetainedSet(scratch.containsSet);
      scratch.containsSource = collection;
      scratch.containsSourceSize = collection.size();
    }
    return scratch.containsSet.contains(value);
  }
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static Collection existingIntelIconCandidates(LinkedHashMap icons, Object entity) {
    Object direct = icons.get(entity);
    if (direct != null) return Collections.singletonList(direct);
    return icons.values();
  }
  /** Identity index for H.getIntelIconEntity(), avoiding an O(M) scan per Intel item. */
  @SuppressWarnings("rawtypes")
  public static Object getIntelIconEntityIndexed(Object owner, List intelEntities, IntelInfoPlugin plugin) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (c == null || !c.intelEntityIndex || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)
        || intelEntities == null || plugin == null) {
      return invokeOriginalIntelIconLookup(owner, plugin);
    }
    IntelEntityIndex leasedIndex = null;
    try {
      long now = StarsectorPrepatcherCampaignCaches.nanoTime();
      Object first = intelEntities.isEmpty() ? null : intelEntities.get(0);
      Object last = intelEntities.isEmpty() ? null : intelEntities.get(intelEntities.size() - 1);
      IntelEntityIndex index;
      IdentityHashMap<List<?>, IntelEntityIndex> intelEntityIndexes = INTEL_ENTITY_INDEXES;
      synchronized (intelEntityIndexes) {
        if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
          index = null;
        } else {
          index = intelEntityIndexes.get(intelEntities);
          long ttlNs = c.intelEntityIndexTtlMs * 1_000_000L;
          if (index == null || index.size != intelEntities.size() || index.first != first || index.last != last
              || (ttlNs > 0L && now - index.builtAtNanos > ttlNs)) {
            index = IntelEntityIndex.build(intelEntities, first, last, now);
            if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
              index = null;
            } else {
              if (StarsectorPrepatcherCampaignCaches.evictIdleAndLruForInsert(intelEntityIndexes, intelEntities, now,
                  c.intelEntityIndexOwnerIdleTtlMs, 32, StarsectorPrepatcherMetrics.INTEL_ENTITY_OWNER_IDLE_EVICTIONS,
                  StarsectorPrepatcherMetrics.INTEL_ENTITY_OWNER_LIMIT_EVICTIONS)) {
                intelEntityIndexes.put(intelEntities, index);
              }
              StarsectorPrepatcherMetrics.INTEL_INDEX_BUILDS.increment();
            }
          }
          if (index != null) {
            index.lastAccessNanos = now;
            if (!index.failed) {
              index.activeUsers++;
              leasedIndex = index;
            }
          }
          if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
            intelEntityIndexes.clear();
            index = null;
          }
        }
      }
      if (index != null && !index.failed) {
        StarsectorPrepatcherMetrics.INTEL_INDEX_HITS.increment();
        return index.byPlugin.get(plugin);
      }
    } catch (Throwable ignored) {
      // Fall through to the original method. Keep that invocation outside
      // this catch so an exception from game/mod code is never retried.
    } finally {
      if (leasedIndex != null) leasedIndex.activeUsers--;
    }
    return invokeOriginalIntelIconLookup(owner, plugin);
  }
  static Object invokeOriginalIntelIconLookup(Object owner, IntelInfoPlugin plugin) {
    if (owner == null) return null;
    try {
      return IntelEntityAccess.ORIGINAL.get(owner.getClass()).invoke(owner, plugin);
    } catch (InvocationTargetException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof RuntimeException) throw (RuntimeException) cause;
      if (cause instanceof Error) throw (Error) cause;
      throw new RuntimeException(cause);
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException("Unable to invoke original Starsector Intel entity lookup", ex);
    }
  }
  static final class IntelEntityAccess {
    static final ClassValue<Method> ORIGINAL = new ClassValue<>() {
      @Override
      protected Method computeValue(Class<?> type) {
        try {
          Method method = StarsectorPrepatcherHooks.findDeclaredMethod(type, "smo$originalGetIntelIconEntity",
              IntelInfoPlugin.class);
          method.setAccessible(true);
          return method;
        } catch (ReflectiveOperationException ex) {
          throw new IllegalStateException(ex);
        }
      }
    };

    static final ClassValue<Field> PLUGIN_FIELD = new ClassValue<>() {
      @Override
      protected Field computeValue(Class<?> type) {
        Field result = null;
        Class<?> current = type;
        while (current != null) {
          for (Field field : current.getDeclaredFields()) {
            if (IntelInfoPlugin.class.isAssignableFrom(field.getType())) {
              if (result != null) {
                throw new IllegalStateException("Ambiguous IntelInfoPlugin fields in " + type.getName());
              }
              result = field;
            }
          }
          current = current.getSuperclass();
        }
        if (result == null) { throw new IllegalStateException("No IntelInfoPlugin field in " + type.getName()); }
        result.setAccessible(true);
        return result;
      }
    };
  }
  static final class IntelEntityIndex {
    final int size;
    final Object first;
    final Object last;
    final long builtAtNanos;
    volatile long lastAccessNanos;
    volatile int activeUsers;
    final IdentityHashMap<IntelInfoPlugin, Object> byPlugin;
    final boolean failed;

    IntelEntityIndex(int size, Object first, Object last, long builtAtNanos,
        IdentityHashMap<IntelInfoPlugin, Object> byPlugin, boolean failed) {
      this.size = size;
      this.first = first;
      this.last = last;
      this.builtAtNanos = builtAtNanos;
      this.lastAccessNanos = builtAtNanos;
      this.byPlugin = byPlugin;
      this.failed = failed;
    }

    @SuppressWarnings("rawtypes")
    static IntelEntityIndex build(List entities, Object first, Object last, long now) {
      IdentityHashMap<IntelInfoPlugin, Object> byPlugin = new IdentityHashMap<>();
      try {
        for (Object entity : entities) {
          if (!(entity instanceof SectorEntityToken token)) continue;
          Object data = token.getCustomData().get("intelIconData");
          if (data == null) continue;
          Field pluginField = IntelEntityAccess.PLUGIN_FIELD.get(data.getClass());
          Object value = pluginField.get(data);
          if (value instanceof IntelInfoPlugin plugin) byPlugin.put(plugin, entity);
        }
        return new IntelEntityIndex(entities.size(), first, last, now, byPlugin, false);
      } catch (ConcurrentModificationException ex) {
        return new IntelEntityIndex(entities.size(), first, last, now, new IdentityHashMap<>(), true);
      } catch (Throwable ex) {
        return new IntelEntityIndex(entities.size(), first, last, now, new IdentityHashMap<>(), true);
      }
    }
  }
  static final class IntelCache {
    SectorMapAPI mapForLocation;
    SectorEntityToken location;
    long locationAtNanos;
    boolean locationValid;
    SectorMapAPI mapForArrows;
    List<?> arrows;
    long arrowsAtNanos;
    boolean arrowsValid;
  }
  static final class CommRelaySystemEntry {
    final int ordinal;
    final StarSystemAPI system;

    CommRelaySystemEntry(int ordinal, StarSystemAPI system) {
      this.ordinal = ordinal;
      this.system = system;
    }
  }
  /** Spatial index whose output is sorted back into CampaignEngine.getStarSystems()
   * order before the original IntelManager loop observes it. */
  static final class CommRelaySystemIndex {
    final int size;
    final Object first;
    final Object last;
    final int radiusBits;
    final float cellSize;
    long validatedAtNanos;
    final Object[] entries;
    final int[] xBits;
    final int[] yBits;
    final HashMap<Long, ArrayList<CommRelaySystemEntry>> cells;
    final boolean failed;

    CommRelaySystemIndex(int size, Object first, Object last, int radiusBits, float cellSize, long validatedAtNanos,
        Object[] entries, int[] xBits, int[] yBits, HashMap<Long, ArrayList<CommRelaySystemEntry>> cells,
        boolean failed) {
      this.size = size;
      this.first = first;
      this.last = last;
      this.radiusBits = radiusBits;
      this.cellSize = cellSize;
      this.validatedAtNanos = validatedAtNanos;
      this.entries = entries;
      this.xBits = xBits;
      this.yBits = yBits;
      this.cells = cells;
      this.failed = failed;
    }

    boolean matches(List<?> live, long now, int ttlMs, float radius) {
      int currentSize = live.size();
      if (size != currentSize || radiusBits != Float.floatToIntBits(radius)) return false;
      long ttlNs = ttlMs * 1_000_000L;
      if (ttlNs <= 0L) return false;
      // A failed build is a safe vanilla fallback. Remember it only for a
      // bounded interval so a transient mod/list failure does not allocate
      // and rebuild on every IntelManager call.
      if (failed) {
        long elapsed = now - validatedAtNanos;
        return elapsed >= 0L && elapsed < ttlNs;
      }
      Object currentFirst = currentSize == 0 ? null : live.get(0);
      Object currentLast = currentSize == 0 ? null : live.get(currentSize - 1);
      if (first != currentFirst || last != currentLast) return false;
      long elapsed = now - validatedAtNanos;
      if (elapsed >= 0L && elapsed < ttlNs) return true;

      // System hyperspace coordinates are mutable, but rebuilding/validating
      // the full 2k+ system relation on every IntelManager frame defeats the
      // spatial index itself. Perform the exact identity/coordinate audit at
      // the configured interval; ordinary frames remain O(1) before the
      // spatial candidate query. A rare mod teleport can therefore remain
      // stale for at most commRelay.indexTtlMs, while all live relay/tag/
      // memory checks are still executed by the original method every frame.
      try {
        StarsectorPrepatcherMetrics.COMM_RELAY_VALIDATION_SCANS.increment();
        StarsectorPrepatcherMetrics.COMM_RELAY_SYSTEMS_VALIDATED.add(currentSize);
        for (int i = 0; i < currentSize; i++) {
          Object raw = live.get(i);
          if (raw != entries[i] || !(raw instanceof StarSystemAPI system)) return false;
          Vector2f location = system.getLocation();
          if (location == null || Float.floatToIntBits(location.x) != xBits[i]
              || Float.floatToIntBits(location.y) != yBits[i])
            return false;
        }
      } catch (Throwable ignored) {
        return false;
      }
      validatedAtNanos = now;
      return true;
    }

    @SuppressWarnings("rawtypes")
    static CommRelaySystemIndex build(List systems, long now, float radius) {
      int size = systems.size();
      Object first = size == 0 ? null : systems.get(0);
      Object last = size == 0 ? null : systems.get(size - 1);
      Object[] entries = new Object[size];
      int[] xBits = new int[size];
      int[] yBits = new int[size];
      HashMap<Long, ArrayList<CommRelaySystemEntry>> cells = new HashMap<>(Math.max(32, size * 2));
      float cellSize = Math.max(radius, 1f);
      try {
        for (int i = 0; i < size; i++) {
          Object raw = systems.get(i);
          entries[i] = raw;
          if (!(raw instanceof StarSystemAPI system)) {
            throw new IllegalStateException("Non-system entry in CampaignEngine star-system list");
          }
          Vector2f location = system.getLocation();
          if (location == null || !Float.isFinite(location.x) || !Float.isFinite(location.y)) {
            throw new IllegalStateException("Invalid star-system hyperspace location");
          }
          xBits[i] = Float.floatToIntBits(location.x);
          yBits[i] = Float.floatToIntBits(location.y);
          int cellX = StarsectorPrepatcherHooks.safeFloorToInt(location.x / cellSize);
          int cellY = StarsectorPrepatcherHooks.safeFloorToInt(location.y / cellSize);
          cells.computeIfAbsent(StarsectorPrepatcherHooks.cellKey(cellX, cellY), ignored -> new ArrayList<>(4))
              .add(new CommRelaySystemEntry(i, system));
        }
        return new CommRelaySystemIndex(size, first, last, Float.floatToIntBits(radius), cellSize, now, entries, xBits,
            yBits, cells, false);
      } catch (Throwable ignored) {
        // Failed entries intentionally contain no campaign references;
        // the weak engine key must not be made strong through the value.
        return new CommRelaySystemIndex(size, null, null, Float.floatToIntBits(radius), cellSize, now, new Object[0],
            new int[0], new int[0], new HashMap<>(0), true);
      }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    void collect(float x, float y, float radius, List output) {
      int minX = StarsectorPrepatcherHooks.safeFloorToInt((x - radius) / cellSize);
      int maxX = StarsectorPrepatcherHooks.safeFloorToInt((x + radius) / cellSize);
      int minY = StarsectorPrepatcherHooks.safeFloorToInt((y - radius) / cellSize);
      int maxY = StarsectorPrepatcherHooks.safeFloorToInt((y + radius) / cellSize);
      for (int cellX = minX; cellX <= maxX; cellX++) {
        for (int cellY = minY; cellY <= maxY; cellY++) {
          List<CommRelaySystemEntry> bucket = cells.get(StarsectorPrepatcherHooks.cellKey(cellX, cellY));
          if (bucket != null) output.addAll(bucket);
          if (cellY == Integer.MAX_VALUE) break;
        }
        if (cellX == Integer.MAX_VALUE) break;
      }
    }
  }
}
