# Minecraft Speedrun Plugin

Paper speedrun-assist plugin for Minecraft/Paper `26.1.2`.

Current plugin version: `26.1.2-3.1.11-ALPHA`.

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
speedrun/build/libs/speedrun-26.1.2-3.1.11-ALPHA.jar
```

## Install

1. Build the plugin jar.
2. Copy `speedrun-26.1.2-3.1.11-ALPHA.jar` into the server `plugins/` folder.
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
- `settings.reset_time_on_join`: optionally set loaded worlds to day when the first join starts the run.
- `progression.settings.task-display-mode`: `ACTIVE_STAGE`, `ALL_STAGES`, or `ALL_GAME_STAGES` task display.
- `progression.settings.completed-task-hide`: optionally hide completed tasks after a timeout.
- `casual.start-pre-scan.enabled`: enable controlled Casual live/background discovery.
- `casual.start-pre-scan.mode`: `SAFE`, `BALANCED`, or `AGGRESSIVE` scanner profile.
- `casual.compass.death-location.enabled`: add each player's own last death point to their compass menu.
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

## Notes For 3.1.3 Alpha

- Spawn compass targeting now refreshes from the live Overworld spawn instead of relying on a cached menu entry.
- Normal coordinate targets no longer receive invalid lodestone compass metadata.

## Notes For 3.1.4 Alpha

- Spawn compass targeting now uses a protected hidden lodestone, matching discovered structure compass behavior.

## Notes For 3.1.5 Alpha

- Beacon waypoints now try nearby safe footprints before falling back to legacy placement.

## Notes For 3.1.6 Alpha

- Server startup logs now include the plugin version, gamemode, and key runtime settings.

## Notes For 3.1.7 Alpha

- Approximate End Portal coordinates now convert correctly for Nether scoreboards.
- Task display can now show either the active stage or all configured stages for the player's world.

## Notes For 3.1.8 Alpha

- Task display can now group all configured tasks by world with `ALL_GAME_STAGES`.
- Completed tasks can optionally disappear from task displays after a configured timeout.

## Notes For 3.1.9 Alpha

- Default progression no longer auto-generates Fortress, Bastion, or Stronghold structure tasks.

## Notes For 3.1.10 Alpha

- Casual start pre-scan no longer calls blocking structure locate APIs; it uses loaded-chunk live discovery instead.
- Added `settings.reset_time_on_join` for first-join run starts.
- Removed the default duplicate `Stage Complete` tellraw reward and filters the legacy default command at runtime.
- Casual compass can show each player's own last death location as a private destination.

## Notes For 3.1.11 Alpha

- `reset_time_on_join` now skips worlds without a normal world clock instead of failing player join.
- Casual death destinations create a private hidden lodestone so Nether death tracking can point the compass reliably.
- Start pre-scan now supports `SAFE`, `BALANCED`, and `AGGRESSIVE` profiles with a budgeted background chunk queue.
- Background-discovered structure coordinates are treated as approximate and shown with approximate styling.

## License

MIT License. See `LICENSE.txt`.
