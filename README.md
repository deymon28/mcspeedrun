# 🚀 Minecraft Speedrun Plugin

A comprehensive Minecraft plugin designed to enhance speedrun challenges on your server. It features a **dynamic timer**, tracks key in-game **locations**, and manages **item collection tasks**, all displayed on a user-friendly **scoreboard**.

---

## ✨ Features

### ⏱️ Dynamic Timer
A precise timer that starts automatically when the plugin is enabled and can be **paused or reset**.

### 📍 Location Tracking
Automatically detects and displays coordinates for crucial speedrun milestones:

- **Village**: Found by striking a bell within a set time limit.
- **Nether Portals**: Tracks both Overworld and Nether side portals.
- **Fortress**: Detected via the `nether/find_fortress` advancement.
- **Bastion**: Detected via the `nether/find_bastion` advancement.
- **End Portal**: Detected via the `story/follow_ender_eye` advancement.

### 📦 Persistent Item Tasks
Players must collect specific quantities of items. Progress is shown on the scoreboard, and completed tasks remain completed even if items are lost (e.g., upon death).

**Overworld Tasks:**
- Wood (various log types)
- Cobblestone
- Iron Ingots
- Cooked Food (various types)

**Nether Tasks:**
- Ender Pearls
- Blaze Rods

### 📋 Dynamic Scoreboard
A real-time sidebar scoreboard displays:

- Current speedrun time
- Status and coordinates of found locations
- Current progress for all active tasks

**Automatic Task Group Hiding:**
Once all Overworld tasks are completed, the "Overworld" task group is removed from the scoreboard to make room for Nether tasks.

### 🎮 Player-Friendly Interactions
Intuitive commands for control and an interactive way to mark milestones.

Automatic timer stop after killing Ender Dragon.

---

## 🛠️ Installation

1. Download the latest `.jar` file from the [Releases](../../releases) page.
2. Place the `Speedrun-1.0-SNAPSHOT.jar` file into your server’s `plugins/` directory.
3. Start or restart your Minecraft server.

*Tested only on Paper server for Minecraft 1.21.5*

---

## 🕹️ Commands

All commands are controlled via `/run` (or its aliases `/speedrun`, `/sr`):

- `/run pause` – Pauses or resumes the speedrun timer.
- `/run reset` – Resets the entire speedrun (timer, found locations, task progress).

---

## 🔑 Permissions

- `speedrun.control` – Allows players to use the `/run` commands (`pause`, `reset`).

**Default:** `op`

---

## ⚙️ Configuration

Currently, task requirements and timings are hardcoded.  
**Future versions** may include a `config.yml` for full customizability.

---

## 🏗️ Upcoming Tasks and 🔮 Future Plans

### Coming Soon:
- ~~Codebase refactoring and cleanup~~
- ~~Fix known bugs~~
- ~~Add explicit "paused" indicator on the scoreboard~~
- ~~Move village search timer into the village coordinate field with color highlighting and compact formatting~~

### Planned Features:
- ~~Start the timer when the first player joins, not on server start~~
- ~~Add a per-task resource tracking mode~~:
    - ~~Total collected over time~~
    - ~~Only current inventory amount~~
- ~~Display tasks dynamically based on the player's current world (Overworld, Nether, etc.)~~
- ~~Move most plugin settings to `config.yml` for easier customization~~
- ~~Add localization support, starting with a full Ukrainian translation~~
- Introduce a structured task progression path
- ~~Dynamically scale required resources based on player count~~
- Log each speedrun attempt with detailed data
- ~~Add optional rewards (cosmetic only) for task and milestone completion~~
- ~~Expand in-game commands to enhance interaction~~
- ~~Revise Nether portal coordinate detection to work without requiring player entry~~

---

---

## 🤝 Contribution

Feel free to open issues for bug reports or feature requests.  
Pull requests are welcome!

---

> Made with ❤️ by **deymon28** **and Nikita**
