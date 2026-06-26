# Minecraft Speedrun Plugin

Paper speedrun-assist plugin for Minecraft/Paper `26.1.2`.

Current plugin version: `26.1.2-3.5.3-ALPHA`.

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
- Local browser-based config editor with schema validation and safe hot-apply.

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
speedrun/build/libs/speedrun-26.1.2-3.5.3-ALPHA.jar
```

## Install

1. Build the plugin jar.
2. Copy `speedrun-26.1.2-3.5.3-ALPHA.jar` into the server `plugins/` folder.
3. Start or restart a Paper `26.1.2` server.

This alpha version has been verified to load on Paper `26.1.2-69`.

## Commands

- `/run start` - start the speedrun.
- `/run pause` - pause or resume the timer.
- `/run stop` - stop the current run.
- `/run reset` - reset timer, tasks, scanners, waypoints, and runtime caches.
- `/run reload` - reload configuration and tasks.
- `/run webconfig [restart|stop]` - show, restart, or stop the local web configuration editor.
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

- `web-config.enabled`: start a localhost browser editor and print a one-run tokenized URL in the server console.
- `web-config.bind` and `web-config.port`: local HTTP bind settings. Keep the bind address on `127.0.0.1` unless intentionally exposing it.
- `settings.gamemode`: `NORMAL`, `CASUAL`, or `HARDCORE`.
- `settings.reset_time_on_join`: set loaded worlds to day when the first join starts the run.
- `progression.settings.task-display-mode`: `ACTIVE_STAGE`, `ALL_STAGES`, or `ALL_GAME_STAGES` task display. Default is `ALL_GAME_STAGES`.
- `progression.settings.completed-task-hide`: hide completed tasks after a timeout. Default is enabled with a 10 second timeout.
- `casual.start-pre-scan.enabled`: enable controlled Casual live/background discovery.
- `casual.start-pre-scan.mode`: `SAFE`, `BALANCED`, `AGGRESSIVE`, or `LOCATE` scanner profile.
- `casual.start-pre-scan.lava-pool-vertical-scan`: vertical scan range around players for pre-scan lava pool detection.
- `casual.start-pre-scan.safety`: hard caps for background chunk queue load.
- `casual.compass.death-location.enabled`: add each player's own last death point to their compass menu.
- `diagnostics.trace.enabled`: write detailed JSONL runtime traces for bug reproduction.
- `settings.chunk-biome-logging.enabled`: log visited Overworld chunk biomes.
- `casual.structure_waypoints`: enable structure waypoints. End Gateway is the default marker type; Beacon remains available.
- `casual.nether_gold_highlight`: highlight gold blocks around Nether players.
- `progression`: staged item and structure tasks.
- `rewards`: global reward commands, sounds, and particles.

## Testing

Fast JUnit tests cover pure utility logic, task scaling/progress behavior, command structure alias parsing, config defaults, language keys, plugin metadata, and Overworld/Nether coordinate scaling:

```powershell
cd speedrun
.\gradlew.bat test
```

Full build runs the same test suite before producing the plugin jar:

```powershell
cd speedrun
.\gradlew.bat build
```

Paper smoke testing starts real temporary Paper servers outside the repository and verifies the plugin enables across key configuration profiles: `NORMAL`, `CASUAL` with `SAFE`, `BALANCED`, `AGGRESSIVE`, and `LOCATE` pre-scan, and `HARDCORE`.

```powershell
cd speedrun
powershell.exe -ExecutionPolicy Bypass -File .\scripts\paper-smoke-test.ps1
```

## Structure Discovery

The background scanner scans loaded chunks and, in `BALANCED`/`AGGRESSIVE`, a bounded async Paper chunk queue. Safety caps limit chunk queue pressure even when a config profile is set too high.

- Overworld generated structures can map to `VILLAGE` and `END_PORTAL` from Stronghold metadata.
- Nether generated structures can map to `FORTRESS` and `BASTION`.
- Fortress and Bastion are ignored in the Overworld because the mapper only accepts them when the scanned world environment is `NETHER`.
- End Portal/Stronghold scanning is ignored in the Nether; Nether scoreboards may still show converted approximate Overworld coordinates.
- Lava Pool and Bell confirmation scans run only in the Overworld block/snapshot scanner.
- `LOCATE` mode performs a small, staggered set of Bukkit/Paper locate API calls for villages, strongholds, fortresses, and bastions, then falls back to loaded-chunk live scanning. Locate calls are synchronous, so keep `calls-per-run` low.

## Diagnostics Trace

Set `diagnostics.trace.enabled: true` in `config.yml` and reload/restart to write JSONL traces under `plugins/Speedrun/traces`. Use this when reproducing compass, lodestone, portal, or scanner bugs. Categories can be `ALL` or selected values: `diagnostics`, `lifecycle`, `compass`, `lodestone`, `scanner`, `structure`, `portal`.

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

## Notes For 3.2.0 Alpha

- Added a JUnit 5 test suite for utility logic, task scaling, config/profile parsing, command aliases, resource defaults, and plugin metadata.
- Added a Paper smoke-test script that boots real temporary Paper servers across the main runtime configuration profiles.
- Extracted linked Overworld/Nether coordinate scaling into a testable utility used by the scoreboard.

## Notes For 3.2.1 Alpha

- Approximate Village coordinates can now be confirmed by finding or interacting with a Bell.
- Nether Portal compass tracking now keeps separate hidden lodestones for each portal side/world.

## Notes For 3.3.0 Alpha

- Spawn and other compass destinations now create hidden lodestones more reliably, including async chunk loading when the target chunk is not loaded.
- Hidden lodestones are placed deeper at the same X/Z instead of near the surface, and old lodestones are not removed until a replacement can be placed.
- Added optional JSONL trace diagnostics for compass selection, lodestone placement, scanner decisions, structure matches, portals, and run lifecycle events.

## Notes For 3.4.0 Alpha

- Added `LOCATE` start pre-scan mode for bounded one-shot structure locate API calls with loaded-chunk fallback.
- Added hard safety caps for background pre-scan chunk load rate and queue size, so oversized configs cannot generate tens of thousands of chunks unchecked.
- Documented that oversized `AGGRESSIVE` settings can cause long shutdown saves and Nether chunk loading stalls.

## Notes For 3.4.1 Alpha

- Updated generated config defaults for reset-time-on-join, End Gateway waypoints, all-game-stage task display, and completed-task hiding.

## Notes For 3.4.2 Alpha

- Start pre-scan lava pool detection now scans 12 blocks below and 32 blocks above the player origin by default, with configurable vertical scan bounds.
- Tasks shown by all-stage display modes now track progress and completion even before their progression stage becomes active.

## Notes For 3.5.0 Alpha

- Added a local token-protected web config editor served from the plugin through Java's built-in HTTP server.
- The editor exposes schema-driven fields plus structured progression and rewards editors, validation, preview, save, apply, and save-and-apply actions.
- Runtime apply now refreshes trace settings, Casual mode components, scanner tasks, tab coordinates, waypoints, highlights, and scoreboards where safe; active progression changes are held for reset to avoid losing task progress.

## Notes For 3.5.1 Alpha

- Reworked the web config editor into a dark-only interface with collapsible setting groups.
- Added schema metadata for visual group labels and parent setting dependencies, so inactive Casual, scanner, waypoint, reward, and scaling settings show why they currently do not apply.
- The change preview now groups pending edits by apply impact before save/apply.

## Notes For 3.5.3 Alpha

- Fixed web config language apply so language values are canonicalized to bundled file codes, both bundled language files are generated on startup, and failed apply attempts do not leave a broken runtime language value.

## License

MIT License. See `LICENSE.txt`.
