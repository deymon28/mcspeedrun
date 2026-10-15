# Configuration reference

This reference lists every setting of `plugins/Speedrun/config.yml` with its default value.
It is generated from the commented default file, which is the authoritative source.
Settings can also be changed in the browser editor (`/run webconfig`), which explains each one.

After editing the file by hand, run `/run reload`. Values that are invalid fall back to their
defaults and are reported in the console. When the plugin is updated, the file is upgraded
automatically and the previous version is saved to `plugins/Speedrun/config-backups/`.

## `web-config`

| Setting | Default | Description |
| --- | --- | --- |
| `web-config.enabled` | `true` | Local browser editor for this file. Administrators get a private access link with /run webconfig. |
| `web-config.bind` | `"127.0.0.1"` | Network address of the editor. Keep 127.0.0.1 unless you deliberately expose it outside this machine. |
| `web-config.port` | `8765` | TCP port of the editor (1024-65535). |
| `web-config.allow-console-reward-commands` | `false` | Allow the editor to add reward entries that run console commands. Sound and particle rewards ("api:" entries) are always allowed. Console commands can still be added here by hand. |

## `settings`

| Setting | Default | Description |
| --- | --- | --- |
| `settings.gamemode` | `"NORMAL"` | NORMAL: timer, tasks, and location tracking. CASUAL: everything in NORMAL plus a navigation compass, waypoints, Nether gold highlighting, and tab coordinates. HARDCORE: only the timer; all assistance is disabled. |
| `settings.language` | `"en"` | Language of in-game messages: en, uk, de, es, fr, pt_BR, pl, zh_CN. Message files are in plugins/Speedrun/lang and can be customized. |
| `settings.start-on-first-join` | `true` | Start the timer when the first player joins. If false, the timer starts when the server starts. |
| `settings.reset_time_on_join` | `true` | When the first player join starts a run, also set the Overworld time to morning. |
| `settings.pause-when-empty` | `false` | Pause the timer automatically while nobody is online and resume when a player returns. Runs paused manually with /run pause stay paused. |
| `settings.resume-after-restart` | `true` | Keep an active run when the server stops, restarts, or crashes. The run comes back paused and continues when the first player joins; the downtime is not counted. It is only restored into the same world, game mode, and stage setup. |
| `settings.allow-reassigning-locations` | `true` | Allow /run new <location> to move locations that were already found. If false, only empty locations can be set. |
| `settings.village-search-timeout` | `600` | Seconds to find a village before the village target is dropped. 600 = 10 minutes. |
| `settings.task-tracking-mode` | `"INVENTORY"` | How collected resources count towards tasks: INVENTORY  - items currently in online players' inventories. CUMULATIVE - every item picked up or crafted during the run, even if used later. Items that a player dropped are not counted again when picked up. |
| `settings.scale-resources-by-playercount.enabled` | `true` | Scale required resource amounts with the number of online players (only tasks with srbp: true). |
| `settings.scale-resources-by-playercount.multiplier` | `0.5` | Required amount = base * (1 + (players - 1) * multiplier). 0.5 adds 50% per extra player. |
| `settings.log-attempts` | `true` | Write a text and a JSON log for every attempt to plugins/Speedrun/logs. |
| `settings.notify-admins-on-join` | `true` | Notify administrators on join about configuration upgrades and how to open the editor. |
| `settings.chunk-biome-logging.enabled` | `true` | Log each visited Overworld chunk once with its biome (for activity maps in the JSON log). |
| `settings.coordinate-display.mode` | `"CONDITIONAL"` | How coordinates appear on the scoreboard: UNIFIED     - always show the coordinates of the world where the location is. SEPARATE    - only show locations of the world the player is in. CONDITIONAL - convert Overworld/Nether coordinates to the player's current world. |
| `settings.scoreboard.hide-score-numbers` | `true` | Hide the red numbers on the right side of the sidebar (Paper only; ignored elsewhere). |
| `settings.proximity-scanner.lava-pool.radius` | `16` | Automatic detection of locations around players. Horizontal radius in blocks to look for lava pools around each player. |
| `settings.proximity-scanner.lava-pool.required-source-blocks` | `12` | Lava source blocks needed to count as a pool. |
| `settings.proximity-scanner.village.radius` | `32` | Radius in blocks to look for village bells around each player. |
| `settings.proximity-scanner.nether-portal.check-radius` | `4` | Radius in blocks around a lit block or portal entry to find the portal frame. |
| `settings.proximity-scanner.nether-portal.search-radius` | `90` | Radius in blocks to find the exact exit portal after a player travels through a portal (maximum 160). |
| `settings.proximity-scanner.portal-search-timeout` | `15` | Seconds to wait for the exact exit portal search before the approximate arrival point is used. |

