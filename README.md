# Speedrun

A speedrun companion plugin for Minecraft servers. It adds a run timer, staged tasks,
automatic tracking of key locations, personal bests with stage splits, and an optional
assisted mode with a navigation compass. Everything can be configured in a browser.

It runs on **Paper, Spigot, and CraftBukkit** and uses each platform's strengths:
asynchronous chunk loading and native text components on Paper, the Spigot chat API on
Spigot, and plain Bukkit everywhere else.

Current version: **4.1.0 beta** for Minecraft **26.3** (`speedrun-26.3-4.1.0-BETA.jar`).
See [docs/releases/v4.1.0-beta.md](docs/releases/v4.1.0-beta.md) for what changed.

## Highlights

- **Run timer** that starts with the first player or immediately, pauses and resumes, optionally
  pauses itself while the server is empty, and stops when the Ender Dragon dies. It measures real
  time, so a lagging server does not slow it down.
- **Runs survive restarts and crashes** – an active run is saved regularly and continues after the
  server comes back (paused until the first player joins; the downtime does not count).
- **Stages and tasks** – collect items (wood, iron, cooked food, pearls, blaze rods, …) or find
  structures; the next stage starts when the current one is complete.
- **Location tracking** for lava pools, villages, Nether portals (both sides), fortresses,
  bastions, and the stronghold, shown on the scoreboard in the coordinates of the player's world.
- **Personal bests and splits** – each stage completion is announced with the difference to your
  best split; a finished run is compared with the best time and stored per game mode.
- **Three game modes**
  - *Normal*: timer, tasks, and location tracking.
  - *Casual*: adds a Navigation Compass with a destination menu (including your last death),
    light beams over found locations, glowing gold blocks in the Nether, coordinates in the tab
    list, and an optional background structure search.
  - *Hardcore*: timer only, all assistance off.
- **Browser settings editor** with search, explanations for every setting, a guide, change
  review, undo, and safe apply rules – in 8 languages.
- **In-game messages in 8 languages**: English, Ukrainian, German, Spanish, French, Portuguese
  (Brazil), Polish, and Simplified Chinese. All texts can be customized.
- **Automatic configuration upgrades** that keep your settings, add new ones, and back up the old file.
- **Run logs** (text and JSON) for analysis, plus optional detailed diagnostics.

## Compatibility

| Server software | Minecraft | Status |
| --- | --- | --- |
| Paper | 26.3 | Tested. Full feature set, asynchronous chunk loading. |
| Spigot | 26.3 | Tested. Full feature set; missing chunks are loaded synchronously (rate-limited). |
| CraftBukkit | 26.3 | Tested. Same as Spigot; clickable chat links are not available. |
| Paper | 26.1.2 | Loads and runs (startup smoke test). |
| Folia | – | Not supported (the plugin relies on a single main thread). |

Requires **Java 25** or newer. Paper builds for 26.3 are marked beta by the Paper team.

## Installation

