package ax.nk.noteblock.game.timeline;

import ax.nk.noteblock.game.GameController;
import ax.nk.noteblock.session.GameSession;
import ax.nk.noteblock.game.timeline.playback.PlaybackEngine;
import ax.nk.noteblock.game.timeline.render.OverlayRenderer;
import ax.nk.noteblock.game.timeline.render.TrackRenderer;
import ax.nk.noteblock.game.timeline.ui.ControlItems;
import ax.nk.noteblock.game.timeline.ui.LibraryMenus;
import ax.nk.noteblock.game.timeline.ui.SettingsMenus;
import ax.nk.noteblock.game.timeline.ui.ChatPrompt;
import ax.nk.noteblock.game.timeline.ui.TextPrompt;
import ax.nk.noteblock.game.timeline.util.TimelineMath;
import ax.nk.noteblock.game.timeline.world.FreezeTimeService;
import ax.nk.noteblock.game.timeline.world.WorldRules;
import ax.nk.noteblock.game.timeline.score.TimelineScore;
import ax.nk.noteblock.game.timeline.edit.TimelineEditor;
import ax.nk.noteblock.game.timeline.input.TrackTargeting;
import ax.nk.noteblock.game.timeline.input.TimelineInputHandler;
import ax.nk.noteblock.persistence.SongRepository;
import ax.nk.noteblock.persistence.TimelineScoreJson;
import ax.nk.noteblock.game.timeline.ui.SongBrowserMenus;
import ax.nk.noteblock.game.timeline.ui.DeleteConfirmMenu;
import ax.nk.noteblock.persistence.SongDataRow;
import ax.nk.noteblock.persistence.SongRow;
import com.google.gson.Gson;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Session-local controller that:
 * - builds a 25 (Z) x 100 (X) track in the player's void world
 * - gives 16 instrument blocks + a Start item
 * - lets player place notes onto the track
 * - plays back notes across the X axis (time)
 */
public final class TimelineController implements GameController, Listener {

    // Track geometry: X = time, Z = pitch row
    private static final int DEFAULT_TRACK_TIME_LENGTH = 100;

    private final SongBrowserMenus songBrowserMenus;
    private final DeleteConfirmMenu deleteConfirmMenu = new DeleteConfirmMenu();

    private int loadPageIndex = 0;
    private SongBrowserMenus.Mode loadMode = SongBrowserMenus.Mode.LOAD;

    private static final Gson GSON = new Gson();
    private static final int MAX_TRACK_TIME_LENGTH = 1000;
    private static final int MIN_TRACK_TIME_LENGTH = 1;
    private static final int TRACK_PITCH_WIDTH = 25;

    // World coords (relative to world spawn); keep it simple and consistent
    private static final int BASE_Y = 64;
    private static final int TRACK_Y = BASE_Y + 1;
    private static final Vector ORIGIN = new Vector(0, TRACK_Y, 0);

    // Playback
    private static final long TICKS_PER_STEP = 2L; // 10 steps/sec at 20tps

    private static final int MIN_TICKS_PER_STEP = 1;
    private static final int MAX_TICKS_PER_STEP = 20;

    private static final double EDIT_RAY_DISTANCE = 30.0;

    // tempo is in server ticks per step
    private int ticksPerStep = (int) TICKS_PER_STEP;

    // Track length (X axis)
    private int trackLength = DEFAULT_TRACK_TIME_LENGTH;

    // Playback range selection (inclusive indices, null = unset)
    private Integer rangeBeginIndex;
    private Integer rangeEndIndex;

    // restore state when the session ends
    private boolean previousAllowFlight;
    private boolean previousFlying;
    private float previousFlySpeed;

    private final TimelineScore score = new TimelineScore(LAYER_COUNT);

    private int activeLayerIndex = 0;
    private int layerCount = 1;

    private static final boolean DEBUG_INPUT = false;

    private static final int LAYER_COUNT = 4;

