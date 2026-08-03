
package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.CampaignEngineLayers;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import com.starsector.prepatcher.agent.PrepatcherLog;
import java.lang.ref.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

public final class StarsectorPrepatcherHyperspaceHooks {
    private static volatile PrepatcherConfig cfg;
    private static volatile LongSupplier NANO_CLOCK=System::nanoTime;
    private static final LongAdder randomCalls=new LongAdder();
    private static final LongAdder layerFilters=new LongAdder(), autoAlloc=new LongAdder(), autoReuse=new LongAdder();
    private static final LongAdder autoInternalReads=new LongAdder();
    private static final LongAdder listBorrows=new LongAdder(), removeCalls=new LongAdder(), linearRemoves=new LongAdder(), removedItems=new LongAdder();
    private static final LongAdder starfieldListDrops=new LongAdder();
    private static final LongAdder starfieldLifecyclePoolResets=new LongAdder();
    private static final LongAdder starfieldStaleLeaseDrops=new LongAdder();
    private static volatile int starfieldFreeListCount;
    private static volatile long starfieldRetainedCapacity;
    private static volatile long starfieldPoolGeneration;
    private static WeakReference<Thread> statsThread=new WeakReference<>(null);
    private static long lastRandomApprox;

    private static long nanoTime(){LongSupplier clock=NANO_CLOCK;return clock==null?System.nanoTime():clock.getAsLong();}

    static synchronized void configure(PrepatcherConfig c){
        cfg=c;
        if(c!=null && c.statsLogIntervalSeconds>0)startStatsThread();
    }
    private static void startStatsThread(){
        Thread running=statsThread.get();
        if(running!=null && running.isAlive())return;
        try{
            Thread thread=new Thread(StarsectorPrepatcherHyperspaceHooks::statsLoop,"StarsectorPrepatcher-Stats");
            thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();statsThread=new WeakReference<>(thread);
        }catch(Throwable ex){
            statsThread=new WeakReference<>(null);
            try{PrepatcherLog.error("Hyperspace statistics logger could not start",ex);}catch(Throwable ignored){}
        }
    }
    public static float cullHeight(Object terrain, ViewportAPI viewport){
        return viewport.getVisibleHeight();
    }
    public static int yDimension(Object array,Object terrain){
        if(array==null) return 0;
        try { int n=Array.getLength(array); if(n==0)return 0; Object row=Array.get(array,0); return row==null?0:Array.getLength(row); }
        catch(Throwable t){return Array.getLength(array);}
    }
    public static java.util.EnumSet filterLayers(java.util.EnumSet set,Object terrain){
        if(set!=null && set.remove(CampaignEngineLayers.TERRAIN_9)) layerFilters.increment();
        return set;
    }
    public static java.util.Set filterLayerSet(java.util.Set set,Object terrain){
        if(set!=null && set.remove(CampaignEngineLayers.TERRAIN_9)) layerFilters.increment();
        return set;
    }

    private static final ReferenceQueue<RandomPool> RANDOM_POOL_QUEUE=new ReferenceQueue<>();
    private static final ConcurrentLinkedQueue<RandomPoolRef> RANDOM_POOL_REFS=new ConcurrentLinkedQueue<>();
    private static final class RandomPoolRef extends WeakReference<RandomPool>{
        // Primitive-only tail state lets the logger include incomplete batches
        // without retaining the owning Thread (or anything reachable from it).
        int pendingStats;
        RandomPoolRef(RandomPool pool){super(pool,RANDOM_POOL_QUEUE);}
    }
    private static final class RandomPool {
        final Random[] a=new Random[64]; final RandomPoolRef stats; int p;
        RandomPool(){stats=new RandomPoolRef(this);RANDOM_POOL_REFS.add(stats);}
    }
    private static final ThreadLocal<RandomPool> RANDOMS=ThreadLocal.withInitial(RandomPool::new);
    public static Random seededRandom(long seed){
        RandomPool p=RANDOMS.get(); int i=p.p++ & (p.a.length-1); Random r=p.a[i];
        if(r==null){r=new Random(seed);p.a[i]=r;}else r.setSeed(seed);
        // This hook can run millions of times per minute. Batch telemetry so the
        // statistics themselves do not become part of the terrain hot path.
        if(++p.stats.pendingStats>=4096){int completed=p.stats.pendingStats;p.stats.pendingStats=0;randomCalls.add(completed);}
        return r;
    }

