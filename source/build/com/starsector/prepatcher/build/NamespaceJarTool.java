package com.starsector.prepatcher.build;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

/** Offline test/build namespace view. Outputs are private intermediates, never game replacements. */
public final class NamespaceJarTool {
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--detect")) {
            try (JarFile jar = new JarFile(args[1])) {
                org.objectweb.asm.tree.ClassNode node=new org.objectweb.asm.tree.ClassNode();
                new ClassReader(jar.getInputStream(jar.getJarEntry("com/fs/starfarer/campaign/CampaignEngine.class"))).accept(node,ClassReader.SKIP_CODE);
                String canonical="(FLcom/fs/starfarer/util/A/new;)V";
                List<String> matches=new ArrayList<>();
                if (node.methods.stream().anyMatch(m -> m.name.equals("advance") && m.desc.equals(canonical))) matches.add("windows-0.98a-RC8");
                try (var tables=Files.list(Path.of(args[2]))) {
                    for (Path table : tables.filter(p -> p.getFileName().toString().endsWith(".tsv")).sorted().toList()) {
                        Map<String,String> names=new HashMap<>();
                        for (String line : Files.readAllLines(table)) {
                            String[] row=line.split("\t");
                            if (row[0].equals("C")) names.put(row[1],row[2]);
                        }
                        Remapper remapper=new Remapper(Opcodes.ASM9) { public String map(String n) { return names.getOrDefault(n,n); } };
                        String descriptor=remapper.mapMethodDesc(canonical);
                        if (node.methods.stream().anyMatch(m -> m.name.equals("advance") && m.desc.equals(descriptor))) matches.add(table.getFileName().toString().replace(".tsv",""));
                    }
                }
                if (matches.size()!=1) throw new IllegalArgumentException("Unknown or ambiguous game namespace: "+matches);
                System.out.println(matches.get(0));
                return;
            }
        }
        if (args.length != 3) throw new IllegalArgumentException("mapping.tsv linux-input.jar canonical-output.jar");
        Map<String,String> classes = new HashMap<>(), forward = new HashMap<>(), members = new HashMap<>();
        List<String[]> rows = Files.readAllLines(Path.of(args[0])).stream().filter(s -> !s.startsWith("#")).map(s -> s.split("\\t")).toList();
        for (String[] p : rows) if (p[0].equals("C")) { classes.put(p[2],p[1]); forward.put(p[1],p[2]); }
        Remapper f = new Remapper(Opcodes.ASM9) { public String map(String name) { return forward.getOrDefault(name,name); } };
        for (String[] p : rows) if (p[0].equals("M") || p[0].equals("F")) {
            String desc = p[0].equals("M") ? f.mapMethodDesc(p[3]) : f.mapDesc(p[3]);
            String key = p[0]+"\t"+f.map(p[1])+"\t"+p[4]+"\t"+desc;
            String prior = members.putIfAbsent(key,p[2]);
            if (prior != null && !prior.equals(p[2])) throw new IllegalArgumentException("Non-bijective member " + key);
        }
        try (JarFile input = new JarFile(args[1]); JarOutputStream output = new JarOutputStream(Files.newOutputStream(Path.of(args[2])))) {
            // The table explicitly isolates unresolved native classes. Public API and
            // third-party classes outside that inventory retain their stable names.
            Remapper r = new Remapper(Opcodes.ASM9) {
                public String map(String n) { return classes.getOrDefault(n,n); }
                public String mapMethodName(String o,String n,String d) { return members.getOrDefault("M\t"+o+"\t"+n+"\t"+d,n); }
                public String mapFieldName(String o,String n,String d) { return members.getOrDefault("F\t"+o+"\t"+n+"\t"+d,n); }
            };
            Set<String> names = new HashSet<>();
            for (JarEntry e : Collections.list(input.entries())) {
                if (e.isDirectory() || e.getName().startsWith("META-INF/")) continue;
                byte[] bytes=input.getInputStream(e).readAllBytes(); String name=e.getName();
                if (name.endsWith(".class")) {
                    ClassReader cr=new ClassReader(bytes); ClassWriter cw=new ClassWriter(0);
                    cr.accept(new ClassRemapper(cw,r),0); bytes=cw.toByteArray(); name=new ClassReader(bytes).getClassName()+".class";
                }
                if (!names.add(name)) throw new IllegalStateException("Duplicate output " + name);
                JarEntry target=new JarEntry(name); target.setTime(0); output.putNextEntry(target); output.write(bytes); output.closeEntry();
            }
        }
    }
}