    private static int layerY(int layerIndex) {
        return TRACK_Y + layerIndex;
    }

    private final Plugin plugin;

    private GameSession session;
    private Player player;

    private boolean loopEnabled = false;

    private final ControlItems controlItems;
    private final SettingsMenus settingsMenus = new SettingsMenus();
    private final FreezeTimeService freezeTimeService;

    private final TrackRenderer trackRenderer = new TrackRenderer(BASE_Y, TRACK_Y, LAYER_COUNT, TRACK_PITCH_WIDTH, ORIGIN.getBlockX(), ORIGIN.getBlockZ());
    private final OverlayRenderer overlayRenderer = new OverlayRenderer(ORIGIN.getBlockX(), ORIGIN.getBlockZ(), TRACK_PITCH_WIDTH);
    private final PlaybackEngine playback;

    private final TimelineEditor editor = new TimelineEditor(TRACK_Y, LAYER_COUNT, ORIGIN.getBlockX(), ORIGIN.getBlockZ(), TRACK_PITCH_WIDTH);
    private final TrackTargeting targeting = new TrackTargeting(ORIGIN.getBlockX(), ORIGIN.getBlockZ(), TRACK_PITCH_WIDTH, EDIT_RAY_DISTANCE);

    private TimelineInputHandler inputHandler;

    private final LibraryMenus libraryMenus = new LibraryMenus();
    private final SongRepository songRepository;
    private final ChatPrompt chatPrompt;
    private final TextPrompt textPrompt;

    public TimelineController(Plugin plugin, SongRepository songRepository, ChatPrompt chatPrompt, TextPrompt textPrompt) {
        this.plugin = plugin;
        this.controlItems = new ControlItems(plugin);
        this.freezeTimeService = new FreezeTimeService(plugin, () -> this.session == null ? null : this.session.world());
        this.playback = new PlaybackEngine(plugin, (int) TICKS_PER_STEP, false);
        this.songRepository = Objects.requireNonNull(songRepository);
        this.chatPrompt = Objects.requireNonNull(chatPrompt);
        this.textPrompt = Objects.requireNonNull(textPrompt);
        this.songBrowserMenus = new SongBrowserMenus(plugin);
    }

