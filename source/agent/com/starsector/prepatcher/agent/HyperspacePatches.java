
package com.starsector.prepatcher.agent;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Structural patch routines for the hyperspace/terrain target classes. */
final class HyperspacePatches {
    static final String HOOK=
            "com/fs/starfarer/api/StarsectorPrepatcherHyperspaceHooks";
    static final String AUTO_SPARE="smo$automatonSpare";
    static final String AUTO_EXPOSED="smo$automatonCellsExposed";
    static final String AUTO_MARKER="smo$automatonBufferInstrumented";
    static final String BASE_TILED="com/fs/starfarer/api/impl/campaign/terrain/BaseTiledTerrain";
    static final String HYPER_TERRAIN="com/fs/starfarer/api/impl/campaign/terrain/HyperspaceTerrainPlugin";
    static final String AUTOMATON="com/fs/starfarer/api/impl/campaign/terrain/HyperspaceAutomaton";
    static final String STARFIELD="com/fs/starfarer/combat/OOOo";
    static final List<String> TARGET_CLASSES=List.of(BASE_TILED,HYPER_TERRAIN,AUTOMATON,STARFIELD);

    private HyperspacePatches() {}
    static AbstractInsnNode nextReal(AbstractInsnNode n){for(n=n.getNext();n!=null && (n instanceof LabelNode||n instanceof LineNumberNode||n instanceof FrameNode);n=n.getNext());return n;}
    static AbstractInsnNode prevReal(AbstractInsnNode n){for(n=n.getPrevious();n!=null && (n instanceof LabelNode||n instanceof LineNumberNode||n instanceof FrameNode);n=n.getPrevious());return n;}
    /**
     * Atomically correct the two coupled defects in BaseTiledTerrain's vertical viewport bounds:
     * yEnd must use visible height, and the yEnd clamp must use the inner tile-array dimension.
     *
     * The two changes share one semantic expression and therefore one transformation surface. All
     * expected methods are analyzed before either instruction is changed. A partial or ambiguous
     * input returns zero and leaves the ClassNode untouched.
     */
    static int patchViewportBounds(ClassNode cn){
        ArrayList<ViewportBoundsCandidate> candidates=new ArrayList<>();
        for(MethodNode method:cn.methods){
            ViewportBoundsCandidate candidate=originalViewportBoundsCandidate(method);
            if(candidate!=null)candidates.add(candidate);
        }
        if(candidates.size()!=2)return 0;
        for(ViewportBoundsCandidate candidate:candidates){
            candidate.verticalRangeLoad.var=candidate.heightLocal;
            InsnList replacement=new InsnList();
            replacement.add(new InsnNode(Opcodes.ICONST_0));
            replacement.add(new InsnNode(Opcodes.AALOAD));
            replacement.add(new InsnNode(Opcodes.ARRAYLENGTH));
            candidate.method.instructions.insertBefore(candidate.verticalClampLength,replacement);
            candidate.method.instructions.remove(candidate.verticalClampLength);
        }
        return candidates.size();
    }

    static int countPatchedViewportBounds(ClassNode cn){
        int total=0;
        for(MethodNode method:cn.methods)if(hasPatchedViewportBounds(method))total++;
        return total;
    }

    private static ViewportBoundsCandidate originalViewportBoundsCandidate(MethodNode method){
        ViewportLocals locals=viewportLocals(method);
        if(locals==null)return null;

        ArrayList<VarInsnNode> widthLoads=new ArrayList<>();
        int heightLoads=0;
        for(AbstractInsnNode n=locals.afterHeight.getNext();n!=null;n=n.getNext()){
            if(!(n instanceof VarInsnNode load)||load.getOpcode()!=Opcodes.FLOAD)continue;
            if(load.var==locals.widthLocal)widthLoads.add(load);
            else if(load.var==locals.heightLocal)heightLoads++;
        }
        if(widthLoads.size()!=3||heightLoads!=1)return null;

        ClampShape clamp=clampShape(method);
        if(clamp.direct.size()!=2||clamp.nested!=0)return null;
        return new ViewportBoundsCandidate(method,locals.heightLocal,widthLoads.get(2),
                clamp.direct.get(1));
    }

