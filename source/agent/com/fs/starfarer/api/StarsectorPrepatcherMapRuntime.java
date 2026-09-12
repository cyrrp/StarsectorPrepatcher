package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.procgen.Constellation;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.terrain.BaseTiledTerrain;
import com.fs.starfarer.api.util.Misc;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import com.starsector.prepatcher.agent.PrepatcherLog;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

/** Map hit-test caches, nebula preprocessing caches, and sample-cache throttling. */
final class StarsectorPrepatcherMapRuntime {

  static HitGaugeSnapshot gaugeSnapshot() {
    int hitCurrent = 0;
    long hitCells = 0L;
    int hitMax = 0;
    IdentityHashMap<Object, HitCache> hits = HIT_CACHES;
    synchronized (hits) {
      if (hits == HIT_CACHES) {
        hitCurrent = hits.size();
        for (HitCache cache : hits.values()) {
          synchronized (cache) {
            int cells = cache.cells.size();
            hitCells += cells;
            if (cells > hitMax) hitMax = cells;
          }
        }
      }
    }
    return new HitGaugeSnapshot(hitCurrent, hitCells, hitMax);
  }
  record HitGaugeSnapshot(int owners, long cells, int maxCells) {
  }


  /** Detach campaign roots without taking cache/state monitors or clearing in-flight objects. */
  static void campaignLifecycleReset() {
    HIT_CACHES = new IdentityHashMap<>();
    SAMPLE_CLEAR_TIMES = new WeakHashMap<>();
    nebulaCacheSlot = new NebulaCacheSlot();
  }


  private static volatile IdentityHashMap<Object, HitCache> HIT_CACHES = new IdentityHashMap<>();
  private static volatile WeakHashMap<Object, Long> SAMPLE_CLEAR_TIMES = new WeakHashMap<>();
  private static volatile NebulaCacheSlot nebulaCacheSlot = new NebulaCacheSlot();
  static volatile float cachedGridSpacing = -1f;
  static volatile float cachedGridWidth = Float.NaN;
  static volatile float cachedGridHeight = Float.NaN;
  static void maintainHitCaches(PrepatcherConfig c, long generation, long now) {
    IdentityHashMap<Object, HitCache> caches = HIT_CACHES;
    synchronized (caches) {
      if (caches != HIT_CACHES || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) return;
      Iterator<Map.Entry<Object, HitCache>> iterator = caches.entrySet().iterator();
      while (iterator.hasNext()) {
        HitCache cache = iterator.next().getValue();
        if (cache.activeUsers == 0
            && StarsectorPrepatcherCampaignCaches.idleExpired(now, cache.lastAccessNanos, c.hoverOwnerIdleTtlMs)) {
          iterator.remove();
          StarsectorPrepatcherMetrics.HIT_OWNER_IDLE_EVICTIONS.increment();
          continue;
        }
        synchronized (cache) {
          if (c.hoverCellPruneIntervalMs > 0 && now - cache.lastCellPruneNanos >= StarsectorPrepatcherCampaignCaches
              .millisToNanos(c.hoverCellPruneIntervalMs)) {
            cache.lastCellPruneNanos = now;
            long cellTtl = StarsectorPrepatcherCampaignCaches.millisToNanos(c.hoverCellTtlMs);
            if (cellTtl > 0L && !cache.cells.isEmpty()) {
              Iterator<CellHit> cells = cache.cells.values().iterator();
              while (cells.hasNext()) {
                if (!StarsectorPrepatcherCampaignCaches.hoverCellFresh(now, cells.next().atNanos, c.hoverCellTtlMs)) {
                  cells.remove();
                  StarsectorPrepatcherMetrics.HIT_EXPIRED_CELL_EVICTIONS.increment();
                }
              }
            }
          }
        }
      }
    }
  }
  // Hover / hit-test cache. The exact vanilla hit-test remains the source
  // of truth on misses; results are reused for a short time/cell.

  public static SectorEntityToken hitTestCached(Object handler, float x, float y, float radius) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (c == null || !c.mapHitTest || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
      return invokeOriginalHitTest(handler, x, y, radius);
    }