    @Override
    public void onStart(GameSession session, Player player) {
        this.session = session;
        this.player = player;

        // Register input handler (all listeners live there now)
        inputHandler = new TimelineInputHandler(
                controlItems,
                settingsMenus,
                editor,
                score,
                targeting,
                () -> this.player,
                () -> this.session == null ? null : this.session.world(),
                () -> this.trackLength,
                () -> layerY(activeLayerIndex),
                () -> this.activeLayerIndex,
                this::togglePlayback,
                this::setRangeBegin,
                this::setRangeEnd,
                this::resetRange,
                () -> {
                    activeLayerIndex = (activeLayerIndex + 1) % Math.max(1, layerCount);
                    player.sendActionBar(ChatColor.YELLOW + "Layer: " + (activeLayerIndex + 1) + "/" + layerCount);
                    redrawRangeOverlay();
                },
                () -> {
                    // Shift action on layer tool
                    if (activeLayerIndex >= layerCount) activeLayerIndex = 0;
                    if (score.isLayerEmpty(activeLayerIndex) && layerCount > 1) {
                        removeLayer(activeLayerIndex);
                    } else if (layerCount < LAYER_COUNT) {
                        layerCount++;
                        player.sendActionBar(ChatColor.YELLOW + "Layers: " + layerCount + " (active " + (activeLayerIndex + 1) + ")");
                    } else {
                        player.sendActionBar(ChatColor.RED + "Max layers reached (" + LAYER_COUNT + ")");
                    }
                    redrawRangeOverlay();
                },
                this::settingsCallbacks,
                this::libraryCallbacks,
                libraryMenus,
                this::songBrowserCallbacks,
                songBrowserMenus,
                this::deleteConfirmCallbacks,
                deleteConfirmMenu,
                () -> DEBUG_INPUT
        );
        Bukkit.getPluginManager().registerEvents(inputHandler, plugin);

        WorldRules.applyWorldRules(session.world());
        applySafeFlight(player);
        freezeTimeService.start(session.world());
        buildTrack(session.world());

        // Put the player into a sensible viewing position once (track rebuilds won't move them).
        final Location view = new Location(session.world(),
                ORIGIN.getBlockX() + 1.5,
                TRACK_Y + 1.0,
                ORIGIN.getBlockZ() + (TRACK_PITCH_WIDTH / 2.0) + 0.5,
                -90f,
                20f);
        player.teleportAsync(view);

        controlItems.giveItems(player);

        // Make sure hunger is full when entering.
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setHealth(player.getMaxHealth());

        player.sendMessage(ChatColor.GREEN + "Timeline ready. Place instrument blocks on the track, then right-click the Start item.");

        // Reset controller state for a clean session.
        ticksPerStep = (int) TICKS_PER_STEP;
        playback.setTicksPerStep(ticksPerStep);
        trackLength = DEFAULT_TRACK_TIME_LENGTH;
        rangeBeginIndex = null;
        rangeEndIndex = null;
        settingsMenus.invalidate();
        libraryMenus.invalidate();
        songBrowserMenus.invalidate();
        overlayRenderer.clearPlayhead(session.world());
        overlayRenderer.clearRange(session.world());
        layerCount = 1;
        activeLayerIndex = 0;
        loopEnabled = false;
        playback.setLoopEnabled(false);
        playback.setPlayhead(0);

        deleteConfirmMenu.invalidate();
        loadPageIndex = 0;
        loadMode = SongBrowserMenus.Mode.LOAD;

        score.clear();
    }

    @Override
    public void onStop(GameSession session) {
        playback.stop(() -> overlayRenderer.clearPlayhead(this.session == null ? null : this.session.world()));
        overlayRenderer.clearPlayhead(this.session == null ? null : this.session.world());
        overlayRenderer.clearRange(this.session == null ? null : this.session.world());
        if (player != null) {
            restoreFlight(player);
        }
        if (inputHandler != null) {
            HandlerList.unregisterAll(inputHandler);
            inputHandler = null;
        }
        // And unregister controller listener, since we no longer handle events here.
        HandlerList.unregisterAll(this);
        score.clear();
        freezeTimeService.stop();
    }

    // --- Playback

    private void togglePlayback() {
        if (!isInSessionWorld(player.getWorld())) {
            player.sendMessage(ChatColor.RED + "You can only start playback in your session world.");
            return;
        }

        if (playback.isPlaying()) {
            playback.stop(() -> overlayRenderer.clearPlayhead(session.world()));
            player.sendMessage(ChatColor.YELLOW + "Playback stopped.");
            playback.setPlayhead(playback.playbackStartIndex(trackLength, rangeBeginIndex, rangeEndIndex));
            return;
        }

        playback.setTicksPerStep(ticksPerStep);
        playback.setLoopEnabled(loopEnabled);
        playback.start(
                player,
                session.world(),
                trackLength,
                rangeBeginIndex,
                rangeEndIndex,
                layerCount,
                score.scoreByLayerMutable(),
                (idx) -> overlayRenderer.drawPlayhead(session.world(), idx, layerY(activeLayerIndex)),
                () -> overlayRenderer.clearPlayhead(session.world()),
                TRACK_PITCH_WIDTH,
                editor::pitchFromRow,
                null
        );
        player.sendMessage(ChatColor.AQUA + "Playback started.");
    }

    private void setRangeBegin(int idx) {
        rangeBeginIndex = idx;
        overlayRenderer.redrawRange(session.world(), rangeBeginIndex, rangeEndIndex, trackLength, layerY(activeLayerIndex));

        if (playback.isPlaying()) {
            if (loopEnabled) {
                // Restart playback so the scheduler captures the updated range values.
                playback.setPlayhead(playback.playbackStartIndex(trackLength, rangeBeginIndex, rangeEndIndex));
                restartPlayback();
            }
        }
    }