1. Download `speedrun-26.3-4.1.0-BETA.jar` from the [releases page](https://github.com/deymon28/mcspeedrun/releases)
   and check it against the published `.sha256` file.
2. Put the jar into your server's `plugins/` folder and start the server.
3. Join as an operator and run `/run webconfig`. Click the link to open the settings editor.
4. Pick a game mode, review the tasks, and press **Save & apply**.
5. Start a run with `/run start`, or simply join if *Start when the first player joins* is on.

Upgrading from 3.x: replace the jar and restart. Your `config.yml` is upgraded automatically,
the old file is saved to `plugins/Speedrun/config-backups/`, and a summary is written to the
console and shown to administrators when they join.

## Commands and permissions

The command is `/run` (alias `/sr`). `speedrun.player` is granted to everyone, `speedrun.admin`
to operators. Administrative commands also work from the server console.

| Command | Permission | Description |
| --- | --- | --- |
| `/run help` | player | List the commands you can use. |
| `/run status` | player | Timer, current stage, and players. |
| `/run tasks` | player | Open tasks in your world. |
| `/run records` | player | Personal best and best splits of the current game mode. |
| `/run givecompass [player]` | player / admin for others | Get a Navigation Compass. |
| `/run start` · `pause` · `stop` · `reset` | admin | Control the run. `reset` starts a fresh run. |
| `/run skipstage` | admin | Complete the current stage (the run will not count for records). |
| `/run new <location> [x y z]` | admin | Set a location at your position or at coordinates. |
| `/run remove <location>` | admin | Hide a location from the scoreboard. |
| `/run locate pos1` / `pos2` | admin | Estimate the stronghold from two Eye of Ender throws (stand where you threw and look where the eye flew). |
| `/run locate <x1> <z1> <yaw1> <x2> <z2> <yaw2>` | admin | Same estimate from two throws noted from the F3 screen. |
| `/run records reset` | admin | Clear the records of the current game mode. |
| `/run reload` | admin | Reload `config.yml` and message files. |
| `/run webconfig [restart\|stop]` | admin | Get a private link to the settings editor. |

Locations: `lava pool`, `village`, `nether portal`, `fortress`, `bastion`, `end portal`.

## Configuration

### Browser editor

`/run webconfig` prints a private link (clickable in game). The editor runs inside the plugin on
`127.0.0.1:8765`, needs no account, and offers:

- every setting with a plain-language explanation, its default value, and a reset button;
- search across all settings, a filter for inactive settings, and an 8-language interface;
- an editor for stages and tasks and for reward lists;
- a review panel with every pending change, undo, and *Save & apply*, *Save only*, or *Apply
  without saving*;
- an overview of the server software and its capabilities, the current run, and the last
  configuration upgrade;
- a built-in guide.

Changes are applied safely: settings that change the rules of a running run are saved but wait
until the run ends, and startup settings such as the editor port wait for `/run reload` or a
restart. If the file was changed elsewhere in the meantime, the editor asks you to reload
instead of overwriting it.

### config.yml

`plugins/Speedrun/config.yml` is fully commented. After editing it by hand, run `/run reload`.
The most important settings:

| Setting | Default | Meaning |
| --- | --- | --- |
| `settings.gamemode` | `NORMAL` | `NORMAL`, `CASUAL`, or `HARDCORE`. |
| `settings.language` | `en` | `en`, `uk`, `de`, `es`, `fr`, `pt_BR`, `pl`, `zh_CN`. |
| `settings.start-on-first-join` | `true` | Start the timer with the first join. |
| `settings.pause-when-empty` | `false` | Pause while nobody is online. |
| `settings.resume-after-restart` | `true` | Continue an active run after a server restart or crash. |
| `settings.task-tracking-mode` | `INVENTORY` | Count carried items or everything collected (`CUMULATIVE`). |
| `records.enabled` | `true` | Store personal bests and splits. |
| `casual.start-pre-scan.mode` | `BALANCED` | Background structure search: `SAFE`, `BALANCED`, `AGGRESSIVE`, `LOCATE`. |
| `web-config.enabled` / `bind` / `port` | `true` / `127.0.0.1` / `8765` | Browser editor. |

See [docs/configuration.md](docs/configuration.md) for the complete reference.

### Tasks and stages

Stages under `progression` are completed in the order of their keys (`1_…`, `2_…`, …). Each task
is a Minecraft item name with an amount, or a structure:

```yaml
progression:
  2_IRON_AGE:
    display-name: "Iron Age"
    world: "NORMAL"            # where the tasks are shown: NORMAL, NETHER, THE_END
    tasks:
      IRON_INGOT: { amount: 7, display-name: "Iron Ingots", srbp: false }
      BREAD: { amount: 16, display-name: "Cooked Food", srbp: true }   # srbp: scale by player count
      STRUCTURE_VILLAGE: { display-name: "Find a Village" }
```

`OAK_LOG` counts every log and wood type, `BREAD` every cooked food. Structure tasks:
`STRUCTURE_LAVA_POOL`, `STRUCTURE_VILLAGE`, `STRUCTURE_NETHER_PORTAL`, `STRUCTURE_FORTRESS`,
`STRUCTURE_BASTION`, `STRUCTURE_END_PORTAL`.

### Messages and languages

Message files are extracted to `plugins/Speedrun/lang/`. Edit them freely: customized texts are
never overwritten, and when an update adds new messages they are appended to your file (the
previous version is kept in `lang-backups/`). Both `&` color codes and MiniMessage tags such as
`<green>` or `<#ff8800>` work.

### Rewards

`rewards.on-task-complete` and `rewards.on-stage-complete` contain lines such as
`api:sound minecraft:entity.player.levelup 1.0 1.5` or `api:particle minecraft:heart 10`.
Any other line runs as a console command with `%player%` replaced. For safety, console
command rewards can only be added in `config.yml`, not from the browser editor (unless
`web-config.allow-console-reward-commands` is enabled in the file).

## Performance

- Scans of world data run on background threads using immutable chunk snapshots; the server
  thread only captures snapshots and applies results.
- On Paper, missing chunks are loaded asynchronously (several at once). On Spigot and
  CraftBukkit at most one chunk per tick is loaded, and the background search only loads
  missing chunks if `casual.start-pre-scan.allow-sync-chunk-loading` is enabled.
- Village detection uses block-entity lists instead of scanning every block; all queues and
  worker pools are bounded; run logs are written by a background writer.
- Gold highlighting uses glowing block displays (no hitbox, never saved with the world) and is
  capped at 256 blocks.
- Light beams and compass lodestones are the only blocks the plugin places. They are protected
  against mining, explosions, and pistons, are removed on reset, stop, and shutdown, and are
  recorded in `world-changes.yml` so they are removed on the next start even after a crash.

## Security

- The editor listens on `127.0.0.1` only and every API request needs a random key that changes
  on every start (`/run webconfig restart` creates a new one). The key travels in the link's
  `#fragment`, so it never appears in HTTP requests, web request logs, or referrers.
- Host-header checks block DNS-rebinding attacks; repeated wrong keys are throttled; responses
  carry a strict Content Security Policy.
- The editor validates every value, never runs commands, and writes `config.yml` atomically.
- Share the link with nobody. A link requested from the server console is also written to the
  server log; run `/run webconfig restart` before sharing logs. For remote access prefer an SSH
  tunnel to changing `web-config.bind`.

## Files created by the plugin

| Path in `plugins/Speedrun/` | Content |
| --- | --- |
| `config.yml` | Configuration. |
| `config-backups/` | Previous configuration files and `migration-report.txt`. |
| `lang/`, `lang-backups/` | Message files and their backups. |
| `records.yml` | Personal bests and splits. |
| `run-state.yml` | The active run, saved every 30 seconds and on shutdown (`settings.resume-after-restart`). |
| `world-changes.yml` | Temporary blocks (light beams, compass lodestones) to undo after a crash. Empty or absent normally. |
| `logs/` | Text and JSON log of every run (`settings.log-attempts`). |
| `traces/` | Optional diagnostics (`diagnostics.trace.enabled`). |

## Building from source

```bash
cd speedrun
./gradlew build
```

The jar is written to `speedrun/build/libs/speedrun-26.3-4.1.0-BETA.jar`. The build compiles the
common code against the Spigot API, the Paper adapter (`src/paper/java`) against the Paper API,
shades and relocates Adventure and JSON-java, and runs the unit tests. Browser editor tests:

```bash
node --test speedrun/scripts/web-editor-test.cjs
```

`speedrun/scripts/server-smoke-test.ps1` starts real servers in isolated folders under
`speedrun/build/`, checks startup, the protected web API, console commands, and a clean shutdown
for every game mode. `speedrun/scripts/resume-smoke-test.ps1` boots a server twice and checks that
an active run is saved on shutdown and restored on the next start. Both need server jars you
provide and an accepted Minecraft EULA.

## Troubleshooting

- **The editor link does not open** – check `web-config.enabled`, make sure the port is free, and
  use `/run webconfig restart`.
- **A location is wrong** – stand at the right place and use `/run new <location>` or give coordinates.
- **No compass distance on CraftBukkit** – use Spigot or Paper for the full feature set.
- **A run did not come back after a restart** – the console says why (for example a changed world,
  game mode, or stage setup). Runs stopped with `/run stop` or `/run reset` are not kept.
- **A new run does not start when players join** – after a finished or stopped run, start the next
  one with `/run start` or `/run reset`.
- **Something behaves strangely** – enable `diagnostics.trace.enabled`, reproduce the problem, and
  attach the newest file from `plugins/Speedrun/traces/` to your report.

## License

MIT, see [LICENSE.txt](LICENSE.txt). The plugin bundles
[Adventure](https://github.com/KyoriPowered/adventure) (MIT) and
[JSON-java](https://github.com/stleary/JSON-java) (public domain); see
`META-INF/THIRD-PARTY-NOTICES.txt` in the jar.
