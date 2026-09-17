package com.starsector.prepatcher.runtime;

import java.lang.reflect.*;
import java.lang.invoke.MethodHandle;
import java.util.*;
import java.util.jar.*;

/** Resolves the real injected payload in the native Linux game loader, without starting a GUI. */
public final class LinuxRuntimeLinkageSmokeTest {
    public static void main(String[] args) throws Exception {
        if (!"linux-0.98a-RC8".equals(System.getProperty("starsector.prepatcher.gameNamespace"))) throw new AssertionError("not Linux");
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
        if(!nativeSensor.getName().equals("com.fs.starfarer.campaign.o0OO")) throw new AssertionError("Windows sensor descriptor leaked into Linux payload");
        nativeSensor.getDeclaredMethod("new",float.class);
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
        System.out.println("OK Linux runtime linkage payload="+count+" nativeTargets="+nativeTargets+" sensor-native-descriptor/member selection-reflection-bridge");
    }
}