    private static boolean hasPatchedViewportBounds(MethodNode method){
        ViewportLocals locals=viewportLocals(method);
        if(locals==null)return false;
        int widthLoads=0,heightLoads=0;
        for(AbstractInsnNode n=locals.afterHeight.getNext();n!=null;n=n.getNext()){
            if(!(n instanceof VarInsnNode load)||load.getOpcode()!=Opcodes.FLOAD)continue;
            if(load.var==locals.widthLocal)widthLoads++;
            else if(load.var==locals.heightLocal)heightLoads++;
        }
        if(widthLoads!=2||heightLoads!=2)return false;
        ClampShape clamp=clampShape(method);
        return clamp.direct.size()==1&&clamp.nested==1;
    }

    private static ViewportLocals viewportLocals(MethodNode method){
        int width=-1,height=-1;
        AbstractInsnNode afterHeight=null;
        int widthCalls=0,heightCalls=0;
        for(AbstractInsnNode n=method.instructions.getFirst();n!=null;n=n.getNext()){
            if(!(n instanceof MethodInsnNode call))continue;
            if(!call.desc.equals("()F"))continue;
            AbstractInsnNode store=nextReal(call);
            if(!(store instanceof VarInsnNode local)||local.getOpcode()!=Opcodes.FSTORE)continue;
            if(call.name.equals("getVisibleWidth")){
                widthCalls++;width=local.var;
            }else if(call.name.equals("getVisibleHeight")){
                heightCalls++;height=local.var;afterHeight=store;
            }
        }
        if(widthCalls!=1||heightCalls!=1||width<0||height<0||afterHeight==null)return null;
        return new ViewportLocals(width,height,afterHeight);
    }

    private static ClampShape clampShape(MethodNode method){
        ArrayList<InsnNode> direct=new ArrayList<>();
        int nested=0;
        for(AbstractInsnNode n=method.instructions.getFirst();n!=null;n=n.getNext()){
            if(!(n instanceof InsnNode length)||length.getOpcode()!=Opcodes.ARRAYLENGTH)continue;
            AbstractInsnNode next1=nextReal(length);
            AbstractInsnNode next2=next1==null?null:nextReal(next1);
            AbstractInsnNode next3=next2==null?null:nextReal(next2);
            if(next1==null||next1.getOpcode()!=Opcodes.I2F
                    ||next2==null||next2.getOpcode()!=Opcodes.FCMPL
                    ||!(next3 instanceof JumpInsnNode))continue;
            AbstractInsnNode previous=prevReal(length);
            if(previous instanceof FieldInsnNode field
                    &&field.getOpcode()==Opcodes.GETFIELD&&field.desc.startsWith("[[")){
                direct.add(length);continue;
            }
            if(previous==null||previous.getOpcode()!=Opcodes.AALOAD)continue;
            AbstractInsnNode zero=prevReal(previous),fieldInsn=prevReal(zero);
            if(zero!=null&&zero.getOpcode()==Opcodes.ICONST_0
                    &&fieldInsn instanceof FieldInsnNode field
                    &&field.getOpcode()==Opcodes.GETFIELD&&field.desc.startsWith("[["))nested++;
        }
        return new ClampShape(direct,nested);
    }

