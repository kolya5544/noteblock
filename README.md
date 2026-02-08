# noteblock (Paper plugin)

A Paper plugin that gives each joining player their own **timeline music sequencer**.

## What it does (current behavior)

- **On player join**
  - Creates (or reuses) a dedicated void world named `player_<uuid>`
  - Spawns a small **3×3 barrier platform** at Y=64 and teleports the player to Y=65
  - Starts the session controller (`TimelineController`) for that player
- **In the session world**
  - Builds a timeline track: **100 columns (time, X)** × **25 rows (pitch, Z)**
  - Supports up to **4 vertical layers** (Y planes). You can choose the active layer and optionally add/remove layers.
  - Gives the player:
    - **16 instrument tokens** (wool items)
    - **Layers tool**
    - **Settings** item (GUI)
    - **Start / Stop** item
- **On quit/kick (and on plugin disable)**
  - Stops playback and unregisters listeners
  - Unloads the player’s world
  - Deletes the world folder (with retries to tolerate transient Windows file locks)

No commands and no permissions are defined; everything is driven by join/quit and item interactions.

## Timeline track

- **X axis = time**: `0..99` (left → right)
- **Z axis = pitch row**: `0..24` (low → high)
- The track floor is built at **Y=64**, and notes are placed on layer planes starting at **Y=65**.
- Player viewing position is moved to a side-on camera angle after the track is built.

## Items & controls

### Instrument tokens (16)
- Each token represents a different noteblock instrument sound.
- You don’t “consume” tokens: the plugin keeps them effectively infinite (stack size forced back to 1).
- **Place a token on the track** to add/replace a note on the **active layer** at that (time, pitch).
- **Left-click** while looking at the track (up to ~30 blocks) to remove the targeted note.
- **Break** a placed marker block to remove the note (works on any layer).

### Start / Stop (Blaze Rod)
- **Right-click** to start playback.
- Right-click again to stop.

### Settings (Comparator)
- **Right-click** to open the Settings GUI.
- Currently available setting:
  - **Tempo** (server ticks per step), clamped to **1..20**.
  - Changing tempo while playing restarts the playback task at the new rate.

### Layers (Slime Ball)
- **Right-click**: cycle the active layer within the current enabled layer count.
- **Shift + Right-click**:
  - If the current layer is empty and you have more than 1 layer enabled → remove it
  - Otherwise, if below the max → add a layer

### Swap-hand layer cycling (F key)
- While holding an **instrument token** in your main hand, **swap-hand** cycles the active layer.

## Playback

- Playback steps from time `0` to `99`.
- Default tempo is **2 ticks/step** (10 steps/sec on a 20 TPS server).
- At each step, the controller plays all notes in that column across all enabled layers.
- The current column is highlighted on the active layer by temporarily overlaying **red stained glass** on empty cells.

## Session world rules

While in the session world, the controller applies “safe sandbox” rules:
- Difficulty: **Peaceful**
- Gamerules: no daylight/weather cycle, no mob spawning, keep inventory, no fall damage, immediate respawn, no natural regen
- The controller enables flight for the player during the session and restores previous flight settings on exit.

## Extending / replacing the minigame

The game logic is controlled by `ax.nk.noteblock.game.GameController`.

The plugin currently instantiates `TimelineController` via `TimelineControllerFactory` in `Noteblock#onEnable()`.
To replace the minigame, swap the factory used there.

## Build

- Java **21**

```powershell
./gradlew.bat build
```

Output jar:
- `build/libs/noteblock-<version>.jar`

## Run a test server (Gradle)

This project is configured with `xyz.jpenilla.run-paper`.

```powershell
./gradlew.bat runServer
```

## Server dependencies

- Paper server **1.21+** (plugin `api-version: 1.21`)
- Java **21** runtime

## Notes / quirks

- World creation/unload are performed on the main server thread (required by Bukkit/Paper).
- World deletion uses retries because Windows may keep region files locked briefly.