    private void setRangeEnd(int idx) {
        rangeEndIndex = idx;
        overlayRenderer.redrawRange(session.world(), rangeBeginIndex, rangeEndIndex, trackLength, layerY(activeLayerIndex));

        if (playback.isPlaying()) {
            if (loopEnabled) {
                playback.setPlayhead(playback.playbackStartIndex(trackLength, rangeBeginIndex, rangeEndIndex));
                restartPlayback();
            }
        }
    }

    private void resetRange() {
        rangeBeginIndex = null;
        rangeEndIndex = null;
        overlayRenderer.redrawRange(session.world(), rangeBeginIndex, rangeEndIndex, trackLength, layerY(activeLayerIndex));

        if (playback.isPlaying()) {
            if (loopEnabled) {
                playback.setPlayhead(playback.playbackStartIndex(trackLength, rangeBeginIndex, rangeEndIndex));
                restartPlayback();
            }
        } else {
            // Next play should start from the beginning of the full timeline.
            playback.setPlayhead(playback.playbackStartIndex(trackLength, rangeBeginIndex, rangeEndIndex));
        }
    }

    private void restartPlayback() {
        if (!playback.isPlaying()) return;
        playback.setTicksPerStep(ticksPerStep);
        playback.setLoopEnabled(loopEnabled);
        playback.start(
                player,
                session.world(),
                trackLength,
                rangeBeginIndex,
                rangeEndIndex,
                layerCount,
                score.scoreByLayerMutable(),
                (idx) -> overlayRenderer.drawPlayhead(session.world(), idx, layerY(activeLayerIndex)),
                () -> overlayRenderer.clearPlayhead(session.world()),
                TRACK_PITCH_WIDTH,
                editor::pitchFromRow,
                null
        );
    }

    private SettingsMenus.Callbacks settingsCallbacks() {
        return new SettingsMenus.Callbacks() {
            @Override
            public void openTempo() {
                settingsMenus.openTempo(TimelineController.this.player, this);
            }

            @Override
            public void openLength() {
                settingsMenus.openLength(TimelineController.this.player, this);
            }

            @Override
            public void close() {
                TimelineController.this.player.closeInventory();
            }

            @Override
            public void setLoopEnabled(boolean enabled) {
                loopEnabled = enabled;
                playback.setLoopEnabled(enabled);

                if (!enabled && playback.isPlaying()) {
                    // Ensures that disabling loop during playback takes effect immediately.
                    restartPlayback();
                }
            }

            @Override
            public void setTicksPerStep(int newValue) {
                TimelineController.this.setTicksPerStep(TimelineMath.clamp(newValue, MIN_TICKS_PER_STEP, MAX_TICKS_PER_STEP));
            }

            @Override
            public void adjustTrackLength(int delta) {
                TimelineController.this.adjustTrackLength(delta);
            }

            @Override
            public int ticksPerStep() {
                return TimelineController.this.ticksPerStep;
            }

            @Override
            public int trackLength() {
                return TimelineController.this.trackLength;
            }

            @Override
            public int maxTrackLength() {
                return MAX_TRACK_TIME_LENGTH;
            }

            @Override
            public int minTrackLength() {
                return MIN_TRACK_TIME_LENGTH;
            }

            @Override
            public boolean loopEnabled() {
                return loopEnabled;
            }

            @Override
            public boolean isPlaying() {
                return playback.isPlaying();
            }

            @Override
            public void resetPlayheadToRangeStartIfLooping() {
                if (playback.isPlaying() && loopEnabled) {
                    playback.setPlayhead(playback.playbackStartIndex(trackLength, rangeBeginIndex, rangeEndIndex));
                }
            }
        };
    }

