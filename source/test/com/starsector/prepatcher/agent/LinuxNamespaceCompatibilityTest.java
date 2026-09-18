package com.starsector.prepatcher.agent;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.lang.instrument.ClassFileTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.util.*;
import java.io.*;
import com.starsector.prepatcher.agent.presentation.FastForwardPresentationTransformer;
import com.starsector.prepatcher.agent.uieconomy.ReadOnlyUiEconomyStepTransformer;
import com.starsector.prepatcher.agent.uieconomy.IndustryMarketMutationTransformer;
import com.starsector.prepatcher.agent.uieconomy.VanillaMarketOpenLocalizationContractTransformer;

/** Native Linux bytes, the production ordered pipeline, and native output: no renamed game installation. */
public final class LinuxNamespaceCompatibilityTest {
    private static void require(boolean ok,String text) { if(!ok) throw new AssertionError(text); }
    private static String text(byte[] bytes) {
        StringWriter out=new StringWriter();
        new ClassReader(bytes).accept(new TraceClassVisitor(new PrintWriter(out)),0);
        return out.toString();
    }
    public static void main(String[] args) throws Exception {
        GameNamespace ns=GameNamespace.current();
        require("linux-0.98a-RC8".equals(System.getProperty("starsector.prepatcher.gameNamespace")),"not testing native Linux namespace");
        PrepatcherConfig config=PrepatcherConfig.load(Path.of(args[0]));
        ClassLoader loader=ClassLoader.getSystemClassLoader();
        Set<String> targets=new TreeSet<>(PrepatcherTransformer.TARGET_CLASSES);
        targets.addAll(FastForwardPresentationTransformer.TARGET_CLASSES);
        targets.addAll(ReadOnlyUiEconomyStepTransformer.TARGET_CLASSES);
        targets.addAll(IndustryMarketMutationTransformer.TARGET_CLASSES);
        targets.addAll(VanillaMarketOpenLocalizationContractTransformer.TARGET_CLASSES);
        Map<String,byte[]> input=new TreeMap<>();
        for(int i=1;i<args.length;i++) try(JarFile jar=new JarFile(args[i])) {
            for(JarEntry e:Collections.list(jar.entries())) if(e.getName().endsWith(".class")) {
                String n=e.getName().substring(0,e.getName().length()-6);
                if(targets.contains(ns.canonicalName(n))) input.put(n,jar.getInputStream(e).readAllBytes());
            }
        }
        Set<String> found=new HashSet<>(); input.keySet().forEach(n -> found.add(ns.canonicalName(n)));
        Files.write(Path.of(args[0]).toAbsolutePath().getParent().resolve("linux-native-targets.txt"),
                input.keySet().stream().map(n -> n.replace('/','.')).toList());
        Set<String> missing=new TreeSet<>(PrepatcherTransformer.TARGET_CLASSES); missing.removeAll(found);
        require(missing.isEmpty(),"unmapped/missing optimizer targets "+missing);
        int patched=0;
        for(var e:input.entrySet()) {
            String name=e.getKey(); byte[] original=e.getValue();
            require(text(original).equals(text(ns.toNative(ns.toCanonical(original)))),"namespace roundtrip differs: "+name);
            OrderedTransformerPipeline pipeline=PrepatcherAgent.createOrderedPipeline(config,loader,Path.of(args[0]).toAbsolutePath().getParent(),false,true,true,true,true,false,true);
            byte[] result=pipeline.transform(loader,name,null,null,original);
            if (result != null) OptionalModLinkageAudit.verify(original, result);
            require(System.getProperty("starsector.prepatcher.pipelineFailure."+name.replace('/','.'))==null,"production pipeline rejected "+name);
            if(result==null) continue;
            require(new ClassReader(result).getClassName().equals(name),"native class identity changed "+name);
            ClassNode node=new ClassNode(); new ClassReader(result).accept(node,0);
            for(MethodNode m:node.methods) if((m.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0)
                new Analyzer<>(new BasicVerifier()).analyze(node.name,m);
            byte[] twice=pipeline.transform(loader,name,null,null,result);
            require(System.getProperty("starsector.prepatcher.pipelineFailure."+name.replace('/','.'))==null,"second pass rejected "+name);
            require(twice==null || text(result).equals(text(twice)),"non-idempotent native pipeline "+name);
            patched++;
        }
        byte[] sample=input.get(ns.nativeName("com/fs/starfarer/campaign/CampaignEngine"));
        ClassNode repairFixture=new ClassNode(); new ClassReader(sample).accept(repairFixture,0);
        repairFixture.fields.add(new FieldNode(Opcodes.ACC_PUBLIC,"while.new","I",null,null));
        ClassWriter repairWriter=new ClassWriter(0); repairFixture.accept(repairWriter);
        byte[] repairInput=repairWriter.toByteArray();
        OrderedTransformerPipeline repairOnly=new OrderedTransformerPipeline(List.of(
                new OrderedTransformerPipeline.Stage("no optional patches",new ClassFileTransformer() {})),loader,false,true);
        byte[] repaired=repairOnly.transform(loader,repairFixture.name,null,null,repairInput);
        require(Arrays.equals(repaired,IllegalObfuscatedMemberNameRepair.repair(repairFixture.name,repairInput)),
                "mandatory repair without optional patches must preserve native bytecode exactly");
        ClassFileTransformer mutate=(new ClassFileTransformer() {
            public byte[] transform(ClassLoader l,String n,Class<?> c,java.security.ProtectionDomain d,byte[] b) {
                ClassNode node=new ClassNode();new ClassReader(b).accept(node,0);
                node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC,"spp$testRollback","I",null,null));
                ClassWriter writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();
            }
        });
        ClassFileTransformer reject=(new ClassFileTransformer() {
            public byte[] transform(ClassLoader l,String n,Class<?> c,java.security.ProtectionDomain d,byte[] b) {throw new IllegalStateException("intentional namespace rollback test");}
        });
        OrderedTransformerPipeline failing=new OrderedTransformerPipeline(List.of(new OrderedTransformerPipeline.Stage("mutation",mutate),new OrderedTransformerPipeline.Stage("rejection",reject)),loader,false,false);
        require(failing.transform(loader,new ClassReader(sample).getClassName(),null,null,sample)==null,"rollback must preserve original native bytes");
        System.out.println("OK Linux namespace targets="+input.size()+" patched="+patched+" roundtrip/native-identity/full-order/ASM/idempotency/atomic-rollback");
    }
}