    private static final String AUTOMATON_MARKER="smo$automatonBufferInstrumented";
    private static final class AutomatonAccess {
        final Field directCells;
        final Method virtualGetter;
        final Throwable getterFailure;
        AutomatonAccess(Field directCells,Method virtualGetter,Throwable getterFailure){
            this.directCells=directCells;this.virtualGetter=virtualGetter;this.getterFailure=getterFailure;
        }
        int[][] direct(Object owner)throws IllegalAccessException{return (int[][])directCells.get(owner);}
        int[][] virtual(Object owner){
            if(virtualGetter==null)throw new RuntimeException("Unable to resolve hyperspace automaton getCells",getterFailure);
            try{return (int[][])virtualGetter.invoke(owner);}
            catch(InvocationTargetException ex){throw propagate(ex.getCause());}
            catch(Throwable ex){throw new RuntimeException("Unable to invoke hyperspace automaton getCells",ex);}
        }
    }
    private static final ClassValue<AutomatonAccess> AUTOMATON_ACCESS = new ClassValue<>() {
        @Override protected AutomatonAccess computeValue(Class<?> type) {
            Field direct=null;Method getter=null;Throwable getterFailure=null;
            try{
                Field marker=type.getDeclaredField(AUTOMATON_MARKER);
                int modifiers=marker.getModifiers();
                if(marker.getType()!=boolean.class || !Modifier.isStatic(modifiers)
                        || !Modifier.isFinal(modifiers) || !marker.isSynthetic())
                    throw new IllegalStateException("invalid automaton instrumentation marker");
                marker.setAccessible(true);
                if(!marker.getBoolean(null))throw new IllegalStateException("disabled automaton instrumentation marker");
                Field cells=type.getDeclaredField("cells");
                if(cells.getType()!=int[][].class)throw new IllegalStateException("invalid automaton cells field");
                cells.setAccessible(true);direct=cells;
            }catch(Throwable ignored){direct=null;}
            try{
                getter=accessibleVirtualGetter(type);
            }catch(Throwable ex){getterFailure=ex;}
            return new AutomatonAccess(direct,getter,getterFailure);
        }
    };

    private static Method accessibleVirtualGetter(Class<?> type)throws NoSuchMethodException{
        Throwable mismatch=null;
        for(Class<?> current=type;current!=null;current=current.getSuperclass()){
            Method candidate;
            try{candidate=current.getDeclaredMethod("getCells");}
            catch(NoSuchMethodException missing){continue;}
            if(candidate.getReturnType()!=int[][].class){
                mismatch=new IllegalStateException("getCells has an unexpected return type on "+current.getName());
                continue;
            }
            // Invoking a public base declaration still performs virtual dispatch,
            // while avoiding IllegalAccessException for non-public mod subclasses.
            if(Modifier.isPublic(current.getModifiers())
                    && Modifier.isPublic(candidate.getModifiers()))return candidate;
        }
        NoSuchMethodException failure=new NoSuchMethodException(
                "No accessible public getCells()[[I declaration for "+type.getName());
        if(mismatch!=null)failure.initCause(mismatch);
        throw failure;
    }

    /**
     * Engine-internal terrain reads must not mark the public cells array as
     * escaped. Direct access requires the exact target class plus the synthetic
     * success marker installed with the buffer patch. Subclasses, unpatched
     * owners and inaccessible fields use the cached virtual getter instead;
     * that disables unsafe reuse rather than risking alias corruption.
     */
    public static int[][] automatonCellsInternal(Object owner,String exactOwnerClass){
        Class<?> type=owner.getClass();AutomatonAccess access=AUTOMATON_ACCESS.get(type);
        if(exactOwnerClass!=null && type.getName().equals(exactOwnerClass) && access.directCells!=null){
            try{int[][] result=access.direct(owner);autoInternalReads.increment();return result;}
            catch(Throwable ignored){/* Fail closed through the virtual getter. */}
        }
        return access.virtual(owner);
    }

    private static RuntimeException propagate(Throwable t){
        StarsectorPrepatcherHyperspaceHooks.<RuntimeException>rethrowUnchecked(t);
        throw new AssertionError("unreachable");
    }
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrowUnchecked(Throwable t)throws T{throw (T)t;}

    /**
     * Returns the previous cells buffer only while it has not escaped through
     * HyperspaceAutomaton.getCells(). The candidate is stored on the automaton
     * itself, not in a process-lifetime agent cache, so campaign disposal needs
     * no special lifecycle callback.
     */
    public static int[][] retainAutomatonSpare(int[][] candidate,boolean exposed,
                                                Object owner,String exactOwnerClass){
        // `cells` is protected, so a mod subclass can expose it without calling
        // getCells(). Reuse is safe only for the exact guarded vanilla class;
        // subclasses retain vanilla's one-shot buffer identity semantics.
        if(exposed || owner==null || exactOwnerClass==null
                || !owner.getClass().getName().equals(exactOwnerClass))return null;
        return candidate;
    }
    /** Preserve the zero-initialization contract of MULTIANEWARRAY on reuse. */
    public static int[][] acquireAutomatonBuffer(int rows,int cols,int[][] spare){
        if(rows<0||cols<0)return new int[rows][cols];
        if(valid(spare,rows,cols)){
            for(int[] row:spare) Arrays.fill(row,0);
            autoReuse.increment();return spare;
        }
        autoAlloc.increment();return new int[rows][cols];
    }
    private static boolean valid(int[][] a,int r,int c){
        if(a==null || a.length!=r)return false;
        for(int[] row:a)if(row==null || row.length!=c)return false;
        return true;
    }

