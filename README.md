# StarsectorPrepatcher

[English](README.md) | [Русский](README_RU.md)

Current version: **0.18.4**. Supported game build: **Starsector 0.98a-RC8**.

[![Unplayable without Prepatcher versus smooth with Prepatcher](media/smoothness_comparison.gif)](https://github.com/kirpoly/StarsectorPrepatcher/releases/download/v0.8.0/StarsectorPrepatcher-0.8.0-comparison.webm)

A collection of code optimizations for Starsector internals and selected mods.

## Installation

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

StarsectorPrepatcher is distributed under the terms in [`LICENSE`](LICENSE).
