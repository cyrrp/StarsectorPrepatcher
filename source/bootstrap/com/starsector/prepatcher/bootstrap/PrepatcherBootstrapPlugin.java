package com.starsector.prepatcher.bootstrap;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

/**
 * Sandbox-safe status stub. All file access, reflection and bytecode work is
 * performed by the startup javaagent before Starsector creates mod classloaders.
 */
public final class PrepatcherBootstrapPlugin extends BaseModPlugin {
  @Override
  public void onApplicationLoad() {
    String version = System.getProperty("starsector.prepatcher.version", "unknown");
    String status = System.getProperty("starsector.prepatcher.status", "javaagent-not-installed");
    String patched = System.getProperty("starsector.prepatcher.patchedClasses", "0");
    String applied = System.getProperty("starsector.prepatcher.appliedPatches", "0");
    String skipped = System.getProperty("starsector.prepatcher.skippedPatches", "0");
    if ("javaagent-not-installed".equals(status)) {
      Global.getLogger(PrepatcherBootstrapPlugin.class)
          .warn("StarsectorPrepatcher mod is enabled, but its javaagent is not installed. "
              + "Run install-agent.bat from the mod folder; the game will continue unpatched.");
    } else {
      Global.getLogger(PrepatcherBootstrapPlugin.class)
          .info("StarsectorPrepatcher " + version + ": status=" + status + ", patchedClassesSoFar=" + patched
              + ", appliedPatchesSoFar=" + applied + ", skippedPatchesSoFar=" + skipped);
    }
  }
}