    private static final class PooledList extends ArrayList<Object> {
        int highWater;
        boolean oversizedTouched;
        long oversizedLastUseNanos=Long.MIN_VALUE;
        long leaseGeneration=Long.MIN_VALUE;
        void beginLease(long generation){leaseGeneration=generation;}
        void observe(int size){
            if(size>highWater)highWater=size;
            PrepatcherConfig c=cfg;
            if(c!=null && size>c.starfieldPoolMaxRetainedCapacity)oversizedTouched=true;
        }
        void finishLease(long now){
            if(oversizedTouched){oversizedLastUseNanos=now;oversizedTouched=false;}
        }
        @Override public boolean add(Object value){boolean changed=super.add(value);if(changed)observe(size());return changed;}
        @Override public void add(int index,Object value){super.add(index,value);observe(size());}
        @Override public boolean addAll(Collection<?> values){boolean changed=super.addAll(values);if(changed)observe(size());return changed;}
        @Override public boolean addAll(int index,Collection<?> values){boolean changed=super.addAll(index,values);if(changed)observe(size());return changed;}
    }
    private static final class ListPool {
        long generation;
        final ArrayDeque<PooledList> free=new ArrayDeque<>();
        ListPool(long generation){this.generation=generation;}
    }
    private static final ThreadLocal<ListPool> LISTS=
            ThreadLocal.withInitial(()->new ListPool(starfieldPoolGeneration));

    /**
     * Returns the current thread's pool for the active campaign generation.
     * Lifecycle changes normally remove the campaign-thread value immediately;
     * the generation check also lazily detaches any pool created on another
     * thread without retaining that Thread in a process-lifetime registry.
     */
    private static ListPool currentListPool(){
        long generation=starfieldPoolGeneration;
        ListPool pool=LISTS.get();
        if(pool.generation!=generation){
            int dropped=pool.free.size();
            pool.free.clear();
            if(dropped>0)starfieldListDrops.add(dropped);
            pool.generation=generation;
            publishPoolGauges(pool);
        }
        return pool;
    }

    @SuppressWarnings("rawtypes") public static ArrayList borrowArrayList(){
        ListPool p=currentListPool();PooledList a=p.free.pollFirst();
        if(a==null)a=new PooledList();else a.clear();
        a.beginLease(p.generation);listBorrows.increment();publishPoolGauges(p);return a;
    }
    @SuppressWarnings({"rawtypes","unchecked"}) public static boolean removeAllAndRelease(Collection receiver,Collection removals){
        boolean changed=false; removeCalls.increment();
        try{
            if(cfg!=null && cfg.starfieldLinearRemoval && removals!=null && receiver!=null
                    && removals.size()>=cfg.starfieldRemoveAllThreshold
                    && receiver.size()>=cfg.starfieldRemoveAllThreshold*2){
                Set membership=membership(removals); int before=receiver.size();
                for(Iterator it=receiver.iterator();it.hasNext();) if(membership.contains(it.next())){it.remove();changed=true;}
                int d=before-receiver.size(); if(d>0)removedItems.add(d); linearRemoves.increment();
            } else if(receiver!=null) changed=receiver.removeAll(removals);
            return changed;
        } finally {
            if(removals instanceof PooledList a){
                long generation=starfieldPoolGeneration;
                if(a.leaseGeneration!=generation){
                    a.clear();a.oversizedTouched=false;a.leaseGeneration=Long.MIN_VALUE;
                    starfieldStaleLeaseDrops.increment();starfieldListDrops.increment();
                }else{
                    long now=nanoTime();a.finishLease(now);a.clear();ListPool p=currentListPool();
                    if(a.leaseGeneration!=p.generation){
                        a.leaseGeneration=Long.MIN_VALUE;
                        starfieldStaleLeaseDrops.increment();starfieldListDrops.increment();
                    }else if(p.free.size()<32){
                        a.leaseGeneration=Long.MIN_VALUE;p.free.addFirst(a);
                    }else{
                        a.leaseGeneration=Long.MIN_VALUE;starfieldListDrops.increment();
                    }
                    publishPoolGauges(p);
                }
            }
        }
    }