    HitCache cache = null;
    HitCache leasedCache = null;
    long now = 0L;
    long cellKey = 0L;
    boolean cacheCell = false;
    try {
      try {
        HitAccess access = HitAccess.ACCESS.get(handler.getClass());
        Object map = access.mapField.get(handler);
        float factor = ((Number) access.getFactor.invoke(map)).floatValue();
        Object icons = access.getIcons.invoke(map);
        int iconCount = icons instanceof Map ? ((Map<?, ?>) icons).size() : -1;
        Object location = access.getLocation.invoke(map);
        now = StarsectorPrepatcherCampaignCaches.nanoTime();
        IdentityHashMap<Object, HitCache> hitCaches = HIT_CACHES;
        synchronized (hitCaches) {
          if (StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
            cache = hitCaches.get(handler);
            if (cache == null) {
              boolean retain = StarsectorPrepatcherCampaignCaches.evictIdleAndLruForInsert(hitCaches, handler, now,
                  c.hoverOwnerIdleTtlMs, 64, StarsectorPrepatcherMetrics.HIT_OWNER_IDLE_EVICTIONS,
                  StarsectorPrepatcherMetrics.HIT_OWNER_LIMIT_EVICTIONS);
              cache = new HitCache(c.hoverMaxCells, now);
              if (retain) hitCaches.put(handler, cache);
            }
            cache.lastAccessNanos = now;
          }
          if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
            hitCaches.clear();
            cache = null;
          } else if (cache != null) {
            cache.activeUsers++;
            leasedCache = cache;
          }
        }
        if (cache != null) {
          synchronized (cache) {
            if (cache.location != location || cache.iconCount != iconCount
                || Float.floatToIntBits(cache.factor) != Float.floatToIntBits(factor)) {
              cache.reset(location, iconCount, factor);
            }
            long minInterval = c.hoverMaxHz <= 0 ? 0L : 1_000_000_000L / c.hoverMaxHz;
            if (cache.hasLast && minInterval > 0L && now - cache.lastAtNanos < minInterval) {
              StarsectorPrepatcherMetrics.HOVER_HITS.increment();
              return cache.lastResult;
            }
            if (c.hoverCellPixels > 0f && c.hoverCellTtlMs > 0 && c.hoverMaxCells > 0 && Float.isFinite(factor)
                && factor > 0f) {
              int cellX = StarsectorPrepatcherHooks.safeFloorToInt((x * factor) / c.hoverCellPixels);
              int cellY = StarsectorPrepatcherHooks.safeFloorToInt((y * factor) / c.hoverCellPixels);
              cellKey = StarsectorPrepatcherHooks.cellKey(cellX, cellY)
                  ^ ((long) Float.floatToIntBits(radius) * 0x9E3779B97F4A7C15L);
              CellHit hit = cache.cells.get(cellKey);
              if (hit != null
                  && StarsectorPrepatcherCampaignCaches.hoverCellFresh(now, hit.atNanos, c.hoverCellTtlMs)) {
                cache.setLast(hit.value, now);
                StarsectorPrepatcherMetrics.HOVER_HITS.increment();
                return hit.value;
              }
              cacheCell = true;
            }
          }
        }
      } catch (Throwable ignored) {
        // Cache setup is optional. Invoke the original once, below, outside
        // the catch boundary so its exception/side effects stay exact.
        cache = null;
        cacheCell = false;
      }

