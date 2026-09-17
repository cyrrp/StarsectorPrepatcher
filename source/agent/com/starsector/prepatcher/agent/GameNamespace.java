package com.starsector.prepatcher.agent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

/** Reversible namespace transport around the ENTIRE atomic patch pipeline. */
final class GameNamespace {
    private static final GameNamespace IDENTITY = new GameNamespace();
    private static volatile GameNamespace active;
    private final Map<String,String> forward = new HashMap<>(), reverse = new HashMap<>();
    private final Map<String,String> nativeMembers = new HashMap<>(), canonicalMembers = new HashMap<>();
    private final boolean linux;
    private GameNamespace() { linux=false; }
    private GameNamespace(InputStream input) throws IOException {
        linux=true;
        List<String[]> rows;
        try (BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
            rows=reader.lines().filter(s -> !s.isBlank() && !s.startsWith("#")).map(s -> s.split("\\t",-1)).toList();
        }
        for (String[] p:rows) if (p[0].equals("C")) {
            putUnique(forward,p[1],p[2]); putUnique(reverse,p[2],p[1]);
        }
        Remapper f=remapper(false,false,null);
        for (String[] p:rows) if (p[0].equals("M") || p[0].equals("F")) {
            String nativeDesc=p[0].equals("M") ? f.mapMethodDesc(p[3]) : f.mapDesc(p[3]);
            addMember(nativeMembers,p[0],p[1],p[2],p[3],p[4]);
            addMember(canonicalMembers,p[0],f.map(p[1]),p[4],nativeDesc,p[2]);
        }
    }
    private static void putUnique(Map<String,String> map,String key,String value) {
        String old=map.putIfAbsent(key,value);
        if (old!=null && !old.equals(value)) throw new IllegalArgumentException("Non-bijective Linux mapping: "+key);
    }
    private static void addMember(Map<String,String> map,String kind,String owner,String name,String desc,String target) {
        putUnique(map,kind+'\t'+owner+'\t'+name+'\t'+desc,target);
        // FR/Java27 repair illegal dotted names before this pipeline.
        if (name.contains(".")) putUnique(map,kind+'\t'+owner+'\t'+name.replace('.','_')+'\t'+desc,target.replace('.','_'));
    }
    static GameNamespace current() {
        GameNamespace result=active;
        if (result!=null) return result;
        synchronized (GameNamespace.class) {
            if (active!=null) return active;
            try (InputStream in=ClassLoader.getSystemResourceAsStream("com/fs/starfarer/campaign/CampaignEngine.class")) {
                if (in==null) return IDENTITY; // -version / no game classpath; do not cache absence.
                ClassNode node=new ClassNode(); new ClassReader(in).accept(node,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
                boolean isLinux=node.methods.stream().anyMatch(m -> m.name.equals("advance") && m.desc.equals("(FLcom/fs/starfarer/util/super/B;)V"));
                boolean isWindows=node.methods.stream().anyMatch(m -> m.name.equals("advance") && m.desc.equals("(FLcom/fs/starfarer/util/A/new;)V"));
                if (!isLinux && !isWindows) throw new IllegalStateException("Unknown Starsector bytecode namespace; refusing guessed names");
                if (isLinux) {
                    InputStream table=GameNamespace.class.getResourceAsStream("/com/starsector/prepatcher/agent/linux-0.98a-RC8.tsv");
                    if (table==null) throw new IllegalStateException("Linux name table is missing from the agent");
                    active=new GameNamespace(table);
                } else active=IDENTITY;
                System.setProperty("starsector.prepatcher.gameNamespace",isLinux?"linux-0.98a-RC8":"windows-0.98a-RC8");
                return active;
            } catch (IOException e) { throw new IllegalStateException("Cannot read game namespace",e); }
        }
    }
    String canonicalName(String nativeName) { return reverse.getOrDefault(nativeName,nativeName); }
    String nativeName(String canonicalName) { return forward.getOrDefault(canonicalName,canonicalName); }
    byte[] toCanonical(byte[] bytes) { return linux ? remap(bytes,true,false) : bytes; }
    byte[] toNative(byte[] bytes) { return linux ? remap(bytes,false,false) : bytes; }
    byte[] toNativeRuntime(byte[] bytes) { return repairNative(toNative(bytes)); }
    byte[] runtimePayload(byte[] bytes) { return repairNative(linux ? remap(bytes,false,true) : bytes); }
    private byte[] repairNative(byte[] bytes) {
        // A canonical legal name may map to a dotted native name. FR already ran
        // before us, so do not reintroduce illegal names after its repair pass.
        return linux && Runtime.version().feature() >= 27
                ? IllegalObfuscatedMemberNameRepair.repair(new ClassReader(bytes).getClassName(),bytes)
                : bytes;
    }
    private byte[] remap(byte[] bytes,boolean canonical,boolean strings) {
        ClassReader reader=new ClassReader(bytes); ClassWriter writer=new ClassWriter(0);
        reader.accept(new ClassRemapper(writer,remapper(canonical,strings,reader.getClassName())),0);
        return writer.toByteArray();
    }
    private Remapper remapper(boolean canonical,boolean strings,String payloadName) {
        Map<String,String> names=canonical?reverse:forward, members=canonical?canonicalMembers:nativeMembers;
        return new Remapper(Opcodes.ASM9) {
            @Override public String map(String n) { return names.getOrDefault(n,n); }
            @Override public String mapMethodName(String o,String n,String d) { return members.getOrDefault("M\t"+o+'\t'+n+'\t'+d,n); }
            @Override public String mapFieldName(String o,String n,String d) { return members.getOrDefault("F\t"+o+'\t'+n+'\t'+d,n); }
            @Override public Object mapValue(Object value) {
                if (strings && value instanceof String text) {
                    if (text.equals("new") && "com/fs/starfarer/api/StarsectorPrepatcherPresentationHooks$SelectionIndicatorBridge".equals(payloadName))
                        return mapMethodName("com/fs/starfarer/campaign/ui/oOO0","new","(F)V");
                    String internal=text.replace('.','/');
                    if (names.containsKey(internal)) return text.contains("/") ? map(internal) : map(internal).replace('/','.');
                }
                return super.mapValue(value);
            }
        };
    }
}