    /**
     * Detaches retained backing arrays at every campaign lifecycle boundary.
     * Borrowed lists carry the old generation and are discarded when returned,
     * preventing an in-flight old lease from repopulating the new campaign pool.
     */
    static void campaignLifecycleReset(long generation){
        starfieldPoolGeneration=generation;
        ListPool pool=LISTS.get();
        int dropped=pool.free.size();
        pool.free.clear();
        pool.generation=generation;
        if(dropped>0)starfieldListDrops.add(dropped);
        starfieldLifecyclePoolResets.increment();
        publishPoolGauges(pool);
        LISTS.remove();
    }

    static void runPoolMaintenance(long now,boolean forced){
        PrepatcherConfig c=cfg;if(c==null)return;ListPool p=currentListPool();
        long grace=Math.max(0L,(long)c.starfieldPoolOversizedGraceMs*1_000_000L);
        for(Iterator<PooledList> it=p.free.iterator();it.hasNext();){
            PooledList list=it.next();
            if(list.highWater>c.starfieldPoolMaxRetainedCapacity
                    && list.oversizedLastUseNanos!=Long.MIN_VALUE
                    && now-list.oversizedLastUseNanos>=grace){
                it.remove();starfieldListDrops.increment();
            }
        }
        while(p.free.size()>32){p.free.removeLast();starfieldListDrops.increment();}
        publishPoolGauges(p);
    }
    private static void publishPoolGauges(ListPool p){
        long capacity=0L;for(PooledList list:p.free)capacity+=Math.max(0,list.highWater);
        starfieldFreeListCount=p.free.size();starfieldRetainedCapacity=capacity;
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private static Set membership(Collection c){
        boolean identity=true, broken=false;
        for(Object o:c){if(o==null)continue;Class<?> k=o.getClass();boolean e=decl(k,"equals",Object.class),h=decl(k,"hashCode");if(e||h)identity=false;if(e!=h)broken=true;}
        if(broken) return new AbstractSet(){public Iterator iterator(){return c.iterator();}public int size(){return c.size();}public boolean contains(Object o){return c.contains(o);}};
        if(identity){Set s=Collections.newSetFromMap(new IdentityHashMap<>());s.addAll(c);return s;}
        return new HashSet(c);
    }
    private static boolean decl(Class<?> c,String n,Class<?>...p){for(Class<?> x=c;x!=null && x!=Object.class;x=x.getSuperclass())try{x.getDeclaredMethod(n,p);return true;}catch(NoSuchMethodException ignored){}catch(Throwable t){return true;}return false;}

    public static long pooledRandomApprox(){
        Reference<? extends RandomPool> stale;
        while((stale=RANDOM_POOL_QUEUE.poll())!=null){
            RandomPoolRef retired=(RandomPoolRef)stale;int tail=retired.pendingStats;retired.pendingStats=0;
            if(tail>0)randomCalls.add(tail);
            RANDOM_POOL_REFS.remove(retired);
        }
        long total=randomCalls.sum();
        for(RandomPoolRef ref:RANDOM_POOL_REFS){
            total+=Math.max(0,ref.pendingStats);
        }
        synchronized(StarsectorPrepatcherHyperspaceHooks.class){
            if(total<lastRandomApprox)return lastRandomApprox;
            return lastRandomApprox=total;
        }
    }
    public static String statsSnapshot(){
        return "stats pooledRandomApprox="+pooledRandomApprox()
                +" noOpLayers="+layerFilters.sum()
                +" automatonAlloc="+autoAlloc.sum()
                +" automatonReuse="+autoReuse.sum()
                +" automatonInternalReads="+autoInternalReads.sum()
                +" listBorrows="+listBorrows.sum()
                +" removeCalls="+removeCalls.sum()
                +" linearRemoves="+linearRemoves.sum()
                +" removedItems="+removedItems.sum()
                +" starfieldFreeListCount="+starfieldFreeListCount
                +" starfieldRetainedCapacity="+starfieldRetainedCapacity
                +" starfieldListDrops="+starfieldListDrops.sum()
                +" starfieldLifecyclePoolResets="+starfieldLifecyclePoolResets.sum()
                +" starfieldStaleLeaseDrops="+starfieldStaleLeaseDrops.sum();
    }
    private static void statsLoop(){
        Thread current=Thread.currentThread();
        try{
            while(true){
                PrepatcherConfig c=cfg;if(c==null||c.statsLogIntervalSeconds<=0)return;
                long millis=Math.max(1L,c.statsLogIntervalSeconds*1000L);
                Thread.sleep(millis);
                try{PrepatcherLog.info("Hyperspace "+statsSnapshot());}
                catch(Throwable ex){PrepatcherLog.error("Hyperspace statistics logger failed",ex);}
            }
        }catch(InterruptedException ex){current.interrupt();}
        finally{
            synchronized(StarsectorPrepatcherHyperspaceHooks.class){
                if(statsThread.get()==current)statsThread=new WeakReference<>(null);
            }
        }
    }
    private StarsectorPrepatcherHyperspaceHooks(){}
}
