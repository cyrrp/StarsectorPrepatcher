# StarsectorPrepatcher

[English](README.md) | [Русский](README_RU.md)

Current version: **0.18.5**. Supported game build: **Starsector 0.98a-RC8**.

[![Unplayable without Prepatcher versus smooth with Prepatcher](https://github.com/kirpoly/StarsectorPrepatcher/raw/HEAD/media/smoothness_comparison.gif)](https://github.com/kirpoly/StarsectorPrepatcher/releases/download/v0.8.0/StarsectorPrepatcher-0.8.0-comparison.webm)

A collection of code optimizations for Starsector internals and selected mods.

## Windows installation

1. Close Starsector.
2. Extract the mod as `<Starsector>\mods\StarsectorPrepatcher`.
3. Install the javaagent. For vanilla, add the following to `<Starsector>\vmparams` on its existing argument line, after any other `-javaagent` entries and before `-classpath`:

   ```text
   -javaagent:../mods/StarsectorPrepatcher/agent/StarsectorPrepatcherAgent.jar
   ```

   **OR**

   Run the included `StarsectorPrepatcher.bat`, choose **Install javaagent**, and answer the yes/no prompts for each launch path. This does the edit automatically with timestamped backups, and also supports [Faster Rendering](https://github.com/Halke1986/starsector-render) (`fr.vmparams`) and the [Mikohime launcher](https://github.com/GaiusCassiusL/Starsector_Mikohime-Unofficial-Java28-Configurator) (Java 27+) — for those, use the .bat or figure it out yourself.
4. Enable **StarsectorPrepatcher** in the launcher (recommended — shows patch status in the game log) and start the game.
5. If you use **AoTD — Theory of Toolbox**, also install the maintained [Scheduler Fork](https://github.com/cyrrp/AoTD-Theory-Of-Toolbox-Scheduler-Fork) mod (release `1.0.14-spp13`) and enable it. Without it, the market performance fixes do not apply when AoTD is installed.

To remove: run `StarsectorPrepatcher.bat` and choose **Remove javaagent** (it backs up each file before changing it, with a timestamp), or delete the `-javaagent` entry manually.

## Linux installation

Use the release ZIP with prebuilt JARs. Extract it as `<Starsector>/mods/StarsectorPrepatcher`,
without a `-dev` or version suffix. The Linux game directory contains `starsector.sh` and the
game JARs directly; it does not need a `starsector-core` subdirectory. Close the game first.

For the standard launcher with Java 17 or newer, back up `starsector.sh` and add this line to
its existing Java command, after any other `-javaagent` arguments and before `-classpath`:

```sh
    -javaagent:./mods/StarsectorPrepatcher/agent/StarsectorPrepatcherAgent.jar \
```

Keep the trailing `\`: the following `-classpath` line must remain part of the same Java
command. Start `./starsector.sh` from the game directory. Enable the mod in the launcher as on
Windows. To uninstall, remove the added line from `starsector.sh`.

For Mikohime/Faster Rendering with a generated `Miko_Simple.txt`, run from the game directory:

```sh
JAVA_HOME=/path/to/your/java bash ./mods/StarsectorPrepatcher/StarsectorPrepatcher.sh
```

Set `JAVA_HOME` to the Java installation used by your Mikohime setup, or omit it if that Java
is already on `PATH`. Set `PREPATCHER_ARGS` to an absolute path if your argument file has a
different name. This launcher preserves the generated file and adds Prepatcher after its
existing agents. To stop using it, return to your normal launcher. Running the release
requires a Java runtime, not a compiler. With AoTD, use the Scheduler Fork linked above.

StarsectorPrepatcher is distributed under the terms in [`LICENSE`](LICENSE).
