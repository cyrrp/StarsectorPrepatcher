package com.starsector.prepatcher.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.ref.WeakReference;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * One explicitly ordered transformation chain. Every stage sees the bytes returned by
 * the preceding stage; a null result means "keep the current bytes".
 */
final class OrderedTransformerPipeline implements ClassFileTransformer {
    private final List<Stage> stages;
    private final WeakReference<ClassLoader> runtimeLoader;
    private final boolean predefineBridgeRoute;
    private final boolean repairIllegalNamesInAgentPipeline;

    OrderedTransformerPipeline(List<Stage> stages, ClassLoader runtimeLoader,
                               boolean predefineBridgeRoute,
                               boolean repairIllegalNamesInAgentPipeline) {
        this.stages = Collections.unmodifiableList(new ArrayList<>(stages));
        this.runtimeLoader = new WeakReference<>(runtimeLoader);
        this.predefineBridgeRoute = predefineBridgeRoute;
        this.repairIllegalNamesInAgentPipeline = repairIllegalNamesInAgentPipeline;
        System.setProperty("starsector.prepatcher.transformerOrder",
                String.join(" -> ", this.stages.stream().map(Stage::name).toList()));
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) return null;
        ClassLoader expected = runtimeLoader.get();
        if (predefineBridgeRoute && expected != null && loader == expected) {
            // Old Faster Rendering defines its com.fs classes from the bridge below.
            // Returning bytes here would make Java 27 verify an intermediate agent result.
            return null;
        }
        return apply(loader, className, classBeingRedefined,
                protectionDomain, classfileBuffer, true);
    }

    byte[] applyPredefine(String resourceName, byte[] bytes) {
        if (resourceName == null || bytes == null) return bytes;
        String className = resourceName.endsWith(".class")
                ? resourceName.substring(0, resourceName.length() - 6)
                : resourceName;
        ClassLoader loader = runtimeLoader.get();
        if (loader == null) {
            PrepatcherLog.warn("Faster Rendering predefine bridge lost its target loader; "
                    + "leaving " + resourceName + " unchanged.");
            return bytes;
        }
        byte[] transformed = apply(loader, className, null, null, bytes, false);
        return transformed == null ? bytes : transformed;
    }

    private byte[] apply(ClassLoader loader, String className,
                         Class<?> classBeingRedefined,
                         ProtectionDomain protectionDomain,
                         byte[] original, boolean nullWhenUnchanged) {
        byte[] current = original;
        byte[] pipelineInput = original;
        boolean changed = false;
        try {
            if (repairIllegalNamesInAgentPipeline) {
                byte[] compatible = IllegalObfuscatedMemberNameRepair.repair(className, current);
                if (compatible != current) {
                    current = compatible;
                    changed = true;
                }
            }
            // Java 27 compatibility normalization is a prerequisite to the patch group,
            // not one of its optional mutations. A later patch failure rolls back to
            // these loadable bytes instead of restoring an invalid obfuscated class.
            pipelineInput = current;
            // The complete chain is an atomic class-level group. Its input is retained
            // until every ordered member has had a chance to validate its post-state.
            for (Stage stage : stages) {
                Map<String, String> statusesBefore = diagnosticStatuses();
                byte[] next = stage.transformer().transform(loader, className,
                        classBeingRedefined, protectionDomain, current);
                String rejection = newlyRejectedStatus(statusesBefore);
                if (rejection != null) {
                    throw new IllegalStateException("stage '" + stage.name()
                            + "' published " + rejection);
                }
                if (next != null) {
                    current = next;
                    changed = true;
                }
            }
            return changed || !nullWhenUnchanged ? current : null;
        } catch (Throwable failure) {
            PrepatcherLog.error("Atomic transformer pipeline rolled back " + className
                    + " to its input bytes after an ordered-stage failure.", failure);
            System.setProperty("starsector.prepatcher.pipelineFailure."
                    + className.replace('/', '.'), failure.getClass().getSimpleName()
                    + ":" + String.valueOf(failure.getMessage()));
            if (nullWhenUnchanged && pipelineInput == original) return null;
            return pipelineInput;
        }
    }

    private static Map<String, String> diagnosticStatuses() {
        Map<String, String> result = new TreeMap<>();
        Properties properties = System.getProperties();
        synchronized (properties) {
            for (Map.Entry<Object, Object> entry : properties.entrySet()) {
                if (entry.getKey() instanceof String key
                        && key.startsWith("starsector.prepatcher.")
                        && entry.getValue() instanceof String value) {
                    result.put(key, value);
                }
            }
        }
        return result;
    }

    private static String newlyRejectedStatus(Map<String, String> before) {
        Map<String, String> after = diagnosticStatuses();
        for (Map.Entry<String, String> entry : after.entrySet()) {
            String value = entry.getValue();
            if (value.startsWith("SKIPPED_")
                    && !value.equals(before.get(entry.getKey()))) {
                return entry.getKey() + "=" + value;
            }
        }
        return null;
    }

    record Stage(String name, ClassFileTransformer transformer) {
        Stage {
            if (name == null || name.isBlank() || transformer == null) {
                throw new IllegalArgumentException("Pipeline stage metadata is incomplete");
            }
        }
    }
}