## `records`

| Setting | Default | Description |
| --- | --- | --- |
| `records.enabled` | `true` | Remember the best completion time and the best time for each stage in records.yml. |
| `records.announce-splits` | `true` | Announce the time and the difference to the best split whenever a stage is completed. |

## `tracking`

| Setting | Default | Description |
| --- | --- | --- |
| `tracking.player-blocks.enabled` | `false` | Log block coordinates of every player movement for replay/map analysis. Produces large JSON logs. |

## `diagnostics`

| Setting | Default | Description |
| --- | --- | --- |
| `diagnostics.trace.enabled` | `false` | Write detailed JSONL diagnostics to plugins/Speedrun/traces. Enable while reproducing a bug. |
| `diagnostics.trace.mirror-to-console` | `false` | Also print trace events to the server console. |
| `diagnostics.trace.categories` | `["ALL"]` | ALL, or a list of: diagnostics, lifecycle, compass, lodestone, scanner, structure, portal. |

## `casual`

| Setting | Default | Description |
| --- | --- | --- |
| `casual.enabled` | `true` | Master switch for CASUAL features when settings.gamemode is CASUAL. |
| `casual.start-pre-scan.enabled` | `false` | Search for structures in the background when a CASUAL run starts. |
| `casual.start-pre-scan.mode` | `"BALANCED"` | SAFE       - only chunks that are already loaded. BALANCED   - also loads a limited number of missing chunks (async on Paper). AGGRESSIVE - larger chunk budget, also scans the Nether. LOCATE     - uses the server's structure locate function in small steps. |
| `casual.start-pre-scan.radius` | `1000` | Maximum distance in blocks from spawn/players for background scanning (1-1024). |
| `casual.start-pre-scan.loaded-chunk-radius` | `10` | Radius in chunks around each player that is scanned when chunks are already loaded. |
| `casual.start-pre-scan.include-lava-pool` | `true` | Also look for lava pools during the pre-scan. |
| `casual.start-pre-scan.allow-sync-chunk-loading` | `false` | Allow BALANCED/AGGRESSIVE to load missing chunks on servers without asynchronous chunk loading (Spigot/CraftBukkit). Loads are limited to one chunk per tick but may still cost TPS. |
| `casual.start-pre-scan.lava-pool-vertical-scan.below-player` | `12` | Blocks below the scan origin to look for lava pools. |
| `casual.start-pre-scan.lava-pool-vertical-scan.above-player` | `32` | Blocks above the scan origin to look for lava pools. |
| `casual.start-pre-scan.safety.max-chunks-per-run` | `8` | Hard limits applied on top of every profile to protect the server from mass chunk generation. |
| `casual.start-pre-scan.safety.max-queued-chunks` | `5000` |  |
| `casual.start-pre-scan.locate.radius-chunks` | `96` | LOCATE mode only. Search radius in chunks. Each call runs on the server thread, so keep calls-per-run low. |
| `casual.start-pre-scan.locate.period-ticks` | `20` |  |
| `casual.start-pre-scan.locate.calls-per-run` | `1` |  |
| `casual.start-pre-scan.locate.find-unexplored` | `false` |  |
| `casual.start-pre-scan.locate.targets.village` | `true` |  |
| `casual.start-pre-scan.locate.targets.stronghold` | `true` |  |
| `casual.start-pre-scan.locate.targets.nether-structures` | `true` |  |
| `casual.start-pre-scan.profiles.SAFE.chunks-per-run` | `0` | Budgets for each mode. chunks-per-run chunks are processed every period-ticks. |
| `casual.start-pre-scan.profiles.SAFE.period-ticks` | `40` |  |
| `casual.start-pre-scan.profiles.SAFE.max-queued-chunks` | `0` |  |
| `casual.start-pre-scan.profiles.SAFE.load-missing-chunks` | `false` |  |
| `casual.start-pre-scan.profiles.SAFE.scan-spawn` | `false` |  |
| `casual.start-pre-scan.profiles.SAFE.scan-players` | `false` |  |
| `casual.start-pre-scan.profiles.SAFE.include-nether` | `false` |  |
| `casual.start-pre-scan.profiles.BALANCED.chunks-per-run` | `2` |  |
| `casual.start-pre-scan.profiles.BALANCED.period-ticks` | `20` |  |
| `casual.start-pre-scan.profiles.BALANCED.max-queued-chunks` | `1500` |  |
| `casual.start-pre-scan.profiles.BALANCED.load-missing-chunks` | `true` |  |
| `casual.start-pre-scan.profiles.BALANCED.scan-spawn` | `true` |  |
| `casual.start-pre-scan.profiles.BALANCED.scan-players` | `true` |  |
| `casual.start-pre-scan.profiles.BALANCED.include-nether` | `false` |  |
| `casual.start-pre-scan.profiles.AGGRESSIVE.chunks-per-run` | `8` |  |
| `casual.start-pre-scan.profiles.AGGRESSIVE.period-ticks` | `10` |  |
| `casual.start-pre-scan.profiles.AGGRESSIVE.max-queued-chunks` | `5000` |  |
| `casual.start-pre-scan.profiles.AGGRESSIVE.load-missing-chunks` | `true` |  |
| `casual.start-pre-scan.profiles.AGGRESSIVE.scan-spawn` | `true` |  |
| `casual.start-pre-scan.profiles.AGGRESSIVE.scan-players` | `true` |  |
| `casual.start-pre-scan.profiles.AGGRESSIVE.include-nether` | `true` |  |
| `casual.start-pre-scan.profiles.LOCATE.chunks-per-run` | `0` |  |
| `casual.start-pre-scan.profiles.LOCATE.period-ticks` | `40` |  |
| `casual.start-pre-scan.profiles.LOCATE.max-queued-chunks` | `0` |  |
| `casual.start-pre-scan.profiles.LOCATE.load-missing-chunks` | `false` |  |
| `casual.start-pre-scan.profiles.LOCATE.scan-spawn` | `false` |  |
| `casual.start-pre-scan.profiles.LOCATE.scan-players` | `false` |  |
| `casual.start-pre-scan.profiles.LOCATE.include-nether` | `false` |  |
| `casual.compass.show-nether-reference` | `false` | Show the legacy fixed "Nether Reference" destination (not linked to the run). |
| `casual.compass.death-location.enabled` | `true` | Add each player's last death point to their own compass menu. |
| `casual.structure_waypoints.enabled` | `true` | Light beams above found Overworld locations. |
| `casual.structure_waypoints.type` | `"END_GATEWAY"` | END_GATEWAY: compact beam. BEACON: classic beacon beam. |
| `casual.structure_waypoints.offset-blocks` | `10` | Horizontal distance from the location so the marker does not replace important blocks. |
| `casual.structure_waypoints.update-interval-seconds` | `10` | Seconds between waypoint refreshes. |
| `casual.player-tab-coordinates` | `true` | Show each player's own coordinates at the top of the tab list. |
| `casual.nether_gold_highlight.enabled` | `true` | Make gold blocks glow around players in the Nether and inside found bastions. |
| `casual.nether_gold_highlight.radius` | `24` | Radius in blocks around each player in the Nether. |

