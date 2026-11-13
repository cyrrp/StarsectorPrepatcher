package com.starsector.prepatcher.runtime;

import java.lang.reflect.*;
import java.lang.invoke.MethodHandle;
import java.util.*;
import java.util.jar.*;

/** Resolves the real injected payload in the native game loader, without starting a GUI. */
public final class NativeRuntimeLinkageSmokeTest {
    public static void main(String[] args) throws Exception {
        if (System.getProperty("starsector.prepatcher.gameNamespace") == null) throw new AssertionError("namespace not detected");
        ClassLoader loader=ClassLoader.getSystemClassLoader();
        int count=0;
        try(JarFile jar=new JarFile(args[0])) {
            for(JarEntry entry:Collections.list(jar.entries())) {
                String n=entry.getName();
                if(!n.startsWith("com/fs/starfarer/api/StarsectorPrepatcher") || !n.endsWith(".class")) continue;
                Class<?> type=Class.forName(n.substring(0,n.length()-6).replace('/','.'),false,loader);
                type.getDeclaredMethods(); type.getDeclaredFields(); type.getDeclaredConstructors(); count++;
            }
        }
        Class<?> hooks=Class.forName("com.fs.starfarer.api.StarsectorPrepatcherPresentationHooks",false,loader);
        Method sensor=Arrays.stream(hooks.getDeclaredMethods()).filter(m -> m.getName().equals("advanceSensorRangeIndicator")).findFirst().orElseThrow();
        Class<?> nativeSensor=sensor.getParameterTypes()[0];
        Class<?> fleet=Class.forName("com.fs.starfarer.campaign.fleet.CampaignFleet",false,loader);
        if(nativeSensor!=fleet.getDeclaredMethod("getSensorRangeIndicator").getReturnType())
            throw new AssertionError("sensor payload descriptor differs from the actual native fleet getter");
        // Namespace tables are sparse: an absent member row preserves its canonical spelling.
        // The real fleet getter above independently establishes the required native class identity.
        String sensorAdvance="Ò00000";
        String namespace=System.getProperty("starsector.prepatcher.gameNamespace");
        if(!namespace.equals("windows-0.98a-RC8")) try(JarFile jar=new JarFile(args[0])) {
            JarEntry table=jar.getJarEntry("com/starsector/prepatcher/agent/"+namespace+".tsv");
            if(table==null) throw new AssertionError("missing native namespace table: "+namespace);
            List<String> rows=new String(jar.getInputStream(table).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).lines().toList();
            String classPrefix="C\tcom/fs/starfarer/campaign/E\t";
            String expected=rows.stream().filter(r -> r.startsWith(classPrefix)).findFirst().orElseThrow().substring(classPrefix.length());
            if(!nativeSensor.getName().equals(expected.replace('/','.')))
                throw new AssertionError("native fleet sensor identity differs from its proven correspondence");
            String memberPrefix="M\tcom/fs/starfarer/campaign/E\t"+sensorAdvance+"\t(F)V\t";
            List<String> members=rows.stream().filter(r -> r.startsWith(memberPrefix)).toList();
            if(members.size()>1) throw new AssertionError("ambiguous sensor advance correspondence");
            if(!members.isEmpty()) sensorAdvance=members.get(0).substring(memberPrefix.length());
        }
        nativeSensor.getDeclaredMethod(sensorAdvance,float.class);
        Class<?> bridge=Class.forName("com.fs.starfarer.api.StarsectorPrepatcherPresentationHooks$SelectionIndicatorBridge",true,loader);
        Field advance=bridge.getDeclaredField("ADVANCE"); advance.setAccessible(true);
        if(!(advance.get(null) instanceof MethodHandle)) throw new AssertionError("selection indicator MethodHandle unresolved");
        if(count!=138) throw new AssertionError("payload inventory changed: "+count);
        int nativeTargets=0;
        for(String target:java.nio.file.Files.readAllLines(java.nio.file.Path.of(args[1]))) {
            Class<?> type=Class.forName(target,false,loader);
            type.getDeclaredMethods();type.getDeclaredFields();type.getDeclaredConstructors();
            nativeTargets++;
        }
        if(nativeTargets!=73) throw new AssertionError("native target inventory changed: "+nativeTargets);
        System.out.println("OK native runtime linkage payload="+count+" nativeTargets="+nativeTargets+" sensor-native-descriptor/member selection-reflection-bridge");
    }
}
