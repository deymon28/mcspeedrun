# Minecraft Speedrun Plugin

Paper speedrun-assist plugin for Minecraft/Paper `26.1.2`.

Current plugin version: `26.1.2-3.1.2-ALPHA`.

## Features

- Speedrun timer with start, pause, stop, reset, and final-time handling.
- Location tracking for lava pools, villages, Nether portals, fortresses, bastions, and strongholds.
- Native lodestone compass support for stable navigation, including Nether tracking.
- Casual mode with navigation compass, structure waypoints, Nether gold highlighting, and tab-list coordinates.
- Hardcore mode that disables assistance and keeps the UI focused on the timer.
- Staged task progression with configurable item and structure tasks.
- Optional start pre-scan for required structure coordinates.
- Chunk biome logging for Overworld activity-map analysis.
- Configurable rewards through console commands, sounds, and particles.
- Legacy color code and Adventure/MiniMessage message support.
- English and Ukrainian language files.

## Project Structure

The plugin project lives in the `speedrun/` folder:

```text
speedrun/
  src/main/
  build.gradle
  gradle.properties
  settings.gradle
```

## Requirements

- Java `25`
- Paper server `26.1.2`
- Gradle `9.5.1`

The build uses Paper API `26.1.2.build.69-stable`.

## Build

From the repository root:

```powershell
cd speedrun
.\gradlew.bat build
```

The plugin jar is generated at:

```text
speedrun/build/libs/speedrun-26.1.2-3.1.2-ALPHA.jar
```

## Install

1. Build the plugin jar.
2. Copy `speedrun-26.1.2-3.1.2-ALPHA.jar` into the server `plugins/` folder.
3. Start or restart a Paper `26.1.2` server.

This alpha version has been verified to load on Paper `26.1.2-69`.

## Commands

- `/run start` - start the speedrun.
- `/run pause` - pause or resume the timer.
- `/run stop` - stop the current run.
- `/run reset` - reset timer, tasks, scanners, waypoints, and runtime caches.
- `/run reload` - reload configuration and tasks.
- `/run skipstage` - complete the current task stage.
- `/run status` - show current run status.
- `/run tasks` - show active tasks for the current world.
- `/run new <location> [x y z]` - set a tracked location, optionally at custom coordinates.
- `/run remove <location>` - hide a tracked location and stop its related search state.
- `/run locate ...` - stronghold/end portal triangulation helper.
- `/run givecompass [player]` - give the navigation compass.

## Permissions

- `speedrun.player` - basic `/run` access. Default: `true`.
- `speedrun.admin` - administrative run control. Default: `op`.

## Configuration

Main settings are in `speedrun/src/main/resources/config.yml` and are copied to the plugin data folder on first run.

Important options include:

- `settings.gamemode`: `NORMAL`, `CASUAL`, or `HARDCORE`.
- `casual.start-pre-scan.enabled`: pre-scan required structures on Casual run start.
- `settings.chunk-biome-logging.enabled`: log visited Overworld chunk biomes.
- `casual.structure_waypoints`: enable structure waypoints. Beacon is the safe default; End Gateway remains compatible but is not recommended for normal play.
- `casual.nether_gold_highlight`: highlight gold blocks around Nether players.
- `progression`: staged item and structure tasks.
- `rewards`: global reward commands, sounds, and particles.

## Notes For 3.0.0 Alpha

- Migrated to Minecraft/Paper `26.1.2`.
- Added native lodestone compass support.
- Added Casual and Hardcore mode behavior.
- Reworked task progression into stages.
- Added Paper async gold scanning with Bukkit/Spigot fallback.
- Fixed Nether portal fire-spread false positives.
- Fixed village timeout/reset behavior.
- Added exact Nether portal search timeout fallback to approximate coordinates.

## Notes For 3.1.0 Alpha

- Start pre-scan now belongs to `casual.start-pre-scan` and is ignored outside Casual mode.
- Structure pre-scan is staggered and treats the configured radius as blocks, with a chunk cap for Paper locate calls.
- Portal entry now records missing source-side coordinates before searching the destination side.
- Scoreboards are reattached on player rejoin.
- Coordinate display can be `UNIFIED`, `SEPARATE`, or `CONDITIONAL`.
- Attempt JSON logs include run metadata, Y coordinates, and optional block-level player tracking.

## Notes For 3.1.1 Alpha

- Coordinate display modes no longer add world labels or linked coordinates in brackets.
- Nether compass actionbar now shows distance without a text direction arrow.
- End portal entry is detected from the portal block, and End Gateway waypoint side effects are guarded.

## Notes For 3.1.2 Alpha

- Predicted End Portal coordinates from `/run locate` stay yellow until confirmed.
- The compass Spawn destination now uses the world's actual spawn location.
- Navigation compasses only use lodestone tracking when a hidden lodestone exists, and `/run givecompass` replaces the old top-level command.

## License

MIT License. See `LICENSE.txt`.
