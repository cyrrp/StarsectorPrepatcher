
package com.starsector.prepatcher.agent;

import com.fs.starfarer.api.StarsectorPrepatcherHyperspaceHooks;
import com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

public final class HyperspaceCompatibilityTest {
    public static void main(String[] a)throws Exception{
        if(a.length<4)throw new IllegalArgumentException("usage: config core api report");
        PrepatcherConfig cfg=PrepatcherConfig.load(Paths.get(a[0]));
        Path core=Paths.get(a[1]),api=Paths.get(a[2]),report=Paths.get(a[3]);
        StarsectorPrepatcherRuntimeBridge.configure(cfg,report.getParent());PrepatcherTransformer tr=new PrepatcherTransformer(cfg);
        ArrayList<String> lines=new ArrayList<>();int patched=0,verified=0,unchanged=0,failed=0;
        try{runAutomatonRegression();lines.add("REGRESSION_OK automaton exact-owner marker, virtual fallback, alias isolation, zeroing, and safe reuse");}
        catch(Throwable x){failed++;lines.add("REGRESSION_FAIL automaton "+x);}
        try{runTelemetryRegression(cfg);lines.add("REGRESSION_OK cumulative telemetry includes unflushed random tails and stats worker restarts");}
        catch(Throwable x){failed++;lines.add("REGRESSION_FAIL telemetry "+x);}
        for(String target:HyperspacePatches.TARGET_CLASSES){
            byte[] in=read(core,target+".class");if(in==null)in=read(api,target+".class");if(in==null){lines.add("MISSING "+target);failed++;continue;}
            byte[] out=tr.transform(null,target,null,null,in);if(out==null){lines.add("UNCHANGED "+target);unchanged++;continue;}patched++;
            try{ClassNode cn=new ClassNode(Opcodes.ASM8);new ClassReader(out).accept(cn,ClassReader.EXPAND_FRAMES);int methods=0;for(MethodNode m:cn.methods){if((m.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))!=0)continue;new Analyzer<BasicValue>(new BasicVerifier()).analyze(cn.name,m);methods++;}verified+=methods;lines.add("OK "+target+" methods="+methods+" bytes="+in.length+"->"+out.length);}catch(Throwable x){failed++;lines.add("VERIFY_FAIL "+target+" "+x);}
        }
        try{
            byte[] in=read(api,HyperspacePatches.HYPER_TERRAIN+".class");
            byte[] variant=changeUnrelatedStringLiteral(in);
            byte[] out=new PrepatcherTransformer(cfg).transform(null,HyperspacePatches.HYPER_TERRAIN,null,null,variant);
            if(out==null)throw new AssertionError("unrelated string change rejected structural patches");
            lines.add("REGRESSION_OK unrelated string/constant-pool change does not require a class hash snapshot");
        }catch(Throwable x){failed++;lines.add("REGRESSION_FAIL constant-pool-independence "+x);}
        try{
            byte[] in=read(api,HyperspacePatches.BASE_TILED+".class");
            byte[] patchedBytes=new PrepatcherTransformer(cfg).transform(null,HyperspacePatches.BASE_TILED,null,null,in);
            byte[] tampered=tamperFirstHook(patchedBytes,"seededRandom");
            byte[] out=new PrepatcherTransformer(cfg).transform(null,HyperspacePatches.BASE_TILED,null,null,tampered);
            String key="starsector.prepatcher.patchStatus."
                    +HyperspacePatches.BASE_TILED.replace('/','.')+".terrainRandomReuse";
            if(out!=null||!"SKIPPED_STRUCTURAL".equals(System.getProperty(key)))
                throw new AssertionError("marker concealed an incomplete hyperspace postcondition");
            lines.add("REGRESSION_OK hyperspace marker requires the complete structural postcondition");
        }catch(Throwable x){failed++;lines.add("REGRESSION_FAIL hyperspace-postcondition "+x);}
        boolean complete=failed==0&&unchanged==0&&patched==HyperspacePatches.TARGET_CLASSES.size();
        lines.add(0,"SUMMARY targets="+HyperspacePatches.TARGET_CLASSES.size()+" patched="+patched+" unchanged="+unchanged+" verifiedMethods="+verified+" failed="+failed+" complete="+complete);Files.write(report,lines);for(String s:lines)System.out.println(s);if(!complete)System.exit(2);
    }
    static byte[] read(Path jar,String n)throws Exception{try(JarFile j=new JarFile(jar.toFile())){JarEntry e=j.getJarEntry(n);if(e==null)return null;try(InputStream in=j.getInputStream(e)){return in.readAllBytes();}}}

    static byte[] changeUnrelatedStringLiteral(byte[] bytes){
        ClassNode node=new ClassNode(Opcodes.ASM8);new ClassReader(bytes).accept(node,0);
        for(MethodNode method:node.methods)for(AbstractInsnNode insn:method.instructions.toArray()){
            if(insn instanceof LdcInsnNode ldc&&ldc.cst instanceof String text){
                ldc.cst=text+" [constant-pool fixture]";return write(node);
            }
        }
        throw new AssertionError("target has no string literal for constant-pool fixture");
    }
    static byte[] tamperFirstHook(byte[] bytes,String name){
        ClassNode node=new ClassNode(Opcodes.ASM8);new ClassReader(bytes).accept(node,0);
        for(MethodNode method:node.methods)for(AbstractInsnNode insn:method.instructions.toArray()){
            if(insn instanceof MethodInsnNode call&&call.getOpcode()==Opcodes.INVOKESTATIC
                    &&call.owner.equals(HyperspacePatches.HOOK)&&call.name.equals(name)){
                call.name=name+"Tampered";return write(node);
            }
        }
        throw new AssertionError("missing hook to tamper: "+name);
    }
    static byte[] write(ClassNode node){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();}

    static void runAutomatonRegression()throws Exception{
        String name="com/starsector/prepatcher/hyperspace/VerifierAutomaton";
        ClassWriter source=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        source.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",null);
        source.visitField(Opcodes.ACC_PROTECTED,"cells","[[I",null,null).visitEnd();
        source.visitField(Opcodes.ACC_PROTECTED,"next","[[I",null,null).visitEnd();
        source.visitField(Opcodes.ACC_PROTECTED,"getterCalls","I",null,null).visitEnd();
        MethodVisitor init=source.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);init.visitCode();init.visitVarInsn(Opcodes.ALOAD,0);init.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);init.visitInsn(Opcodes.RETURN);init.visitMaxs(0,0);init.visitEnd();
        MethodVisitor getter=source.visitMethod(Opcodes.ACC_PUBLIC,"getCells","()[[I",null,null);getter.visitCode();
        getter.visitVarInsn(Opcodes.ALOAD,0);getter.visitInsn(Opcodes.DUP);getter.visitFieldInsn(Opcodes.GETFIELD,name,"getterCalls","I");getter.visitInsn(Opcodes.ICONST_1);getter.visitInsn(Opcodes.IADD);getter.visitFieldInsn(Opcodes.PUTFIELD,name,"getterCalls","I");
        getter.visitVarInsn(Opcodes.ALOAD,0);getter.visitFieldInsn(Opcodes.GETFIELD,name,"cells","[[I");getter.visitInsn(Opcodes.ARETURN);getter.visitMaxs(0,0);getter.visitEnd();
        MethodVisitor advance=source.visitMethod(Opcodes.ACC_PUBLIC,"advance","(F)V",null,null);advance.visitCode();
        advance.visitVarInsn(Opcodes.ALOAD,0);advance.visitVarInsn(Opcodes.ALOAD,0);advance.visitFieldInsn(Opcodes.GETFIELD,name,"next","[[I");advance.visitFieldInsn(Opcodes.PUTFIELD,name,"cells","[[I");
        advance.visitVarInsn(Opcodes.ALOAD,0);advance.visitVarInsn(Opcodes.ALOAD,0);advance.visitFieldInsn(Opcodes.GETFIELD,name,"cells","[[I");advance.visitInsn(Opcodes.ARRAYLENGTH);advance.visitVarInsn(Opcodes.ALOAD,0);advance.visitFieldInsn(Opcodes.GETFIELD,name,"cells","[[I");advance.visitInsn(Opcodes.ICONST_0);advance.visitInsn(Opcodes.AALOAD);advance.visitInsn(Opcodes.ARRAYLENGTH);advance.visitMultiANewArrayInsn("[[I",2);advance.visitFieldInsn(Opcodes.PUTFIELD,name,"next","[[I");advance.visitInsn(Opcodes.RETURN);advance.visitMaxs(0,0);advance.visitEnd();source.visitEnd();

        byte[] vanilla=source.toByteArray();
        ClassNode node=new ClassNode(Opcodes.ASM8);new ClassReader(vanilla).accept(node,0);
        if(HyperspacePatches.patchAutomaton(node)!=1)throw new AssertionError("synthetic automaton patch count");
        ClassWriter output=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);node.accept(output);
        ByteLoader loader=new ByteLoader();Class<?> type=loader.define(name.replace('/','.'),output.toByteArray());Object owner=type.getConstructor().newInstance();
        java.lang.reflect.Field cells=type.getDeclaredField("cells"),next=type.getDeclaredField("next"),getterCalls=type.getDeclaredField("getterCalls");cells.setAccessible(true);next.setAccessible(true);getterCalls.setAccessible(true);
        java.lang.reflect.Field spare=type.getDeclaredField(HyperspacePatches.AUTO_SPARE),exposed=type.getDeclaredField(HyperspacePatches.AUTO_EXPOSED);
        java.lang.reflect.Field marker=type.getDeclaredField(HyperspacePatches.AUTO_MARKER);
        spare.setAccessible(true);exposed.setAccessible(true);marker.setAccessible(true);
        if(!java.lang.reflect.Modifier.isTransient(spare.getModifiers())||!spare.isSynthetic()||!java.lang.reflect.Modifier.isTransient(exposed.getModifiers())||!exposed.isSynthetic())throw new AssertionError("injected state must be transient and synthetic");
        if(!java.lang.reflect.Modifier.isStatic(marker.getModifiers())||!java.lang.reflect.Modifier.isFinal(marker.getModifiers())||!marker.isSynthetic()||!marker.getBoolean(null))throw new AssertionError("instrumentation marker contract");
        int[][] first={{7,8},{9,10}},second={{1,2},{3,4}};cells.set(owner,first);next.set(owner,second);
        Object internal=StarsectorPrepatcherHyperspaceHooks.automatonCellsInternal(owner,type.getName());
        if(internal!=first)throw new AssertionError("internal cells read changed identity");
        if(getterCalls.getInt(owner)!=0)throw new AssertionError("instrumented exact owner did not use direct cells access");
        type.getMethod("advance",float.class).invoke(owner,0f);
        if(cells.get(owner)!=second||next.get(owner)!=first)
            throw new AssertionError("internal cells read incorrectly disabled buffer reuse");
        for(int[] row:(int[][])next.get(owner))for(int value:row)if(value!=0)
            throw new AssertionError("internally-read reused buffer was not zeroed");

        int[][] third={{11,12},{13,14}},fourth={{5,6},{7,8}};cells.set(owner,third);next.set(owner,fourth);
        Object escaped=type.getMethod("getCells").invoke(owner);if(escaped!=third)throw new AssertionError("getCells identity changed");
        if(getterCalls.getInt(owner)!=1)throw new AssertionError("public getter was not invoked");
        type.getMethod("advance",float.class).invoke(owner,0f);
        if(cells.get(owner)!=fourth||next.get(owner)==third)throw new AssertionError("escaped cells buffer was reused");
        if(third[0][0]!=11||third[1][1]!=14)throw new AssertionError("escaped cells buffer was mutated");
        if(spare.get(owner)!=null)throw new AssertionError("owner retained an extra spare after rollover");
        if(StarsectorPrepatcherHyperspaceHooks.retainAutomatonSpare(first,false,new Object(),type.getName())!=null)
            throw new AssertionError("unknown/subclass owner was allowed to reuse a protected buffer");

        String subclassName=name+"Subclass";
        Class<?> subclass=loader.define(subclassName.replace('/','.'),subclassBytes(name,subclassName));
        Object child=subclass.getConstructor().newInstance();int[][] childCells={{21}};cells.set(child,childCells);
        if(StarsectorPrepatcherHyperspaceHooks.automatonCellsInternal(child,type.getName())!=childCells)
            throw new AssertionError("subclass virtual getter changed identity");
        if(getterCalls.getInt(child)!=2)throw new AssertionError("subclass override was bypassed");
        if(StarsectorPrepatcherHyperspaceHooks.automatonCellsInternal(child,subclass.getName())!=childCells || getterCalls.getInt(child)!=4)
            throw new AssertionError("inherited marker enabled direct access for a subclass");

        String hiddenSubclassName=name+"HiddenSubclass";
        Class<?> hiddenSubclass=loader.define(hiddenSubclassName.replace('/','.'),
                subclassBytes(name,hiddenSubclassName,0));
        java.lang.reflect.Constructor<?> hiddenConstructor=hiddenSubclass.getDeclaredConstructor();
        hiddenConstructor.setAccessible(true);Object hiddenChild=hiddenConstructor.newInstance();
        int[][] hiddenCells={{25}};cells.set(hiddenChild,hiddenCells);
        if(StarsectorPrepatcherHyperspaceHooks.automatonCellsInternal(hiddenChild,type.getName())!=hiddenCells)
            throw new AssertionError("non-public subclass virtual getter changed identity");
        if(getterCalls.getInt(hiddenChild)!=2)
            throw new AssertionError("non-public subclass override was bypassed");

        String throwingSubclassName=name+"ThrowingSubclass";
        Class<?> throwingSubclass=loader.define(throwingSubclassName.replace('/','.'),
                throwingSubclassBytes(name,throwingSubclassName));
        Object throwingChild=throwingSubclass.getConstructor().newInstance();
        try{StarsectorPrepatcherHyperspaceHooks.automatonCellsInternal(throwingChild,type.getName());
            throw new AssertionError("checked getter failure was swallowed");}
        catch(Throwable expected){
            if(!(expected instanceof java.io.IOException)
                    || !"checked-getCells".equals(expected.getMessage()))
                throw new AssertionError("checked getter failure changed",expected);
        }

        Class<?> partial=new ByteLoader().define(name.replace('/','.'),vanilla);Object partialOwner=partial.getConstructor().newInstance();
        java.lang.reflect.Field partialCells=partial.getDeclaredField("cells"),partialCalls=partial.getDeclaredField("getterCalls");partialCells.setAccessible(true);partialCalls.setAccessible(true);
        int[][] partialValue={{31}};partialCells.set(partialOwner,partialValue);
        if(StarsectorPrepatcherHyperspaceHooks.automatonCellsInternal(partialOwner,partial.getName())!=partialValue || partialCalls.getInt(partialOwner)!=1)
            throw new AssertionError("unpatched exact owner bypassed its virtual getter");

        try{StarsectorPrepatcherHyperspaceHooks.automatonCellsInternal(null,type.getName());
            throw new AssertionError("null automaton owner did not preserve invokevirtual NPE");}
        catch(NullPointerException expected){/* Original INVOKEVIRTUAL fails at the call site. */}
    }
    static byte[] subclassBytes(String parent,String name){
        return subclassBytes(parent,name,Opcodes.ACC_PUBLIC);
    }
    static byte[] subclassBytes(String parent,String name,int access){
        ClassWriter source=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        source.visit(Opcodes.V17,access,name,null,parent,null);
        MethodVisitor init=source.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);init.visitCode();init.visitVarInsn(Opcodes.ALOAD,0);init.visitMethodInsn(Opcodes.INVOKESPECIAL,parent,"<init>","()V",false);init.visitInsn(Opcodes.RETURN);init.visitMaxs(0,0);init.visitEnd();
        MethodVisitor getter=source.visitMethod(Opcodes.ACC_PUBLIC,"getCells","()[[I",null,null);getter.visitCode();
        getter.visitVarInsn(Opcodes.ALOAD,0);getter.visitInsn(Opcodes.DUP);getter.visitFieldInsn(Opcodes.GETFIELD,parent,"getterCalls","I");getter.visitInsn(Opcodes.ICONST_2);getter.visitInsn(Opcodes.IADD);getter.visitFieldInsn(Opcodes.PUTFIELD,parent,"getterCalls","I");
        getter.visitVarInsn(Opcodes.ALOAD,0);getter.visitFieldInsn(Opcodes.GETFIELD,parent,"cells","[[I");getter.visitInsn(Opcodes.ARETURN);getter.visitMaxs(0,0);getter.visitEnd();source.visitEnd();return source.toByteArray();
    }
    static byte[] throwingSubclassBytes(String parent,String name){
        ClassWriter source=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        source.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,name,null,parent,null);
        MethodVisitor init=source.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);init.visitCode();init.visitVarInsn(Opcodes.ALOAD,0);init.visitMethodInsn(Opcodes.INVOKESPECIAL,parent,"<init>","()V",false);init.visitInsn(Opcodes.RETURN);init.visitMaxs(0,0);init.visitEnd();
        MethodVisitor getter=source.visitMethod(Opcodes.ACC_PUBLIC,"getCells","()[[I",null,null);getter.visitCode();getter.visitTypeInsn(Opcodes.NEW,"java/io/IOException");getter.visitInsn(Opcodes.DUP);getter.visitLdcInsn("checked-getCells");getter.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/io/IOException","<init>","(Ljava/lang/String;)V",false);getter.visitInsn(Opcodes.ATHROW);getter.visitMaxs(0,0);getter.visitEnd();source.visitEnd();return source.toByteArray();
    }
    static void runTelemetryRegression(PrepatcherConfig cfg)throws Exception{
        long before=StarsectorPrepatcherHyperspaceHooks.pooledRandomApprox();
        for(int i=0;i<37;i++)StarsectorPrepatcherHyperspaceHooks.seededRandom(0x5eedL+i);
        long after=StarsectorPrepatcherHyperspaceHooks.pooledRandomApprox();
        if(after-before!=37L)throw new AssertionError("unflushed random tail missing: "+before+" -> "+after);
        String first=StarsectorPrepatcherHyperspaceHooks.statsSnapshot(),second=StarsectorPrepatcherHyperspaceHooks.statsSnapshot();
        if(!first.equals(second))throw new AssertionError("cumulative stats changed without new events: "+first+" / "+second);
        if(!first.contains("pooledRandomApprox="+after))throw new AssertionError("stats snapshot omitted random tail: "+first);

        java.lang.reflect.Field workerField=StarsectorPrepatcherHyperspaceHooks.class.getDeclaredField("statsThread");workerField.setAccessible(true);
        @SuppressWarnings("unchecked") java.lang.ref.WeakReference<Thread> firstRef=(java.lang.ref.WeakReference<Thread>)workerField.get(null);
        Thread firstWorker=firstRef.get();if(firstWorker==null||!firstWorker.isAlive())throw new AssertionError("stats worker did not start");
        firstWorker.interrupt();firstWorker.join(1000L);
        if(firstWorker.isAlive())throw new AssertionError("stats worker ignored interruption");
        StarsectorPrepatcherRuntimeBridge.configure(cfg,Path.of("."));
        @SuppressWarnings("unchecked") java.lang.ref.WeakReference<Thread> secondRef=(java.lang.ref.WeakReference<Thread>)workerField.get(null);
        Thread secondWorker=secondRef.get();
        if(secondWorker==null||secondWorker==firstWorker||!secondWorker.isAlive())throw new AssertionError("stats worker did not restart");
    }
    static final class ByteLoader extends ClassLoader{
        ByteLoader(){super(HyperspaceCompatibilityTest.class.getClassLoader());}
        Class<?> define(String name,byte[] bytes){return defineClass(name,bytes,0,bytes.length);}
    }
}
