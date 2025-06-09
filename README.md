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

## 🏗️ Updates and 🛠️ Future changes

**Soon**:
- Refactoring the project
- Fixing known bugs
- Explicit display of the pause
- move the timer to search for a village in the village coordinate field (with colour highlighting), format it to a compact view, the rest of the behaviour is preserved

**In the future**:
- Separation of task display depending on the world where the player is located
- Moving most entities to the configuration file for easy configuration
- Translation of the project into Ukrainian
- Creating a logical and detailed sequence of tasks to complete
- Progression of the number of resources required for the task, depending on the number of players
- Detailed logging of each speedrun attempt
- Rewards (not affecting fair play) for players for completing tasks and finding locations
- New commands that will expand the interaction with the plugin without leaving the game

---

---

## 🤝 Contribution

Feel free to open issues for bug reports or feature requests.  
Pull requests are welcome!

---

> Made with ❤️ by **deymon28** **and Nikita**