    private void setTicksPerStep(int newValue) {
        if (newValue == ticksPerStep) return;
        ticksPerStep = newValue;
        playback.setTicksPerStep(newValue);
        player.sendMessage(ChatColor.GRAY + "Tempo set to " + ticksPerStep + " ticks/step");

        // Restart playback with new tempo.
        if (playback.isPlaying()) {
            playback.start(
                    player,
                    session.world(),
                    trackLength,
                    rangeBeginIndex,
                    rangeEndIndex,
                    layerCount,
                    score.scoreByLayerMutable(),
                    (idx) -> overlayRenderer.drawPlayhead(session.world(), idx, layerY(activeLayerIndex)),
                    () -> overlayRenderer.clearPlayhead(session.world()),
                    TRACK_PITCH_WIDTH,
                    editor::pitchFromRow,
                    null
            );
        }
    }

    private void adjustTrackLength(int delta) {
        final int oldLength = trackLength;
        final int newLength = TimelineMath.clamp(trackLength + delta, MIN_TRACK_TIME_LENGTH, MAX_TRACK_TIME_LENGTH);
        if (newLength == trackLength) {
            if (delta > 0) {
                player.sendActionBar(ChatColor.RED + "Max track length is " + MAX_TRACK_TIME_LENGTH);
            } else {
                player.sendActionBar(ChatColor.RED + "Min track length is " + MIN_TRACK_TIME_LENGTH);
            }
            return;
        }

        // If shrinking, drop out-of-bounds notes so they can't keep playing.
        if (newLength < oldLength) {
            pruneNotesOutsideLength(newLength);
            trackRenderer.clearWorldColumnsOutsideLength(session.world(), newLength, oldLength);
            overlayRenderer.clearPlayhead(session.world());
            overlayRenderer.clearRange(session.world());
        }

        trackLength = newLength;

        // Keep range markers inside the track.
        if (rangeBeginIndex != null && rangeBeginIndex >= trackLength) rangeBeginIndex = trackLength - 1;
        if (rangeEndIndex != null && rangeEndIndex >= trackLength) rangeEndIndex = trackLength - 1;
        if (trackLength <= 0) {
            rangeBeginIndex = null;
            rangeEndIndex = null;
        }

        player.sendMessage(ChatColor.GRAY + "Track length set to " + trackLength);

        trackRenderer.buildTrack(session.world(), player, trackLength);
        overlayRenderer.redrawRange(session.world(), rangeBeginIndex, rangeEndIndex, trackLength, layerY(activeLayerIndex));
        trackRenderer.redrawNotes(session.world(), trackLength, score.scoreByLayerMutable());

        if (playback.isPlaying()) {
            playback.start(
                    player,
                    session.world(),
                    trackLength,
                    rangeBeginIndex,
                    rangeEndIndex,
                    layerCount,
                    score.scoreByLayerMutable(),
                    (idx) -> overlayRenderer.drawPlayhead(session.world(), idx, layerY(activeLayerIndex)),
                    () -> overlayRenderer.clearPlayhead(session.world()),
                    TRACK_PITCH_WIDTH,
                    editor::pitchFromRow,
                    null
            );
        }
    }

    private void buildTrack(World world) {
        trackRenderer.buildTrack(world, player, trackLength);
        overlayRenderer.redrawRange(world, rangeBeginIndex, rangeEndIndex, trackLength, layerY(activeLayerIndex));
        trackRenderer.redrawNotes(world, trackLength, score.scoreByLayerMutable());
    }

    // --- Records

    private void pruneNotesOutsideLength(int newLength) {
        score.pruneNotesOutsideLength(newLength, ORIGIN.getBlockX());
    }

    private void applySafeFlight(Player player) {
        previousAllowFlight = player.getAllowFlight();
        previousFlying = player.isFlying();
        previousFlySpeed = player.getFlySpeed();

        player.setAllowFlight(true);
        player.setFlying(true);
        player.setFlySpeed(0.08f);
    }