## `progression`

| Setting | Default | Description |
| --- | --- | --- |
| `progression.settings.task-display-mode` | `"ALL_GAME_STAGES"` | ACTIVE_STAGE: only the current stage. ALL_STAGES: all tasks of the player's world. ALL_GAME_STAGES: all tasks grouped by world. |
| `progression.settings.completed-task-hide.enabled` | `true` | Hide completed tasks after timeout-seconds. |
| `progression.settings.completed-task-hide.timeout-seconds` | `10` |  |

## `rewards`

| Setting | Default | Description |
| --- | --- | --- |
| `rewards.enabled` | `true` |  |
| `rewards.on-task-complete` | `list` | Reward lines. |
| `rewards.on-stage-complete` | `list` | Reward lines. |

## Stages and tasks (`progression`)

Every key under `progression` except `settings` is a stage. Stages are completed in the
alphabetical order of their keys. A stage without tasks is skipped (it would otherwise complete
instantly and announce a meaningless split).

| Stage field | Description |
| --- | --- |
| `display-name` | Name shown in chat and in `/run status`. Translations in `lang/*.yml` (`stages.<KEY>`) take precedence. |
| `world` | `NORMAL`, `NETHER`, or `THE_END`: the dimension in which the stage's tasks are shown. |
| `tasks` | Map of task key to `{ amount, display-name, srbp }`. |

Task keys are Minecraft item names (for example `IRON_INGOT`) or structure keys:
`STRUCTURE_LAVA_POOL`, `STRUCTURE_VILLAGE`, `STRUCTURE_NETHER_PORTAL`, `STRUCTURE_FORTRESS`,
`STRUCTURE_BASTION`, `STRUCTURE_END_PORTAL`. `STRUCTURE_NETHER_PORTAL` completes when either side
of a portal is found; `STRUCTURE_NETHER_PORTAL_OVERWORLD` and `STRUCTURE_NETHER_PORTAL_NETHER`
require that side. `OAK_LOG` counts every log, wood, stem, and hyphae type (including cherry,
mangrove, and pale oak), `BREAD` counts every cooked food. `srbp: true` scales the amount with the number of online players.

## Managed values

`config-version` and `plugin-version` are written by the plugin and drive automatic upgrades.
Do not edit them.
