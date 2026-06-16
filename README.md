# Goat: Advanced Utility & Automation Framework for Minecraft 1.21.11

Goat is a modular, high-performance utility client and automation framework built as a **Fabric mod** for **Minecraft 1.21.11**. It provides powerful automation scripts, a custom pathfinding engine, and a robust failsafe protection layer.

> [!WARNING]
> **Project Status: Work In Progress (WIP)**
> This codebase is under active development. Many features are incomplete, non-functional, or unstable. Specifically, the custom A* Pathfinder engine currently contains known bugs and optimization issues. 

> [!TIP]
> **Get Involved & Support**
> Join our [Discord Server](https://discord.gg/94KwynaSwZ) to discuss features, report issues, or contribute to development.

---

## Key Features

### 1. Custom GUI System
- **Dynamic Dashboard**: Fully drag-and-drop, resizable, and multi-categorical GUI dashboard.
- **Custom Typography**: Bypasses the default Minecraft pixelated font renderer by loading custom TrueType Fonts (`gui.ttf`) with disabled anti-aliasing for clean rendering at small scales.
- **Intuitive Controls**: Integrated sub-menus with support for toggle buttons, numerical sliders, mode selectors, and custom keybind assignment.

### 2. A* Pathfinder Engine
- **3D Collision Awareness**: Parses Minecraft's 3D bounding boxes (`VoxelShape`) to calculate optimal navigation paths.
- **Advanced Movement Nodes**: Resolves paths with horizontal traversing, ladder/stairs climbing, and safe fall damage thresholds (up to Y-2).
- **Smooth Rotation Interpolation**: Employs an interpolation layer during `END_CLIENT_TICK` to mimic natural client-side player camera rotations, ensuring full interpolation animations.
- **Stuck Recovery**: Dynamic stuck-detection triggers automated path recalculation if the player is obstructed for more than 2 seconds.
- **Native Particle ESP**: Renders paths using vanilla native particles (`HAPPY_VILLAGER`, `END_ROD`) instead of drawing custom OpenGL lines, bypassing typical render-hook scans.

### 3. Failsafe Manager (Safety Shield)
- **Multi-Vector Detection**: Actively monitors client and server states for anomaly vectors:
  - **Rotation Check**: Detects sudden, non-natural camera snaps (>45 degrees in a single tick) common in moderator checks.
  - **World & Server Swaps**: Intercepts dimension changes, server transfers, or lobby evacuations.
  - **Anomaly Monitoring**: Detects bad potion effects, cobwebs, inventory fullness, guest visits, slot/item changes, Jacob's events, and velocity/knockback fluctuations.
- **Emergency Dispatcher**: Instantly stops active macros, releases key states, executes custom escape procedures (e.g., evacuation commands), or disconnects the client.

### 4. SkyBlock & Automation Modules
- **Farming & Garden**:
  - **FarmingMacro**: Leverages the native `KeyBinding` API to simulate authentic WASD key presses, avoiding direct packet-level movements.
  - **Pest Cleaner**: Automates locating, navigating to, and eliminating crop pests.
  - **Visitors Macro**: Auto-interacts with Garden visitors to fulfill trades.
- **Mining & Commissions**:
  - **MiningBot / Ore & Gemstone Macros**: High-efficiency node mining with customizable target blocks.
  - **Commission Macro**: Automatically parses and completes Crystal Hollows or Dwarven Mines commission tasks.
  - **Powder & Nuker Macros**: Optimized templates for automated Mithril/Gemstone mining and chest parsing.
- **Puzzle Solver**:
  - **AutoExperiments**: Autonomously solves Chronomatron, Ultrasequencer, and Superpairs minigames at the Experimentation Table.
- **Movement & Utilities**:
  - **AutoSprint**: Features *Legit* (only sprint forward) and *Omni* (sprint in all directions) movement modes.
  - **ForagingMacro**: Fully automates tree farming and wood chopping.
  - **Render Toggles**: CustomFOV, FullBright, PestESP, and client-side TimeChanger.

---

## Getting Started

### Prerequisites
- **Java 21 Development Kit (JDK)**
- An active Minecraft launcher or development IDE (IntelliJ IDEA recommended)

### Build and Compilation
Use the Gradle wrapper to build the release jar:

**Windows (PowerShell):**
```powershell
.\gradlew.bat build
```

**macOS / Linux:**
```bash
./gradlew build
```

The compiled mod jar will be generated at:
```text
build/libs/goat-1.0.0.jar
```

### Running the Client in Development
To boot up Minecraft with the mod preloaded:

```powershell
.\gradlew.bat runClient
```

---

## Usage Controls

| Control | Description |
| :--- | :--- |
| **Right Shift** | Opens/closes the main Goat GUI dashboard. |
| **`/goat`** | Chat command to verify mod initialization status. |
| **`/goto <X> <Y> <Z>`** | Commands the A* Pathfinder to navigate to the specified coordinate. |

---

## Credits & Special Thanks
Special thanks to the following open-source projects for their inspiration and references:
- [V5](https://github.com/V5-Client/V5)
- [FarmHelper](https://github.com/JellyLabScripts/FarmHelper)

---

## Disclaimer & Terms
This project is developed for educational, modding, and automation research purposes. Please ensure compliance with local server rules and terms of service before using automation macros on public networks.
