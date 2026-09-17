package com.starsector.prepatcher.build;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Offline evidence generator. Never changes game JARs; ambiguous matches are rejected. */
public final class LinuxMappingGenerator {
    private final Map<String, ClassNode> windows = new TreeMap<>(), linux = new TreeMap<>();
    private final Map<String, String> classes = new TreeMap<>(), reverse = new HashMap<>();
    private final Map<String, String> members = new TreeMap<>();
    private final Set<String> conflicts = new TreeSet<>();
    private final Map<String,String> coarseShapes = new HashMap<>();
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("windows-core linux-core seed-tsv output-tsv");
        LinuxMappingGenerator g = new LinuxMappingGenerator();
        g.read(Path.of(args[0]), g.windows); g.read(Path.of(args[1]), g.linux);
        for (String line : args[2].equals("-") ? List.<String>of() : Files.readAllLines(Path.of(args[2]))) {
            String[] p = line.split("\\t");
            if (p.length == 2 && g.windows.containsKey(p[0]) && g.linux.containsKey(p[1])) g.pair(p[0], p[1]);
        }
        // Human-readable names are not an obfuscator-generated correspondence.
        for (String name : g.windows.keySet()) {
            String simple = name.substring(name.lastIndexOf('/') + 1);
            if (Arrays.stream(simple.split("\\$")).allMatch(s -> (s.length() > 4 && s.matches("[A-Z][a-z][A-Za-z0-9]+") && !s.matches("[oO0]+") && !Set.of("String", "Object").contains(s)) || s.matches("[0-9]+")) && g.linux.containsKey(name)) g.pair(name, name);
        }
        for (int round = 0; round < 8; round++) {
            int before = g.classes.size();
            g.matchUniqueClasses(round > 0);
            g.propagateClasses();
            if (before == g.classes.size() && round > 0) break;
        }
        for (int round = 0; round < 8; round++) {
            int before = g.members.size();
            g.matchMembers();
            if (before == g.members.size()) break;
        }
        List<String> out = new ArrayList<>();
        out.add("# Starsector 0.98a-RC8 Windows -> Linux; generated from paired local JARs.");
        for (var e : g.classes.entrySet()) out.add("C\t" + e.getKey() + "\t" + e.getValue());
        for (var e : g.members.entrySet()) out.add(e.getKey() + "\t" + e.getValue());
        for (String nativeName : g.linux.keySet()) if (!g.reverse.containsKey(nativeName))
            out.add("C\tspp/unmapped/linux/" + nativeName + "\t" + nativeName);
        System.out.println("classes=" + g.classes.size() + "/" + g.windows.size() + " members=" + g.members.size());
        for (String name : g.windows.keySet()) if (!g.classes.containsKey(name)) System.out.println("UNMATCHED " + name);
        for (String conflict : g.conflicts) System.out.println("CONFLICT " + conflict);
        if (!g.conflicts.isEmpty()) throw new IllegalStateException("Mapping conflicts: " + g.conflicts.size());
        Files.write(Path.of(args[3]), out, StandardCharsets.UTF_8);
    }
    private void read(Path root, Map<String, ClassNode> into) throws Exception {
        for (String name : List.of("starfarer_obf.jar", "fs.common_obf.jar", "fs.sound_obf.jar")) {
            try (JarFile jar = new JarFile(root.resolve(name).toFile())) {
                for (JarEntry e : Collections.list(jar.entries())) if (e.getName().endsWith(".class")) {
                    ClassNode n = new ClassNode();
                    new ClassReader(jar.getInputStream(e)).accept(n, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    into.put(n.name, n);
                }
            }
        }
    }
    private void pair(String w, String l) {
        String old = classes.get(w), inv = reverse.get(l);
        if ((old != null && !old.equals(l)) || (inv != null && !inv.equals(w))) {
            conflicts.add("class " + w + " -> " + l + " existing=" + old + "/" + inv); return;
        }
        classes.put(w, l); reverse.put(l, w);
    }
    private String type(String name, boolean fromLinux, boolean refined) {
        if (name == null) return "null";
        Map<String, ClassNode> own = fromLinux ? linux : windows;
        if (!own.containsKey(name)) return name;
        if (!refined) return "@";
        String w = fromLinux ? reverse.get(name) : (classes.containsKey(name) ? name : null);
        return w == null ? "@" : w;
    }
    private String desc(String d, boolean l, boolean refined) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i); b.append(c);
            if (c == 'L') { int end = d.indexOf(';', i); b.append(type(d.substring(i + 1, end), l, refined)); i = end - 1; }
        }
        return b.toString();
    }
    private String constant(Object value, boolean l, boolean refined) {
        if (value instanceof Type t) return "type:" + desc(t.getDescriptor(), l, refined);
        if (value instanceof Handle h) return "handle:" + h.getTag() + ":" + type(h.getOwner(), l, refined) + ":" + desc(h.getDesc(), l, refined);
        return String.valueOf(value);
    }
    private String methodShape(MethodNode m, boolean l, boolean refined) {
        StringBuilder b = new StringBuilder(desc(m.desc, l, refined)).append(':').append(m.access);
        IdentityHashMap<LabelNode, Integer> positions = new IdentityHashMap<>();
        int position = 0;
        for (AbstractInsnNode n : m.instructions) { if (n instanceof LabelNode label) positions.put(label, position); if (n.getOpcode() >= 0) position++; }
        for (AbstractInsnNode n : m.instructions) {
            if (n.getOpcode() < 0) continue;
            b.append('|').append(n.getOpcode());
            if (n instanceof MethodInsnNode x) b.append(':').append(type(x.owner, l, refined)).append(':').append(desc(x.desc, l, refined));
            else if (n instanceof FieldInsnNode x) b.append(':').append(type(x.owner, l, refined)).append(':').append(desc(x.desc, l, refined));
            else if (n instanceof TypeInsnNode x) b.append(':').append(type(x.desc, l, refined));
            else if (n instanceof LdcInsnNode x) b.append(':').append(constant(x.cst, l, refined));
            else if (n instanceof IntInsnNode x) b.append(':').append(x.operand);
            else if (n instanceof VarInsnNode x) b.append(':').append(x.var);
            else if (n instanceof IincInsnNode x) b.append(':').append(x.var).append(':').append(x.incr);
            else if (n instanceof JumpInsnNode x) b.append(':').append(positions.get(x.label));
            else if (n instanceof TableSwitchInsnNode x) b.append(':').append(x.min).append(':').append(x.max).append(':').append(positions.get(x.dflt)).append(':').append(x.labels.stream().map(positions::get).toList());
            else if (n instanceof LookupSwitchInsnNode x) b.append(':').append(x.keys).append(':').append(positions.get(x.dflt)).append(':').append(x.labels.stream().map(positions::get).toList());
            else if (n instanceof MultiANewArrayInsnNode x) b.append(':').append(desc(x.desc,l,refined)).append(':').append(x.dims);
            else if (n instanceof InvokeDynamicInsnNode x) b.append(':').append(desc(x.desc,l,refined)).append(':').append(constant(x.bsm,l,refined)).append(':').append(Arrays.stream(x.bsmArgs).map(v -> constant(v,l,refined)).toList());
        }
        for (TryCatchBlockNode t : m.tryCatchBlocks) b.append("!try:").append(positions.get(t.start)).append(':').append(positions.get(t.end)).append(':').append(positions.get(t.handler)).append(':').append(type(t.type,l,refined));
        return b.toString();
    }
    private String shape(ClassNode c, boolean l, boolean refined) {
        String cacheKey=l+":"+c.name;
        if (!refined && coarseShapes.containsKey(cacheKey)) return coarseShapes.get(cacheKey);
        List<String> fields = c.fields.stream().map(f -> f.access + ":" + desc(f.desc,l,refined) + ":" + constant(f.value,l,refined)).sorted().toList();
        List<String> methods = c.methods.stream().map(m -> methodShape(m,l,refined)).sorted().toList();
        String text = c.access + ":" + type(c.superName,l,refined) + ":" + c.interfaces.stream().map(n -> type(n,l,refined)).sorted().toList() + ":" + fields + ":" + methods;
        try {
            String result=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            if (!refined) coarseShapes.put(cacheKey,result);
            return result;
        }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void matchUniqueClasses(boolean refined) {
        Map<String,List<String>> w = new HashMap<>(), l = new HashMap<>();
        windows.forEach((n,c) -> { if (!classes.containsKey(n)) w.computeIfAbsent(shape(c,false,refined), k -> new ArrayList<>()).add(n); });
        linux.forEach((n,c) -> { if (!reverse.containsKey(n)) l.computeIfAbsent(shape(c,true,refined), k -> new ArrayList<>()).add(n); });
        w.forEach((key,names) -> { if (names.size() == 1 && l.containsKey(key) && l.get(key).size() == 1) pair(names.get(0),l.get(key).get(0)); });
    }
    private void pairType(String w, String l) {
        if (w == null || l == null) return;
        if (windows.containsKey(w) && linux.containsKey(l)) pair(w,l);
    }
    private void pairOwner(String w, String l) {
        if (windows.containsKey(w) && linux.containsKey(l)
                && shape(windows.get(w),false,false).equals(shape(linux.get(l),true,false))) pair(w,l);
    }
    private void pairDesc(String w, String l) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("L([^;]+);");
        var a = p.matcher(w); var b = p.matcher(l);
        while (a.find() && b.find()) pairType(a.group(1),b.group(1));
    }
    private void propagateClasses() {
        for (var entry : new TreeMap<>(classes).entrySet()) {
            ClassNode w = windows.get(entry.getKey()), l = linux.get(entry.getValue());
            pairType(w.superName,l.superName);
            for (MethodNode wm : w.methods) {
                String signature = methodShape(wm,false,false);
                boolean readable = wm.name.matches("[a-z][a-zA-Z0-9]{7,}") || wm.name.startsWith("<");
                List<MethodNode> matches = l.methods.stream().filter(lm -> (!readable || lm.name.equals(wm.name)) && signature.equals(methodShape(lm,true,false))).toList();
                if (matches.size() != 1) continue;
                // Require uniqueness in BOTH directions, including empty/bridge methods.
                if (w.methods.stream().filter(m -> signature.equals(methodShape(m,false,false))).count() != 1) continue;
                MethodNode lm = matches.get(0);
                pairDesc(wm.desc,lm.desc);
                List<AbstractInsnNode> wi = Arrays.stream(wm.instructions.toArray()).filter(n -> n.getOpcode() >= 0).toList();
                List<AbstractInsnNode> li = Arrays.stream(lm.instructions.toArray()).filter(n -> n.getOpcode() >= 0).toList();
                for (int i=0;i<wi.size();i++) {
                    if (wi.get(i) instanceof MethodInsnNode a && li.get(i) instanceof MethodInsnNode b) { pairOwner(a.owner,b.owner); pairDesc(a.desc,b.desc); }
                    if (wi.get(i) instanceof FieldInsnNode a && li.get(i) instanceof FieldInsnNode b) { pairOwner(a.owner,b.owner); pairDesc(a.desc,b.desc); }
                    if (wi.get(i) instanceof TypeInsnNode a && li.get(i) instanceof TypeInsnNode b) pairType(a.desc,b.desc);
                }
            }
        }
    }
    private void member(String kind, String owner, String name, String descriptor, String target) {
        if (!classes.containsKey(owner)) return;
        String key = kind + "\t" + owner + "\t" + name + "\t" + descriptor;
        String old = members.putIfAbsent(key,target);
        if (old != null && !old.equals(target)) conflicts.add(key + " -> " + target + " existing=" + old);
    }
    private void matchMembers() {
        for (var pair : classes.entrySet()) {
            ClassNode w = windows.get(pair.getKey()), l = linux.get(pair.getValue());
            for (MethodNode wm : w.methods) {
                String key = "M\t" + w.name + "\t" + wm.name + "\t" + wm.desc;
                String known = members.get(key);
                List<MethodNode> matches = l.methods.stream().filter(lm -> desc(lm.desc,true,true).equals(wm.desc) && (known == null || lm.name.equals(known))).toList();
                List<MethodNode> sameName = matches.stream().filter(lm -> lm.name.equals(wm.name) && (wm.name.length() > 7 || wm.name.startsWith("<"))).toList();
                if (sameName.size() == 1) matches = sameName;
                else {
                    String sig = methodShape(wm,false,true);
                    if (known == null && w.methods.stream().filter(m -> sig.equals(methodShape(m,false,true))).count() != 1) continue;
                    matches = matches.stream().filter(lm -> sig.equals(methodShape(lm,true,true))).toList();
                }
                if (matches.size() != 1) continue;
                MethodNode lm = matches.get(0);
                member("M",w.name,wm.name,wm.desc,lm.name);
                if (!methodShape(wm,false,true).equals(methodShape(lm,true,true))) continue;
                List<AbstractInsnNode> wi = Arrays.stream(wm.instructions.toArray()).filter(n -> n.getOpcode() >= 0).toList();
                List<AbstractInsnNode> li = Arrays.stream(lm.instructions.toArray()).filter(n -> n.getOpcode() >= 0).toList();
                for (int i=0;i<wi.size();i++) {
                    if (wi.get(i) instanceof MethodInsnNode a && li.get(i) instanceof MethodInsnNode b && Objects.equals(classes.get(a.owner),b.owner)) member("M",a.owner,a.name,a.desc,b.name);
                    if (wi.get(i) instanceof FieldInsnNode a && li.get(i) instanceof FieldInsnNode b && Objects.equals(classes.get(a.owner),b.owner)) member("F",a.owner,a.name,a.desc,b.name);
                }
            }
            for (FieldNode wf : w.fields) {
                List<FieldNode> matches = l.fields.stream().filter(lf -> desc(lf.desc,true,true).equals(wf.desc) && lf.access == wf.access && Objects.equals(lf.value,wf.value)).toList();
                List<FieldNode> same = matches.stream().filter(f -> f.name.equals(wf.name) && wf.name.length() > 7).toList();
                if (same.size() == 1) matches = same;
                if (matches.size() == 1 && !members.containsKey("F\t" + w.name + "\t" + wf.name + "\t" + wf.desc)) member("F",w.name,wf.name,wf.desc,matches.get(0).name);
            }
        }
    }
}