    private void restoreFlight(Player player) {
        player.setFlySpeed(previousFlySpeed);
        player.setAllowFlight(previousAllowFlight);
        player.setFlying(previousFlying);
    }

    private boolean isInSessionWorld(World world) {
        return session != null && session.world() != null && session.world().equals(world);
    }

    private void removeLayer(int layerIndex) {
        // Only allow removing an empty layer.
        if (!score.isLayerEmpty(layerIndex)) {
            player.sendActionBar(ChatColor.RED + "Layer isn't empty.");
            return;
        }

        if (layerCount <= 1) {
            player.sendActionBar(ChatColor.RED + "Can't remove the last layer.");
            return;
        }

        // Clear any blocks on that plane just in case (should be empty).
        final World w = session.world();
        final int y = layerY(layerIndex);
        for (int dx = 0; dx < trackLength; dx++) {
            for (int dz = 0; dz < TRACK_PITCH_WIDTH; dz++) {
                w.getBlockAt(ORIGIN.getBlockX() + dx, y, ORIGIN.getBlockZ() + dz).setType(Material.AIR, false);
            }
        }

        // We don’t shift layers in the data model; just reduce the active layer count.
        if (layerIndex == layerCount - 1) {
            layerCount--;
        } else {
            layerCount--; // remove a middle layer conceptually
        }

        if (activeLayerIndex >= layerCount) activeLayerIndex = layerCount - 1;
        player.sendActionBar(ChatColor.YELLOW + "Layers: " + layerCount + " (active " + (activeLayerIndex + 1) + ")");
        redrawRangeOverlay();
    }

    private void redrawRangeOverlay() {
        overlayRenderer.redrawRange(session.world(), rangeBeginIndex, rangeEndIndex, trackLength, layerY(activeLayerIndex));
        if (playback.isPlaying() && loopEnabled) {
            playback.setPlayhead(playback.playbackStartIndex(trackLength, rangeBeginIndex, rangeEndIndex));
            restartPlayback();
        }
    }

    private LibraryMenus.Callbacks libraryCallbacks() {
        return new LibraryMenus.Callbacks() {
            @Override
            public void saveSong() {
                TimelineController.this.player.playSound(TimelineController.this.player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.6f, 1.2f);
                TimelineController.this.openSaveSongPrompt();
            }

            @Override
            public void loadSong() {
                TimelineController.this.player.playSound(TimelineController.this.player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.6f, 1.2f);
                TimelineController.this.openLoadSongBrowser();
            }

            @Override
            public void close() {
                TimelineController.this.player.playSound(TimelineController.this.player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.5f, 0.8f);
                TimelineController.this.player.closeInventory();
            }
        };
    }