      SectorEntityToken value = invokeOriginalHitTest(handler, x, y, radius);
      if (cache != null) {
        try {
          synchronized (cache) {
            if (cacheCell) cache.cells.put(cellKey, new CellHit(value, now));
            cache.setLast(value, now);
          }
          StarsectorPrepatcherMetrics.HOVER_MISSES.increment();
        } catch (Throwable ignored) {
          // Returning the already-computed original result is always safe.
        }
      }
      return value;
    } finally {
      if (leasedCache != null) leasedCache.activeUsers--;
    }
  }
  static SectorEntityToken invokeOriginalHitTest(Object handler, float x, float y, float radius) {
    try {
      Method original = HitOriginalAccess.ORIGINAL.get(handler.getClass());
      return (SectorEntityToken) original.invoke(handler, x, y, radius);
    } catch (InvocationTargetException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof RuntimeException) throw (RuntimeException) cause;
      if (cause instanceof Error) throw (Error) cause;
      throw new RuntimeException(cause);
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException("Unable to invoke original Starsector map hit-test", ex);
    }
  }
  static final class HitOriginalAccess {
    static final ClassValue<Method> ORIGINAL = new ClassValue<>() {
      @Override
      protected Method computeValue(Class<?> type) {
        try {
          Method original = StarsectorPrepatcherHooks.findDeclaredMethod(type, "smo$originalHitTest", float.class,
              float.class, float.class);
          original.setAccessible(true);
          return original;
        } catch (ReflectiveOperationException ex) {
          throw new IllegalStateException(ex);
        }
      }
    };
  }
  static final class HitAccess {
    static final ClassValue<HitAccess> ACCESS = new ClassValue<>() {
      @Override
      protected HitAccess computeValue(Class<?> type) {
        try {
          Field mapField =
              StarsectorPrepatcherHooks.findUniqueFieldByTypeName(type, "com.fs.starfarer.coreui.A.H", "H map field");
          mapField.setAccessible(true);
          Class<?> mapType = mapField.getType();
          Method getFactor = mapType.getMethod("getFactor");
          Method getIcons = mapType.getMethod("getIcons");
          Method getLocation = mapType.getMethod("getLocation");
          return new HitAccess(mapField, getFactor, getIcons, getLocation);
        } catch (ReflectiveOperationException ex) {
          throw new IllegalStateException(ex);
        }
      }
    };

    final Field mapField;
    final Method getFactor;
    final Method getIcons;
    final Method getLocation;

    HitAccess(Field mapField, Method getFactor, Method getIcons, Method getLocation) {
      this.mapField = mapField;
      this.getFactor = getFactor;
      this.getIcons = getIcons;
      this.getLocation = getLocation;
    }
  }
  static final class HitCache implements StarsectorPrepatcherCampaignCaches.CacheOwner {
    public long lastAccessNanos() {
      return lastAccessNanos;
    }
    public int activeUsers() {
      return activeUsers;
    }
    Object location;
    int iconCount = Integer.MIN_VALUE;
    float factor = Float.NaN;
    boolean hasLast;
    SectorEntityToken lastResult;
    long lastAtNanos;
    volatile long lastAccessNanos;
    volatile int activeUsers;
    long lastCellPruneNanos;
    final LinkedHashMap<Long, CellHit> cells;

    HitCache(int maxEntries, long now) {
      lastAccessNanos = now;
      lastCellPruneNanos = now;
      final int limit = Math.max(1, maxEntries);
      cells = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, CellHit> eldest) {
          return size() > limit;
        }
      };
    }

    void reset(Object newLocation, int newIconCount, float newFactor) {
      location = newLocation;
      iconCount = newIconCount;
      factor = newFactor;
      hasLast = false;
      lastResult = null;
      lastAtNanos = 0L;
      cells.clear();
    }

    void setLast(SectorEntityToken result, long now) {
      hasLast = true;
      lastResult = result;
      lastAtNanos = now;
    }
  }
  static final class CellHit {
    final SectorEntityToken value;
    final long atNanos;
    CellHit(SectorEntityToken value, long atNanos) {
      this.value = value;
      this.atNanos = atNanos;
    }
  }
  // Map-open preprocessing caches

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static void updateSystemNebulasCached(Object owner, List systemNebulas, List constellationLabels,
      List nebulaStars) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (c == null || !c.systemNebulaCache || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
      invokeOriginalSystemNebulaBuilder(owner);
      return;
    }
    systemNebulas.clear();
    constellationLabels.clear();
    nebulaStars.clear();
    Throwable cacheFailure = null;
    try {
      SectorAPI sector = Global.getSector();
      if (sector != null) {
        NebulaCache cache = getOrBuildNebulaCache(sector, c, generation);
        if (cache != null) {
          SystemNebulaAccess access = SystemNebulaAccess.ACCESS.get(owner.getClass());
          for (int i = 0; i < cache.systemNebulaSystems.size(); i++) {
            StarSystemAPI system = cache.systemNebulaSystems.get(i);
            Object terrain = access.createNebula.invoke(null, system);
            if (!(terrain instanceof SectorEntityToken token)) {
              throw new IllegalStateException("createNebula returned " + terrain);
            }
            token.getCustomData().put("system", system);
            token.getCustomData().put("seed", Long.valueOf(cache.systemNebulaSeeds[i]));
            systemNebulas.add(terrain);
          }
          for (Constellation constellation : cache.constellations) {
            Object label = access.createConstellationLabel.invoke(null, constellation);
            if (!(label instanceof SectorEntityToken token)) {
              throw new IllegalStateException("createConstellationLabel returned " + label);
            }
            token.getCustomData().put("constellationLabel", Boolean.TRUE);
            token.getCustomData().put("constellation", constellation);
            constellationLabels.add(label);
          }
          nebulaStars.addAll(cache.nebulaStars);
          StarsectorPrepatcherMetrics.NEBULA_HITS.increment();
          return;
        }
      }
    } catch (Throwable ex) {
      cacheFailure = ex;
    }

    systemNebulas.clear();
    constellationLabels.clear();
    nebulaStars.clear();
    if (cacheFailure != null) {
      PrepatcherLog.warn("System-nebula metadata cache failed; using vanilla builder: "
          + cacheFailure.getClass().getSimpleName() + ": " + cacheFailure.getMessage());
    }
    // Never place the original call inside the compatibility catch: if game
    // or mod code throws, it must be observed after exactly one invocation.
    invokeOriginalSystemNebulaBuilder(owner);
  }
  static NebulaCache getOrBuildNebulaCache(SectorAPI sector, PrepatcherConfig c, long generation) {
    int systems = sector.getStarSystems().size();
    long now = System.nanoTime();
    NebulaCacheSlot slot = nebulaCacheSlot;
    NebulaCache current = slot.value;
    if (StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation) && nebulaCacheSlot == slot && current != null
        && current.sector.get() == sector && current.systemCount == systems
        && (c.systemNebulaMaxAgeMs <= 0 || now - current.createdAtNanos <= c.systemNebulaMaxAgeMs * 1_000_000L)) {
      return current;
    }
    synchronized (slot) {
      if (nebulaCacheSlot != slot || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) return null;
      current = slot.value;
      if (current != null && current.sector.get() == sector && current.systemCount == systems
          && (c.systemNebulaMaxAgeMs <= 0 || now - current.createdAtNanos <= c.systemNebulaMaxAgeMs * 1_000_000L)) {
        return current;
      }
      NebulaCache built = buildNebulaMetadata(sector, now);
      if (nebulaCacheSlot != slot || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) return null;
      slot.value = built;
      // A lifecycle transition may run between the pre-publication check
      // and assignment. It swaps the static slot without waiting here, so
      // this write can then reach only the detached slot. Clear it eagerly
      // and refuse the stale result if either identity check changed.
      if (nebulaCacheSlot != slot || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
        slot.value = null;
        return null;
      }
      StarsectorPrepatcherMetrics.NEBULA_MISSES.increment();
      return built;
    }
  }
  static NebulaCache buildNebulaMetadata(SectorAPI sector, long now) {
    long baseSeed = 0L;
    String seedString = sector.getSeedString();
    if (seedString != null && seedString.length() > 3) {
      baseSeed = Long.parseLong(seedString.substring(3));
    }
    Random random = Misc.getRandom(baseSeed, 10);
    ArrayList<StarSystemAPI> nebulaSystems = new ArrayList<>();
    ArrayList<Long> seeds = new ArrayList<>();
    HashSet<Constellation> constellationSet = new HashSet<>();
    ArrayList<SectorEntityToken> nebulaStars = new ArrayList<>();
    for (StarSystemAPI system : sector.getStarSystems()) {
      if (Boolean.TRUE.equals(system.hasSystemwideNebula()) && system.getAge() != null) {
        nebulaSystems.add(system);
        seeds.add(random.nextLong());
      }
      if (system.isInConstellation()) {
        Constellation constellation = system.getConstellation();
        if (constellation != null) constellationSet.add(constellation);
      }
      if (system.getType() == StarSystemGenerator.StarSystemType.NEBULA && system.getStar() != null
          && system.getStar().getSpec().isNebulaCenter()) {
        nebulaStars.add(system.getStar());
      }
    }
    long[] seedArray = new long[seeds.size()];
    for (int i = 0; i < seeds.size(); i++) seedArray[i] = seeds.get(i);
    return new NebulaCache(new WeakReference<>(sector), sector.getStarSystems().size(), now, nebulaSystems, seedArray,
        new ArrayList<>(constellationSet), nebulaStars);
  }
  static void invokeOriginalSystemNebulaBuilder(Object owner) {
    try {
      SystemNebulaOriginalAccess.ORIGINAL.get(owner.getClass()).invoke(owner);
    } catch (InvocationTargetException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof RuntimeException) throw (RuntimeException) cause;
      if (cause instanceof Error) throw (Error) cause;
      throw new RuntimeException(cause);
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException("Unable to invoke original Starsector system-nebula builder", ex);
    }
  }
  static final class SystemNebulaOriginalAccess {
    static final ClassValue<Method> ORIGINAL = new ClassValue<>() {
      @Override
      protected Method computeValue(Class<?> type) {
        try {
          Method original = StarsectorPrepatcherHooks.findDeclaredMethod(type, "smo$originalUpdateSystemNebulas");
          original.setAccessible(true);
          return original;
        } catch (ReflectiveOperationException ex) {
          throw new IllegalStateException(ex);
        }
      }
    };
  }
  static final class SystemNebulaAccess {
    static final ClassValue<SystemNebulaAccess> ACCESS = new ClassValue<>() {
      @Override
      protected SystemNebulaAccess computeValue(Class<?> type) {
        try {
          Method createNebula = type.getMethod("createNebula", StarSystemAPI.class);
          Method createConstellationLabel = type.getMethod("createConstellationLabel", Constellation.class);
          return new SystemNebulaAccess(createNebula, createConstellationLabel);
        } catch (ReflectiveOperationException ex) {
          throw new IllegalStateException(ex);
        }
      }
    };

    final Method createNebula;
    final Method createConstellationLabel;

    SystemNebulaAccess(Method createNebula, Method createConstellationLabel) {
      this.createNebula = createNebula;
      this.createConstellationLabel = createConstellationLabel;
    }
  }
  public static void forceClearSampleCacheThrottled(Object plugin) {
    if (plugin == null) return;
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (c == null || !c.sampleCacheClearThrottle || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)
        || c.sampleCacheClearMinIntervalMs <= 0) {
      ((BaseTiledTerrain) plugin).forceClearSampleCache();
      return;
    }
    long now = System.nanoTime();
    long interval = c.sampleCacheClearMinIntervalMs * 1_000_000L;
    WeakHashMap<Object, Long> sampleClearTimes = SAMPLE_CLEAR_TIMES;
    synchronized (sampleClearTimes) {
      if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
        // Fall through to the exact vanilla clear below.
      } else {
        Long previous = sampleClearTimes.get(plugin);
        if (StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation) && previous != null
            && now - previous < interval) {
          StarsectorPrepatcherMetrics.SAMPLE_CLEAR_SKIPS.increment();
          return;
        }
        if (StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
          sampleClearTimes.put(plugin, now);
          if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
            // Protect against a re-entrant reset from custom key code.
            sampleClearTimes.clear();
          }
        }
      }
    }
    // A generation change only disables this optional throttle. The exact
    // vanilla clear still runs, preserving all behavior and side effects.
    ((BaseTiledTerrain) plugin).forceClearSampleCache();
  }
  public static float gridSpacing() {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.gridLineCap || c.gridMaxLinesPerAxis <= 0) { return c == null ? 2000f : c.gridBaseSpacing; }
    try {
      float width = Math.abs(Global.getSettings().getFloat("sectorWidth"));
      float height = Math.abs(Global.getSettings().getFloat("sectorHeight"));
      if (Float.floatToIntBits(width) == Float.floatToIntBits(cachedGridWidth)
          && Float.floatToIntBits(height) == Float.floatToIntBits(cachedGridHeight) && cachedGridSpacing > 0f) {
        return cachedGridSpacing;
      }
      float base = c.gridBaseSpacing;
      float maxDimension = Math.max(width, height);
      float lines = maxDimension / base;
      int multiplier = Math.max(1, (int) Math.ceil(lines / c.gridMaxLinesPerAxis));
      float spacing = base * multiplier;
      cachedGridWidth = width;
      cachedGridHeight = height;
      cachedGridSpacing = spacing;
      if (spacing > base) {
        PrepatcherLog
            .info("Map grid LOD: sector=" + width + "x" + height + ", spacing=" + spacing + " (base=" + base + ")");
      }
      return spacing;
    } catch (Throwable ex) {
      return c.gridBaseSpacing;
    }
  }
  static final class NebulaCacheSlot {
    volatile NebulaCache value;
  }
  static final class NebulaCache {
    final WeakReference<SectorAPI> sector;
    final int systemCount;
    final long createdAtNanos;
    final List<StarSystemAPI> systemNebulaSystems;
    final long[] systemNebulaSeeds;
    final List<Constellation> constellations;
    final List<SectorEntityToken> nebulaStars;

    NebulaCache(WeakReference<SectorAPI> sector, int systemCount, long createdAtNanos,
        List<StarSystemAPI> systemNebulaSystems, long[] systemNebulaSeeds, List<Constellation> constellations,
        List<SectorEntityToken> nebulaStars) {
      this.sector = sector;
      this.systemCount = systemCount;
      this.createdAtNanos = createdAtNanos;
      this.systemNebulaSystems = systemNebulaSystems;
      this.systemNebulaSeeds = systemNebulaSeeds;
      this.constellations = constellations;
      this.nebulaStars = nebulaStars;
    }
  }
}