    private static final class ViewportLocals{
        final int widthLocal,heightLocal;
        final AbstractInsnNode afterHeight;
        ViewportLocals(int widthLocal,int heightLocal,AbstractInsnNode afterHeight){
            this.widthLocal=widthLocal;this.heightLocal=heightLocal;this.afterHeight=afterHeight;
        }
    }
    private static final class ClampShape{
        final ArrayList<InsnNode> direct;
        final int nested;
        ClampShape(ArrayList<InsnNode> direct,int nested){this.direct=direct;this.nested=nested;}
    }
    private static final class ViewportBoundsCandidate{
        final MethodNode method;
        final int heightLocal;
        final VarInsnNode verticalRangeLoad;
        final InsnNode verticalClampLength;
        ViewportBoundsCandidate(MethodNode method,int heightLocal,VarInsnNode verticalRangeLoad,
                                InsnNode verticalClampLength){
            this.method=method;this.heightLocal=heightLocal;
            this.verticalRangeLoad=verticalRangeLoad;
            this.verticalClampLength=verticalClampLength;
        }
    }
    static int patchLayers(ClassNode cn){
        int total=0;
        for(MethodNode m:cn.methods){if(!m.name.equals("getActiveLayers"))continue;boolean enumSet=m.desc.endsWith(")Ljava/util/EnumSet;");boolean set=m.desc.endsWith(")Ljava/util/Set;");if(!enumSet&&!set)continue;
            for(AbstractInsnNode n=m.instructions.getFirst();n!=null;n=n.getNext())if(n.getOpcode()==Opcodes.ARETURN){InsnList x=new InsnList();x.add(new VarInsnNode(Opcodes.ALOAD,0));x.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,enumSet?"filterLayers":"filterLayerSet",enumSet?"(Ljava/util/EnumSet;Ljava/lang/Object;)Ljava/util/EnumSet;":"(Ljava/util/Set;Ljava/lang/Object;)Ljava/util/Set;",false));m.instructions.insertBefore(n,x);total++;}
        }
        return total;
    }
    static int countPatchedLayers(ClassNode cn){
        return countStaticCalls(cn,"filterLayers")+countStaticCalls(cn,"filterLayerSet");
    }
    /**
     * HyperspaceTerrainPlugin is the engine-internal consumer of the automaton
     * array. Redirect its two getCells() calls to a direct field hook so they do
     * not mark the public array as escaped. Public/mod calls still go through
     * getCells() and therefore retain vanilla alias safety.
     */
    static int patchAutomatonInternalReads(ClassNode cn){
        final String owner="com/fs/starfarer/api/impl/campaign/terrain/HyperspaceAutomaton";
        int total=0;
        for(MethodNode m:cn.methods){
            for(AbstractInsnNode n=m.instructions.getFirst();n!=null;n=n.getNext()){
                if(!(n instanceof MethodInsnNode call)
                        || call.getOpcode()!=Opcodes.INVOKEVIRTUAL
                        || !call.owner.equals(owner) || !call.name.equals("getCells")
                        || !call.desc.equals("()[[I")) continue;
                m.instructions.insertBefore(call,new LdcInsnNode(owner.replace('/','.')));
                m.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,
                        "automatonCellsInternal","(Ljava/lang/Object;Ljava/lang/String;)[[I",false));
                total++;
            }
        }
        return total;
    }
    static int countPatchedAutomatonInternalReads(ClassNode cn){return countStaticCalls(cn,"automatonCellsInternal");}

    static int patchRandom(ClassNode cn){
        int total=0;
        for(MethodNode m:cn.methods){
            for(AbstractInsnNode n=m.instructions.getFirst();n!=null;){AbstractInsnNode next=n.getNext();
                if(n instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.NEW&&t.desc.equals("java/util/Random")){
                    AbstractInsnNode dup=nextReal(n);if(dup!=null&&dup.getOpcode()==Opcodes.DUP){
                        for(AbstractInsnNode q=dup.getNext();q!=null;q=q.getNext()){
                            if(q instanceof MethodInsnNode mi&&mi.getOpcode()==Opcodes.INVOKESPECIAL&&mi.owner.equals("java/util/Random")&&mi.name.equals("<init>")){
                                if(mi.desc.equals("(J)V")){m.instructions.remove(n);m.instructions.remove(dup);m.instructions.set(mi,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"seededRandom","(J)Ljava/util/Random;",false));total++;}
                                break;
                            }
                            if(q instanceof TypeInsnNode z&&z.getOpcode()==Opcodes.NEW)break;
                        }
                    }
                }
                if(n.getPrevious()==null && n.getNext()==null && next!=null) { n=nextReal(next); } else { n=next; }
            }
        }
        return total;
    }
    static int countPatchedRandom(ClassNode cn){return countStaticCalls(cn,"seededRandom");}
    static int patchAutomaton(ClassNode cn){
        for(FieldNode f:cn.fields)if(f.name.equals(AUTO_SPARE)||f.name.equals(AUTO_EXPOSED)||f.name.equals(AUTO_MARKER))return 0;
        boolean cells=false,next=false;
        for(FieldNode f:cn.fields){if(f.name.equals("cells")&&f.desc.equals("[[I"))cells=true;if(f.name.equals("next")&&f.desc.equals("[[I"))next=true;}
        if(!cells||!next)return 0;

        MethodNode getter=null,advance=null;
        for(MethodNode m:cn.methods){
            if(m.name.equals("getCells")&&m.desc.equals("()[[I"))getter=m;
            else if(m.name.equals("advance")&&m.desc.equals("(F)V"))advance=m;
        }
        if(getter==null||advance==null)return 0;

        ArrayList<InsnNode> returns=new ArrayList<>();
        for(AbstractInsnNode n=getter.instructions.getFirst();n!=null;n=n.getNext())if(n.getOpcode()==Opcodes.ARETURN)returns.add((InsnNode)n);
        ArrayList<FieldInsnNode> swaps=new ArrayList<>(),nextStores=new ArrayList<>();
        ArrayList<MultiANewArrayInsnNode> allocations=new ArrayList<>();
        for(AbstractInsnNode n=advance.instructions.getFirst();n!=null;n=n.getNext()){
            if(n instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.PUTFIELD&&f.owner.equals(cn.name)){
                if(f.name.equals("cells")&&f.desc.equals("[[I"))swaps.add(f);
                else if(f.name.equals("next")&&f.desc.equals("[[I"))nextStores.add(f);
            }else if(n instanceof MultiANewArrayInsnNode a&&a.desc.equals("[[I")&&a.dims==2)allocations.add(a);
        }
        if(returns.size()!=1||swaps.size()!=1||nextStores.size()!=1||allocations.size()!=1)return 0;
        FieldInsnNode swap=swaps.get(0),nextStore=nextStores.get(0);MultiANewArrayInsnNode allocation=allocations.get(0);
        if(!isBefore(swap,allocation)||!isBefore(allocation,nextStore))return 0;

        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_TRANSIENT|Opcodes.ACC_SYNTHETIC,AUTO_SPARE,"[[I",null,null));
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_TRANSIENT|Opcodes.ACC_SYNTHETIC,AUTO_EXPOSED,"Z",null,null));
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC|Opcodes.ACC_FINAL|Opcodes.ACC_SYNTHETIC,AUTO_MARKER,"Z",null,1));

        InsnList escaped=new InsnList();
        escaped.add(new VarInsnNode(Opcodes.ALOAD,0));escaped.add(new InsnNode(Opcodes.ICONST_1));
        escaped.add(new FieldInsnNode(Opcodes.PUTFIELD,cn.name,AUTO_EXPOSED,"Z"));
        getter.instructions.insertBefore(returns.get(0),escaped);

        InsnList stash=new InsnList();
        stash.add(new VarInsnNode(Opcodes.ALOAD,0));stash.add(new VarInsnNode(Opcodes.ALOAD,0));
        stash.add(new FieldInsnNode(Opcodes.GETFIELD,cn.name,"cells","[[I"));stash.add(new VarInsnNode(Opcodes.ALOAD,0));
        stash.add(new FieldInsnNode(Opcodes.GETFIELD,cn.name,AUTO_EXPOSED,"Z"));
        stash.add(new VarInsnNode(Opcodes.ALOAD,0));stash.add(new LdcInsnNode(cn.name.replace('/','.')));
        stash.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"retainAutomatonSpare","([[IZLjava/lang/Object;Ljava/lang/String;)[[I",false));
        stash.add(new FieldInsnNode(Opcodes.PUTFIELD,cn.name,AUTO_SPARE,"[[I"));
        advance.instructions.insertBefore(swap,stash);
        InsnList unexposed=new InsnList();
        unexposed.add(new VarInsnNode(Opcodes.ALOAD,0));unexposed.add(new InsnNode(Opcodes.ICONST_0));
        unexposed.add(new FieldInsnNode(Opcodes.PUTFIELD,cn.name,AUTO_EXPOSED,"Z"));
        advance.instructions.insert(swap,unexposed);

        InsnList spareArg=new InsnList();spareArg.add(new VarInsnNode(Opcodes.ALOAD,0));spareArg.add(new FieldInsnNode(Opcodes.GETFIELD,cn.name,AUTO_SPARE,"[[I"));
        advance.instructions.insertBefore(allocation,spareArg);
        advance.instructions.set(allocation,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"acquireAutomatonBuffer","(II[[I)[[I",false));
        InsnList clearSpare=new InsnList();clearSpare.add(new VarInsnNode(Opcodes.ALOAD,0));clearSpare.add(new InsnNode(Opcodes.ACONST_NULL));clearSpare.add(new FieldInsnNode(Opcodes.PUTFIELD,cn.name,AUTO_SPARE,"[[I"));
        advance.instructions.insert(nextStore,clearSpare);
        return 1;
    }
    static int countPatchedAutomaton(ClassNode cn){
        int spare=0,exposed=0,marker=0;
        for(FieldNode f:cn.fields){
            if(f.name.equals(AUTO_SPARE)&&f.desc.equals("[[I"))spare++;
            else if(f.name.equals(AUTO_EXPOSED)&&f.desc.equals("Z"))exposed++;
            else if(f.name.equals(AUTO_MARKER)&&f.desc.equals("Z")&&Integer.valueOf(1).equals(f.value))marker++;
        }
        return spare==1&&exposed==1&&marker==1
                &&countStaticCalls(cn,"retainAutomatonSpare")==1
                &&countStaticCalls(cn,"acquireAutomatonBuffer")==1?1:0;
    }
    static boolean isBefore(AbstractInsnNode first,AbstractInsnNode second){for(AbstractInsnNode n=first;n!=null;n=n.getNext())if(n==second)return true;return false;}
    static int patchCleanup(ClassNode cn){
        int total=0;
        for(MethodNode m:cn.methods){
            ArrayList<Candidate> cs=new ArrayList<>();
            for(AbstractInsnNode n=m.instructions.getFirst();n!=null;n=n.getNext()) if(n instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.NEW&&t.desc.equals("java/util/ArrayList")){
                AbstractInsnNode dup=nextReal(n);if(dup==null||dup.getOpcode()!=Opcodes.DUP)continue;AbstractInsnNode init=nextReal(dup);if(!(init instanceof MethodInsnNode mi)||mi.getOpcode()!=Opcodes.INVOKESPECIAL||!mi.owner.equals("java/util/ArrayList")||!mi.name.equals("<init>")||!mi.desc.equals("()V"))continue;AbstractInsnNode store=nextReal(init);if(store instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ASTORE)cs.add(new Candidate(t,(InsnNode)dup,mi,v.var));
            }
            for(Candidate c:cs){MethodInsnNode remove=null;boolean bad=false;for(AbstractInsnNode n=c.init.getNext();n!=null;n=n.getNext()){
                    if(n instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ALOAD&&v.var==c.slot){AbstractInsnNode nx=nextReal(n);if(nx instanceof MethodInsnNode mi){String key=mi.owner+"."+mi.name+mi.desc;if(mi.name.equals("removeAll")&&mi.desc.equals("(Ljava/util/Collection;)Z")){remove=mi;}else if(!(mi.name.equals("add")||mi.name.equals("isEmpty")||mi.name.equals("size")||mi.name.equals("iterator"))){bad=true;break;}}}
                    if(remove!=null){for(AbstractInsnNode q=remove.getNext();q!=null;q=q.getNext())if(q instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ALOAD&&v.var==c.slot){bad=true;break;}break;}
                }
                if(remove==null||bad)continue;
                m.instructions.remove(c.newNode);m.instructions.remove(c.dup);m.instructions.set(c.init,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"borrowArrayList","()Ljava/util/ArrayList;",false));
                m.instructions.set(remove,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"removeAllAndRelease","(Ljava/util/Collection;Ljava/util/Collection;)Z",false));total++;
            }
        }
        return total;
    }
    static int countPatchedCleanup(ClassNode cn){
        return countStaticCalls(cn,"borrowArrayList")==1
                &&countStaticCalls(cn,"removeAllAndRelease")==1?1:0;
    }
    static int countStaticCalls(ClassNode cn,String name){
        int total=0;
        for(MethodNode m:cn.methods)for(AbstractInsnNode n=m.instructions.getFirst();n!=null;n=n.getNext())
            if(n instanceof MethodInsnNode call&&call.getOpcode()==Opcodes.INVOKESTATIC
                    &&call.owner.equals(HOOK)&&call.name.equals(name))total++;
        return total;
    }
    static final class Candidate{final TypeInsnNode newNode;final InsnNode dup;final MethodInsnNode init;final int slot;Candidate(TypeInsnNode n,InsnNode d,MethodInsnNode i,int s){newNode=n;dup=d;init=i;slot=s;}}
}