    private void openSaveSongPrompt() {
        if (player == null || session == null || session.world() == null) return;

        textPrompt.promptSongName(player, (rawName) -> {
            final String name = sanitizeSongName(rawName);
            if (name.isBlank()) {
                player.sendMessage(ChatColor.RED + "Song name can't be empty.");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.6f, 1.0f);
                return;
            }

            final String json = TimelineScoreJson.toJson(score, trackLength, ticksPerStep, layerCount);

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    songRepository.insertSong(player.getUniqueId(), name, json);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.sendMessage(ChatColor.GREEN + "Saved song as '" + name + "'.");
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 0.5f, 1.5f);
                    });
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to save song: " + e.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.sendMessage(ChatColor.RED + "Failed to save song: " + e.getMessage());
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.6f, 1.0f);
                    });
                }
            });
        });
    }

    private static String sanitizeSongName(String in) {
        if (in == null) return "";
        String s = ChatColor.stripColor(in);
        if (s == null) s = "";
        s = s.trim();
        if (s.length() > 32) s = s.substring(0, 32);
        // Disallow newlines/control chars
        s = s.replaceAll("[\\r\\n\\t]", " ").trim();
        return s;
    }

    private SongBrowserMenus.Callbacks songBrowserCallbacks() {
        return new SongBrowserMenus.Callbacks() {
            @Override
            public void back() {
                TimelineController.this.player.playSound(TimelineController.this.player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.5f, 0.9f);
                libraryMenus.openMain(TimelineController.this.player, libraryCallbacks());
            }

            @Override
            public void close() {
                TimelineController.this.player.playSound(TimelineController.this.player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.5f, 0.8f);
                TimelineController.this.player.closeInventory();
            }

            @Override
            public void prevPage(int newPage) {
                loadPageIndex = Math.max(0, newPage);
                openLoadSongBrowser(loadPageIndex, loadMode);
            }

            @Override
            public void nextPage(int newPage) {
                loadPageIndex = Math.max(0, newPage);
                openLoadSongBrowser(loadPageIndex, loadMode);
            }

            @Override
            public void toggleDeleteMode(boolean enabled) {
                loadMode = enabled ? SongBrowserMenus.Mode.DELETE : SongBrowserMenus.Mode.LOAD;
                openLoadSongBrowser(loadPageIndex, loadMode);
            }

            @Override
            public void loadSong(long songId) {
                TimelineController.this.player.playSound(TimelineController.this.player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.6f, 1.3f);
                TimelineController.this.loadSongById(songId);
            }

            @Override
            public void requestDelete(long songId, String songName) {
                TimelineController.this.player.playSound(TimelineController.this.player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.6f, 1.1f);
                deleteConfirmMenu.open(TimelineController.this.player, songId, songName);
            }
        };
    }

    private void openLoadSongBrowser() {
        openLoadSongBrowser(0, SongBrowserMenus.Mode.LOAD);
    }

    private void openLoadSongBrowser(int pageIndex, SongBrowserMenus.Mode mode) {
        if (player == null) return;
        loadPageIndex = Math.max(0, pageIndex);
        loadMode = mode == null ? SongBrowserMenus.Mode.LOAD : mode;

        songBrowserMenus.open(player);
        player.sendActionBar(ChatColor.GRAY + "Loading songs...");

        final int pageSize = 45;
        final int offset = loadPageIndex * pageSize;

        // Fetch one extra to know if there's next page.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                final List<SongRow> songsPlus = songRepository.listSongs(player.getUniqueId(), pageSize + 1, offset);
                final boolean hasNext = songsPlus.size() > pageSize;
                final List<SongRow> songs = hasNext ? songsPlus.subList(0, pageSize) : songsPlus;
                final boolean hasPrev = loadPageIndex > 0;

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player == null) return;
                    songBrowserMenus.render(songs, loadPageIndex, hasPrev, hasNext, loadMode);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 0.25f, 1.4f);
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player == null) return;
                    player.sendMessage(ChatColor.RED + "Failed to load song list: " + e.getMessage());
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.6f, 1.0f);
                    libraryMenus.openMain(player, libraryCallbacks());
                });
            }
        });
    }

    private void loadSongById(long songId) {
        if (player == null) return;

        player.sendActionBar(ChatColor.GRAY + "Loading song...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                final SongDataRow row = songRepository.getSongById(player.getUniqueId(), songId);
                if (row == null) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player == null) return;
                        player.sendMessage(ChatColor.RED + "Song not found.");
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.6f, 1.0f);
                    });
                    return;
                }

                final TimelineScoreJson.SongPayload payload = GSON.fromJson(row.dataJson(), TimelineScoreJson.SongPayload.class);

                Bukkit.getScheduler().runTask(plugin, () -> applyLoadedSong(row.name(), payload));
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player == null) return;
                    player.sendMessage(ChatColor.RED + "Failed to load song: " + e.getMessage());
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.6f, 1.0f);
                });
            }
        });
    }

    private void applyLoadedSong(String name, TimelineScoreJson.SongPayload payload) {
        if (player == null || session == null || session.world() == null) return;

        // Stop playback
        if (playback.isPlaying()) {
            playback.stop(() -> overlayRenderer.clearPlayhead(session.world()));
        }
        overlayRenderer.clearPlayhead(session.world());
        overlayRenderer.clearRange(session.world());
        rangeBeginIndex = null;
        rangeEndIndex = null;

        // Reset and rebuild track if length differs
        final int newTrackLength = payload == null ? DEFAULT_TRACK_TIME_LENGTH : payload.trackLength();
        final int newTicks = payload == null ? (int) TICKS_PER_STEP : payload.ticksPerStep();
        final int newLayerCount = payload == null ? 1 : Math.max(1, Math.min(LAYER_COUNT, payload.layerCount()));

        ticksPerStep = TimelineMath.clamp(newTicks, MIN_TICKS_PER_STEP, MAX_TICKS_PER_STEP);
        playback.setTicksPerStep(ticksPerStep);

        trackLength = TimelineMath.clamp(newTrackLength, MIN_TRACK_TIME_LENGTH, MAX_TRACK_TIME_LENGTH);

        layerCount = newLayerCount;
        activeLayerIndex = Math.min(activeLayerIndex, layerCount - 1);

        // Wipe note blocks by rebuilding the track
        trackRenderer.buildTrack(session.world(), player, trackLength);

        // Clear and apply notes to score
        score.clear();
        if (payload != null && payload.layers() != null) {
            final int maxLayerToRead = Math.min(layerCount, payload.layers().size());
            for (int layer = 0; layer < maxLayerToRead; layer++) {
                final List<TimelineScoreJson.NoteDto> notes = payload.layers().get(layer);
                if (notes == null) continue;
                for (TimelineScoreJson.NoteDto n : notes) {
                    if (n == null) continue;
                    final int t = n.t();
                    if (t < 0 || t >= trackLength) continue;
                    final int pitch = n.p();
                    if (pitch < 0 || pitch >= TRACK_PITCH_WIDTH) continue;

                    final int x = ORIGIN.getBlockX() + t;
                    final int y = layerY(layer);
                    final int z = ORIGIN.getBlockZ() + pitch;

                    final BlockPos pos = new BlockPos(x, y, z);
                    score.upsertNote(new ax.nk.noteblock.game.timeline.score.TimelineCell(t, pitch), pos, n.i(), pitch, layer);
                }
            }
        }

        // Finally, redraw note blocks in the world
        trackRenderer.redrawNotes(session.world(), trackLength, score.scoreByLayerMutable());

        // UI feedback
        player.closeInventory();
        player.sendMessage(ChatColor.GREEN + "Loaded song '" + name + "'.");
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.MASTER, 0.6f, 1.2f);
    }

    private DeleteConfirmMenu.Callbacks deleteConfirmCallbacks() {
        return new DeleteConfirmMenu.Callbacks() {
            @Override
            public void confirmDelete(long songId) {
                TimelineController.this.player.sendActionBar(ChatColor.RED + "Deleting...");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        final boolean ok = songRepository.deleteSong(player.getUniqueId(), songId);
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (ok) {
                                player.sendMessage(ChatColor.GREEN + "Deleted.");
                                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.MASTER, 0.5f, 0.8f);
                            } else {
                                player.sendMessage(ChatColor.RED + "Couldn't delete (not found).");
                                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.6f, 1.0f);
                            }
                            openLoadSongBrowser(loadPageIndex, loadMode);
                        });
                    } catch (Exception e) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            player.sendMessage(ChatColor.RED + "Failed to delete: " + e.getMessage());
                            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.6f, 1.0f);
                            openLoadSongBrowser(loadPageIndex, loadMode);
                        });
                    }
                });
            }

            @Override
            public void cancel() {
                openLoadSongBrowser(loadPageIndex, loadMode);
            }
        };
    }
}
