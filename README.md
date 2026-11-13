# StarsectorPrepatcher

[English](README.md) | [Русский](README_RU.md)

Current version: **0.18.8**. Supported game builds: **Starsector 0.98a-RC8, 0.98.5a-RC3 and 0.98.5a-RC5** (Windows/Linux).
The metadata targets 0.98.5a-RC5; on other supported builds the launcher may show a different patch-version notice.

[![Unplayable without Prepatcher versus smooth with Prepatcher](https://github.com/kirpoly/StarsectorPrepatcher/raw/HEAD/media/smoothness_comparison.gif)](https://github.com/kirpoly/StarsectorPrepatcher/releases/download/v0.8.0/StarsectorPrepatcher-0.8.0-comparison.webm)

A collection of code optimizations for Starsector internals and selected mods.

If you use Faster Rendering with Starsector 0.98.5a, use version 1.0.0 or later. Regenerate your Mikohime configuration after updating Faster Rendering.

## Windows installation

1. Close Starsector.
2. Extract the mod as `<Starsector>\mods\StarsectorPrepatcher`.
3. Install the javaagent. For vanilla, add the following to `<Starsector>\vmparams` on its existing argument line, after any other `-javaagent` entries and before `-classpath`:

   ```text
   -javaagent:../mods/StarsectorPrepatcher/agent/StarsectorPrepatcherAgent.jar
   ```

   **OR**

   Run the included `StarsectorPrepatcher.bat`, choose **Install javaagent**, and select the launch paths you use. It edits vanilla, the separate [Faster Rendering](https://github.com/Halke1986/starsector-render) launcher (`fr.vmparams`), and original Mikohime automatically with timestamped backups. Configure original Mikohime once before installing the agent.

   [Gaius's Mikohime configurator](https://github.com/GaiusCassiusL/Starsector_Mikohime-Unofficial-Java28-Configurator) has built-in Prepatcher support: enable **StarsectorPrepatcher** there, generate the configuration, and launch with `Miko_Rouge.bat` or `Miko_Silent.bat` if available. Our installer detects this integration and displays instructions without editing its files. Selecting **Mikohime** is sufficient when Faster Rendering runs through Mikohime; the separate **FasterRendering** target is not needed for that launch path.
4. Enable **StarsectorPrepatcher** in the launcher (recommended — shows patch status in the game log) and start the game.
5. If you use **AoTD — Theory of Toolbox**, also install the maintained [Scheduler Fork](https://github.com/cyrrp/AoTD-Theory-Of-Toolbox-Scheduler-Fork) mod (release `1.0.14-spp13`) and enable it. Without it, the market performance fixes do not apply when AoTD is installed.

To remove: for Gaius's Mikohime with built-in integration, disable **StarsectorPrepatcher** in `Configure_Me.cmd` and regenerate the configuration before deleting the mod folder. For other launch paths, run `StarsectorPrepatcher.bat` and choose **Remove javaagent** (it backs up each changed file with a timestamp), or delete the `-javaagent` entry manually.

## Linux installation

1. Close Starsector.
2. Extract the release ZIP as `<Starsector>/mods/StarsectorPrepatcher`.
3. Install the javaagent (Java 17+). For vanilla, back up `<Starsector>/starsector.sh` and add this line to its existing Java command, after any other `-javaagent` entries and before `-classpath`. Keep the trailing `\`:

   ```sh
   -javaagent:./mods/StarsectorPrepatcher/agent/StarsectorPrepatcherAgent.jar \
   ```

   **OR**

   For Mikohime/Faster Rendering with `Miko_Simple.txt`, use the included launcher from the game directory:

   ```sh
   JAVA_HOME=/path/to/your/java bash ./mods/StarsectorPrepatcher/StarsectorPrepatcher.sh
   ```

   Point `JAVA_HOME` to your Mikohime Java installation, or omit it if that Java is on `PATH`.
4. Enable **StarsectorPrepatcher** in the launcher (recommended — shows patch status in the game log). For vanilla, start `./starsector.sh` from the game directory.
5. If you use **AoTD — Theory of Toolbox**, also install the maintained [Scheduler Fork](https://github.com/cyrrp/AoTD-Theory-Of-Toolbox-Scheduler-Fork) mod (release `1.0.14-spp13`) and enable it. Without it, the market performance fixes do not apply when AoTD is installed.

To remove: delete the added `-javaagent` line from `starsector.sh`, or return to your normal Mikohime launcher.

StarsectorPrepatcher is distributed under the terms in [`LICENSE`](LICENSE).
