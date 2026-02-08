package ax.nk.noteblock.game.timeline;

import ax.nk.noteblock.game.GameController;
import ax.nk.noteblock.session.GameSession;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.RayTraceResult;
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
    private static final int MAX_TRACK_TIME_LENGTH = 1000;
    private static final int MIN_TRACK_TIME_LENGTH = 1;
    private static final int TRACK_PITCH_WIDTH = 25;

    // World coords (relative to world spawn); keep it simple and consistent
    private static final int BASE_Y = 64;
    private static final int TRACK_Y = BASE_Y + 1;
    private static final Vector ORIGIN = new Vector(0, TRACK_Y, 0);

    // Playback
    private static final long TICKS_PER_STEP = 2L; // 10 steps/sec at 20tps

    private static final String TYPE_INSTRUMENT = "instrument";
    private static final String TYPE_START = "start";
    private static final String TYPE_SETTINGS = "settings";
    private static final String TYPE_LAYER_TOOL = "layer_tool";
    private static final String TYPE_RANGE_TOOL = "range_tool";

    private static final int INVENTORY_SLOT_START = 8;
    private static final int INVENTORY_SLOT_SETTINGS = 7;
    private static final int INVENTORY_SLOT_LAYER_TOOL = 6;
    private static final int INVENTORY_SLOT_RANGE_TOOL = 5;

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

    private BukkitTask playbackTask;
    private int playhead;

    // tickIndex -> list(note)
    private final List<Map<Integer, List<NoteEvent>>> scoreByLayer = new ArrayList<>();
    // block position -> tickIndex  (for removals)
    private final Map<BlockPos, NoteRef> refByPos = new HashMap<>();

    private int activeLayerIndex = 0;
    private int layerCount = 1;

    // playhead overlay
    private final Map<BlockPos, Material> playheadOverlay = new HashMap<>();

    // range overlay
    private final Map<BlockPos, Material> rangeOverlay = new HashMap<>();

    private Inventory settingsMainInventory;
    private Inventory settingsTempoInventory;
    private Inventory settingsLengthInventory;

    private static final String MENU_SETTINGS_MAIN_TITLE = "Settings";
    private static final String MENU_SETTINGS_TEMPO_TITLE = "Settings > Tempo";
    private static final String MENU_SETTINGS_LENGTH_TITLE = "Settings > Track Length";

    private static final int MAIN_TEMPO_BUTTON_SLOT = 13;
    private static final int MAIN_LENGTH_BUTTON_SLOT = 15;
    private static final int MAIN_CLOSE_SLOT = 31;
    private static final int MAIN_LOOP_BUTTON_SLOT = 11;

    private static final int TEMPO_DISPLAY_SLOT = 13;
    private static final int TEMPO_MINUS_SLOT = 20;
    private static final int TEMPO_PLUS_SLOT = 24;
    private static final int TEMPO_BACK_SLOT = 30;
    private static final int TEMPO_CLOSE_SLOT = 32;

    private static final int LENGTH_DISPLAY_SLOT = 13;
    private static final int LENGTH_PLUS_1_SLOT = 19;
    private static final int LENGTH_PLUS_10_SLOT = 20;
    private static final int LENGTH_PLUS_50_SLOT = 21;
    private static final int LENGTH_MINUS_1_SLOT = 25;
    private static final int LENGTH_MINUS_10_SLOT = 24;
    private static final int LENGTH_MINUS_50_SLOT = 23;
    private static final int LENGTH_BACK_SLOT = 30;
    private static final int LENGTH_CLOSE_SLOT = 32;

    private static final boolean DEBUG_INPUT = false;
    private long lastRightClickMs;

    private static final int LAYER_COUNT = 4;

    private static int layerY(int layerIndex) {
        return TRACK_Y + layerIndex;
    }

    private final Plugin plugin;

    private NamespacedKey keyType;
    private NamespacedKey keyInstrument;

    private GameSession session;
    private Player player;

    private boolean loopEnabled = false;

    public TimelineController(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onStart(GameSession session, Player player) {
        this.session = session;
        this.player = player;

        this.keyType = new NamespacedKey(plugin, "nb_type");
        this.keyInstrument = new NamespacedKey(plugin, "nb_instrument");

        Bukkit.getPluginManager().registerEvents(this, plugin);

        applyWorldRules(session.world());
        applySafeFlight(player);
        startFreezeTimeTask(session.world());
        buildTrack(session.world());
        giveItems(player);

        // Make sure hunger is full when entering.
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setHealth(player.getMaxHealth());

        player.sendMessage(ChatColor.GREEN + "Timeline ready. Place instrument blocks on the track, then right-click the Start item.");

        // Reset controller state for a clean session.
        ticksPerStep = (int) TICKS_PER_STEP;
        trackLength = DEFAULT_TRACK_TIME_LENGTH;
        rangeBeginIndex = null;
        rangeEndIndex = null;
        settingsMainInventory = null;
        settingsTempoInventory = null;
        settingsLengthInventory = null;
        playheadOverlay.clear();
        rangeOverlay.clear();
        layerCount = 1;
        activeLayerIndex = 0;
        loopEnabled = false;

        scoreByLayer.clear();
        ensureScoreInitialized();
        refByPos.clear();
    }

    @Override
    public void onStop(GameSession session) {
        stopPlayback();
        clearPlayhead();
        clearRangeOverlay();
        if (player != null) {
            restoreFlight(player);
        }
        HandlerList.unregisterAll(this);
        for (Map<Integer, List<NoteEvent>> m : scoreByLayer) m.clear();
        refByPos.clear();
        stopFreezeTimeTask();
    }

    // --- Events

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (!isSessionPlayer(event.getPlayer())) return;
        if (!isInSessionWorld(event.getBlock().getWorld())) return;

        final ItemStack inHand = event.getItemInHand();
        final Integer instrumentId = getInstrumentId(inHand);
        if (instrumentId == null) {
            // We don't give creative mode; allow regular blocks if they somehow get some.
            return;
        }

        // Force placement onto active layer (prevents frustration).
        final Location loc = event.getBlockPlaced().getLocation();
        final int x = loc.getBlockX();
        final int z = loc.getBlockZ();
        final int y = layerY(activeLayerIndex);

        final World w = loc.getWorld();
        final Block target = w.getBlockAt(x, y, z);
        final TrackCell cell = toCell(target.getLocation());
        if (cell == null) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Place notes on the track only.");
            return;
        }

        // Enforce marker block type (visual on the timeline)
        final Material marker = InstrumentPalette.byId(instrumentId).marker;
        target.setType(marker, false);

        // Cancel original placement and keep token infinite.
        event.setCancelled(true);
        event.getPlayer().getInventory().setItem(event.getHand(), normalizeTokenStack(inHand));

        upsertNote(target.getLocation(), instrumentId, cell.pitch(), activeLayerIndex);
        // Preview the note on placement.
        previewNote(instrumentId, cell.pitch());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (!isSessionPlayer(event.getPlayer())) return;
        if (!isInSessionWorld(event.getBlock().getWorld())) return;

        // Support breaking notes on any layer.
        final Block block = event.getBlock();
        final NoteEvent removed = removeNoteAt(block);
        if (removed != null) {
            event.setCancelled(true);
            previewNote(removed.instrumentId(), removed.pitch());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (!isSessionPlayer(p)) return;
        if (!isInSessionWorld(p.getWorld())) return;

        // If it's one of our menus, handle it here.
        if (isSettingsMainView(event.getView())) {
            event.setCancelled(true);
            handleSettingsMainClick(event.getRawSlot());
            return;
        }
        if (isSettingsTempoView(event.getView())) {
            event.setCancelled(true);
            handleSettingsTempoClick(event.getRawSlot());
            return;
        }
        if (isSettingsLengthView(event.getView())) {
            event.setCancelled(true);
            handleSettingsLengthClick(event.getRawSlot());
            return;
        }

        // Otherwise allow moving instruments freely.
        // We still prevent a couple of abusive actions with the control items.
        final ItemStack current = event.getCurrentItem();
        final ItemStack cursor = event.getCursor();
        if (!isStartItem(current) && !isStartItem(cursor) && !isSettingsItem(current) && !isSettingsItem(cursor)) {
            return;
        }

        // Block dropping / cloning / number-key swaps for control items.
        if (event.getClick() == ClickType.DROP
                || event.getClick() == ClickType.CONTROL_DROP
                || event.getAction() == InventoryAction.HOTBAR_SWAP
                || event.getAction() == InventoryAction.CLONE_STACK) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (!isSessionPlayer(event.getPlayer())) return;
        if (!isInSessionWorld(event.getPlayer().getWorld())) return;

        if (DEBUG_INPUT) {
            final String item = event.getItem() == null ? "null" : event.getItem().getType().name();
            plugin.getLogger().info("[NB] Interact action=" + event.getAction() + " hand=" + event.getHand() + " item=" + item + " cancelled=" + event.isCancelled());
        }

        final Action action = event.getAction();

        // Sword range tool: Shift+Click resets; Left sets BEGIN; Right sets END
        final ItemStack item = event.getItem();
        if (isRangeTool(item)
                && (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK
                || action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) {
            event.setCancelled(true);

            if (event.getPlayer().isSneaking()) {
                resetRange();
                player.sendActionBar(ChatColor.GRAY + "Range cleared");
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.4f, 0.9f);
                return;
            }

            final Integer idx = raycastTimeIndex();
            if (idx == null) {
                player.sendActionBar(ChatColor.RED + "Look at the track to set a range.");
                return;
            }

            if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                setRangeBegin(idx);
                player.sendActionBar(ChatColor.AQUA + "Begin: " + ChatColor.WHITE + idx);
            } else {
                setRangeEnd(idx);
                player.sendActionBar(ChatColor.GOLD + "End: " + ChatColor.WHITE + idx);
            }
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.4f, 1.2f);
            return;
        }

        // Left click: long-distance remove note on track.
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            final Block target = raycastTrackCellBlock();
            if (target != null) {
                final NoteEvent removed = removeNoteAt(target);
                if (removed != null) {
                    previewNote(removed.instrumentId(), removed.pitch());
                    event.setCancelled(true);
                }
            }
            return;
        }

        // Right click: we handle BOTH AIR and BLOCK.
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        // Basic debounce
        final long now = System.currentTimeMillis();
        if (now - lastRightClickMs < 75) {
            event.setCancelled(true);
            return;
        }
        lastRightClickMs = now;

        // Always cancel for our special items so region plugins/vanilla don't eat it.
        if (isSettingsItem(item)) {
            event.setCancelled(true);
            openSettingsMainMenu();
            return;
        }

        if (isStartItem(item)) {
            event.setCancelled(true);
            togglePlayback();
            return;
        }

        // Layer tool actions
        if (isLayerTool(item)) {
            event.setCancelled(true);
            if (event.getPlayer().isSneaking()) {
                // Shift+RightClick: add/remove layer.
                if (activeLayerIndex >= layerCount) activeLayerIndex = 0;

                // If current layer is empty and layerCount>1, remove it. Otherwise add if possible.
                if (isLayerEmpty(activeLayerIndex) && layerCount > 1) {
                    removeLayer(activeLayerIndex);
                } else if (layerCount < LAYER_COUNT) {
                    layerCount++;
                    player.sendActionBar(ChatColor.YELLOW + "Layers: " + layerCount + " (active " + (activeLayerIndex + 1) + ")");
                } else {
                    player.sendActionBar(ChatColor.RED + "Max layers reached (" + LAYER_COUNT + ")");
                }
            } else {
                // RightClick: cycle active layer within current layerCount.
                activeLayerIndex = (activeLayerIndex + 1) % layerCount;
                player.sendActionBar(ChatColor.YELLOW + "Layer: " + (activeLayerIndex + 1) + "/" + layerCount);
            }
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.4f, 1.2f);
            return;
        }

        final Integer instrumentId = getInstrumentId(item);
        if (instrumentId != null) {
            final Block target = raycastTrackCellBlock();
            if (target != null) {
                final TrackCell cell = toCell(target.getLocation());
                if (cell != null) {
                    event.setCancelled(true);
                    final Material marker = InstrumentPalette.byId(instrumentId).marker;
                    target.setType(marker, false);
                    upsertNote(target.getLocation(), instrumentId, cell.pitch(), activeLayerIndex);
                    previewNote(instrumentId, cell.pitch());
                    event.getPlayer().getInventory().setItemInMainHand(normalizeTokenStack(item));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!isSessionPlayer(event.getPlayer())) return;
        if (!isInSessionWorld(event.getPlayer().getWorld())) return;

        // While holding an instrument token, swap-hand cycles layers.
        final Integer id = getInstrumentId(event.getMainHandItem());
        if (id == null) return;

        event.setCancelled(true);
        cycleLayer(+1);
    }

    private void cycleLayer(int delta) {
        activeLayerIndex = Math.floorMod(activeLayerIndex + delta, LAYER_COUNT);
        player.sendActionBar(ChatColor.YELLOW + "Layer: " + (activeLayerIndex + 1) + "/" + LAYER_COUNT);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.4f, 1.2f);
    }

    // --- Playback

    private void togglePlayback() {
        if (!isInSessionWorld(player.getWorld())) {
            player.sendMessage(ChatColor.RED + "You can only start playback in your session world.");
            return;
        }

        if (playbackTask != null) {
            stopPlayback();
            player.sendMessage(ChatColor.YELLOW + "Playback stopped.");
            return;
        }

        startPlayback();
        player.sendMessage(ChatColor.AQUA + "Playback started.");
    }

    private void startPlayback() {
        stopPlayback();

        playbackTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (player == null || !player.isOnline()) {
                stopPlayback();
                return;
            }
            if (session == null || session.world() == null) {
                stopPlayback();
                return;
            }

            // Recompute bounds dynamically so changing RANGE mid-playback affects the next loop.
            final int startIndex = playbackStartIndex();
            final int endExclusive = playbackEndExclusive();
            if (startIndex >= endExclusive) {
                stopPlayback();
                player.sendMessage(ChatColor.RED + "Invalid range.");
                return;
            }

            if (playhead < startIndex || playhead >= endExclusive) {
                playhead = startIndex;
            }

            drawPlayhead(playhead);
            playStep(playhead);
            playhead++;

            if (playhead >= endExclusive) {
                if (loopEnabled) {
                    playhead = startIndex;
                } else {
                    stopPlayback();
                    player.sendMessage(ChatColor.GRAY + "Playback finished.");
                }
            }
        }, 0L, ticksPerStep);
    }

    private void stopPlayback() {
        if (playbackTask != null) {
            playbackTask.cancel();
            playbackTask = null;
        }
        clearPlayhead();

        // Reset playhead so the next start always begins from the start (or range start).
        playhead = playbackStartIndex();
    }

    private int playbackStartIndex() {
        final int min = 0;
        final int max = trackLength; // exclusive
        if (rangeBeginIndex == null && rangeEndIndex == null) return min;

        int a = rangeBeginIndex == null ? min : clamp(rangeBeginIndex, min, max - 1);
        int b = rangeEndIndex == null ? (max - 1) : clamp(rangeEndIndex, min, max - 1);
        return Math.min(a, b);
    }

    private int playbackEndExclusive() {
        final int min = 0;
        final int max = trackLength; // exclusive
        if (rangeBeginIndex == null && rangeEndIndex == null) return max;

        int a = rangeBeginIndex == null ? min : clamp(rangeBeginIndex, min, max - 1);
        int b = rangeEndIndex == null ? (max - 1) : clamp(rangeEndIndex, min, max - 1);
        return Math.max(a, b) + 1;
    }

    private static int clamp(int v, int minInclusive, int maxInclusive) {
        return Math.max(minInclusive, Math.min(maxInclusive, v));
    }

    private void drawPlayhead(int tickIndex) {
        clearPlayhead();
        final World w = session.world();
        final int x = ORIGIN.getBlockX() + tickIndex;
        final int y = layerY(activeLayerIndex);
        for (int dz = 0; dz < TRACK_PITCH_WIDTH; dz++) {
            final int z = ORIGIN.getBlockZ() + dz;
            final Block b = w.getBlockAt(x, y, z);
            if (b.getType() == Material.AIR) {
                final BlockPos pos = BlockPos.from(b.getLocation());
                playheadOverlay.put(pos, Material.AIR);
                b.setType(Material.RED_STAINED_GLASS, false);
            }
        }
    }

    private void clearPlayhead() {
        if (session == null || session.world() == null) {
            playheadOverlay.clear();
            return;
        }
        final World w = session.world();
        for (Map.Entry<BlockPos, Material> e : playheadOverlay.entrySet()) {
            final BlockPos pos = e.getKey();
            final Block b = w.getBlockAt(pos.x(), pos.y(), pos.z());
            if (b.getType() == Material.RED_STAINED_GLASS) {
                b.setType(e.getValue(), false);
            }
        }
        playheadOverlay.clear();
    }

    private void previewNote(int instrumentId, int pitchRow) {
        final InstrumentPalette palette = InstrumentPalette.byId(instrumentId);
        final float pitch = pitchFromRow(pitchRow);
        player.playSound(player.getLocation(), palette.sound, SoundCategory.RECORDS, 1.0f, pitch);
    }

    private void upsertNote(Location loc, int instrumentId, int pitch, int layerIndex) {
        ensureScoreInitialized();
        layerIndex = clampLayerIndex(layerIndex);

        final TrackCell cell = toCell(loc);
        if (cell == null) return;

        final int tickIndex = cell.timeIndex();
        final BlockPos pos = BlockPos.from(loc);

        final NoteRef old = refByPos.put(pos, new NoteRef(layerIndex, tickIndex));
        if (old != null) {
            final int oldLayer = clampLayerIndex(old.layerIndex());
            final List<NoteEvent> oldList = scoreByLayer.get(oldLayer).get(old.tickIndex());
            if (oldList != null) {
                oldList.removeIf(n -> n.pos().equals(pos));
                if (oldList.isEmpty()) scoreByLayer.get(oldLayer).remove(old.tickIndex());
            }
        }

        scoreByLayer.get(layerIndex)
                .computeIfAbsent(tickIndex, k -> new ArrayList<>())
                .add(new NoteEvent(pos, instrumentId, pitch));
    }

    private NoteEvent removeNoteAt(Block block) {
        ensureScoreInitialized();

        final BlockPos pos = BlockPos.from(block.getLocation());
        final NoteRef ref = refByPos.remove(pos);
        if (ref == null) return null;

        final int refLayer = clampLayerIndex(ref.layerIndex());

        NoteEvent removed = null;
        final Map<Integer, List<NoteEvent>> layerScore = scoreByLayer.get(refLayer);
        final List<NoteEvent> list = layerScore.get(ref.tickIndex());
        if (list != null) {
            for (Iterator<NoteEvent> it = list.iterator(); it.hasNext(); ) {
                final NoteEvent n = it.next();
                if (n.pos().equals(pos)) {
                    removed = n;
                    it.remove();
                    break;
                }
            }
            if (list.isEmpty()) layerScore.remove(ref.tickIndex());
        }

        final World w = block.getWorld();
        final Material old = block.getType();
        block.setType(Material.AIR, false);

        w.spawnParticle(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5), 12, 0.2, 0.2, 0.2, old.createBlockData());
        w.playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_WOOL_BREAK, SoundCategory.BLOCKS, 0.8f, 1.0f);

        return removed;
    }

    private void playStep(int tickIndex) {
        ensureScoreInitialized();
        final int layersToPlay = Math.min(layerCount, scoreByLayer.size());
        for (int layer = 0; layer < layersToPlay; layer++) {
            final Map<Integer, List<NoteEvent>> layerMap = scoreByLayer.get(layer);
            if (layerMap == null) continue;

            final List<NoteEvent> events = layerMap.get(tickIndex);
            if (events == null || events.isEmpty()) continue;

            for (NoteEvent e : events) {
                final InstrumentPalette palette = InstrumentPalette.byId(e.instrumentId());
                final float pitch = pitchFromRow(e.pitch());
                player.playSound(player.getLocation(), palette.sound, SoundCategory.RECORDS, 1.0f, pitch);

                final Location at = e.pos().toLocation(session.world()).add(0.5, 0.8, 0.5);
                session.world().spawnParticle(Particle.NOTE, at, 1, 0, 0, 0, 1);
            }
        }
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

    private boolean isSessionPlayer(Player p) {
        return this.player != null && p.getUniqueId().equals(this.player.getUniqueId());
    }

    private boolean isInSessionWorld(World world) {
        return session != null && session.world() != null && session.world().equals(world);
    }

    private static float pitchFromRow(int row) {
        return 0.5f + (row / (float) (TRACK_PITCH_WIDTH - 1)) * 1.5f;
    }

    private boolean isLayerEmpty(int layerIndex) {
        final Map<Integer, List<NoteEvent>> map = scoreByLayer.get(layerIndex);
        if (map == null || map.isEmpty()) return true;
        for (List<NoteEvent> list : map.values()) {
            if (list != null && !list.isEmpty()) return false;
        }
        return true;
    }

    private void removeLayer(int layerIndex) {
        // Only allow removing an empty layer.
        if (!isLayerEmpty(layerIndex)) {
            player.sendActionBar(ChatColor.RED + "Layer isn't empty.");
            return;
        }

        // Move active layer if needed.
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

        scoreByLayer.get(layerIndex).clear();

        // If removing the top-most layer, just decrement layerCount.
        if (layerIndex == layerCount - 1) {
            layerCount--;
        } else {
            // If removing a middle layer, shift notes down (none exist), but keep counts consistent by swapping with last.
            scoreByLayer.set(layerIndex, scoreByLayer.get(layerCount - 1));
            scoreByLayer.set(layerCount - 1, new HashMap<>());
            layerCount--;
        }

        if (activeLayerIndex >= layerCount) activeLayerIndex = layerCount - 1;
        player.sendActionBar(ChatColor.YELLOW + "Layers: " + layerCount + " (active " + (activeLayerIndex + 1) + ")");
        redrawRangeOverlay();
    }

    private void ensureScoreInitialized() {
        if (scoreByLayer.isEmpty()) {
            for (int i = 0; i < LAYER_COUNT; i++) scoreByLayer.add(new HashMap<>());
        } else {
            while (scoreByLayer.size() < LAYER_COUNT) scoreByLayer.add(new HashMap<>());
        }
    }

    private static int clampLayerIndex(int idx) {
        return Math.max(0, Math.min(LAYER_COUNT - 1, idx));
    }

    private TrackCell toCell(Location loc) {
        // Accept any layer plane.
        final int y = loc.getBlockY();
        if (y < TRACK_Y || y >= TRACK_Y + LAYER_COUNT) return null;

        final int dx = loc.getBlockX() - ORIGIN.getBlockX();
        final int dz = loc.getBlockZ() - ORIGIN.getBlockZ();

        if (dx < 0 || dx >= trackLength) return null;
        if (dz < 0 || dz >= TRACK_PITCH_WIDTH) return null;

        return new TrackCell(dx, dz);
    }

    private boolean isLayerTool(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        final PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return TYPE_LAYER_TOOL.equals(pdc.get(keyType, PersistentDataType.STRING));
    }

    private boolean isRangeTool(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        final PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return TYPE_RANGE_TOOL.equals(pdc.get(keyType, PersistentDataType.STRING));
    }

    private boolean isStartItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        final PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return TYPE_START.equals(pdc.get(keyType, PersistentDataType.STRING));
    }

    private boolean isSettingsItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        final PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return TYPE_SETTINGS.equals(pdc.get(keyType, PersistentDataType.STRING));
    }

    private Integer getInstrumentId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        final ItemMeta meta = item.getItemMeta();
        final PersistentDataContainer pdc = meta.getPersistentDataContainer();
        final String type = pdc.get(keyType, PersistentDataType.STRING);
        if (!TYPE_INSTRUMENT.equals(type)) return null;
        return pdc.get(keyInstrument, PersistentDataType.INTEGER);
    }

    private ItemStack normalizeTokenStack(ItemStack inHand) {
        if (inHand == null) return null;
        if (getInstrumentId(inHand) == null) return inHand;
        if (inHand.getAmount() == 1) return inHand;
        final ItemStack copy = inHand.clone();
        copy.setAmount(1);
        return copy;
    }

    private void giveItems(Player player) {
        player.getInventory().clear();

        for (InstrumentPalette palette : InstrumentPalette.values()) {
            final ItemStack token = new ItemStack(palette.token, 1);
            final ItemMeta meta = token.getItemMeta();
            meta.setDisplayName(palette.displayName);
            meta.getPersistentDataContainer().set(keyType, PersistentDataType.STRING, TYPE_INSTRUMENT);
            meta.getPersistentDataContainer().set(keyInstrument, PersistentDataType.INTEGER, palette.id);
            token.setItemMeta(meta);
            player.getInventory().addItem(token);
        }

        final ItemStack settings = new ItemStack(Material.COMPARATOR, 1);
        final ItemMeta settingsMeta = settings.getItemMeta();
        settingsMeta.setDisplayName(ChatColor.YELLOW + "Settings");
        settingsMeta.getPersistentDataContainer().set(keyType, PersistentDataType.STRING, TYPE_SETTINGS);
        settings.setItemMeta(settingsMeta);
        player.getInventory().setItem(INVENTORY_SLOT_SETTINGS, settings);

        final ItemStack start = new ItemStack(Material.BLAZE_ROD, 1);
        final ItemMeta startMeta = start.getItemMeta();
        startMeta.setDisplayName(ChatColor.AQUA + "Start / Stop");
        startMeta.getPersistentDataContainer().set(keyType, PersistentDataType.STRING, TYPE_START);
        start.setItemMeta(startMeta);
        player.getInventory().setItem(INVENTORY_SLOT_START, start);

        // Layer tool
        final ItemStack layerTool = new ItemStack(Material.SLIME_BALL, 1);
        final ItemMeta layerToolMeta = layerTool.getItemMeta();
        layerToolMeta.setDisplayName(ChatColor.YELLOW + "Layers");
        layerToolMeta.setLore(List.of(
                ChatColor.GRAY + "Right Click: next layer",
                ChatColor.GRAY + "Shift + Right Click: add/remove"
        ));
        layerToolMeta.getPersistentDataContainer().set(keyType, PersistentDataType.STRING, TYPE_LAYER_TOOL);
        layerTool.setItemMeta(layerToolMeta);
        player.getInventory().setItem(INVENTORY_SLOT_LAYER_TOOL, layerTool);

        // Range tool
        final ItemStack rangeTool = new ItemStack(Material.IRON_SWORD, 1);
        final ItemMeta rangeToolMeta = rangeTool.getItemMeta();
        rangeToolMeta.setDisplayName(ChatColor.YELLOW + "Range");
        rangeToolMeta.setLore(List.of(
                ChatColor.GRAY + "Left Click: set begin",
                ChatColor.GRAY + "Right Click: set end",
                ChatColor.GRAY + "Shift + Click: reset"
        ));
        rangeToolMeta.getPersistentDataContainer().set(keyType, PersistentDataType.STRING, TYPE_RANGE_TOOL);
        rangeTool.setItemMeta(rangeToolMeta);
        player.getInventory().setItem(INVENTORY_SLOT_RANGE_TOOL, rangeTool);
    }

    private void applyWorldRules(World world) {
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);
        world.setClearWeatherDuration(Integer.MAX_VALUE);

        // Freeze time using string gamerule to avoid deprecated enum constants on Paper 1.21.11+
        setGameRuleIfPresent(world, "doDaylightCycle", false);
        // Paper rule that hard-stops time ticking
        setGameRuleIfPresent(world, "tickTime", false);

        setGameRuleIfPresent(world, "doWeatherCycle", false);
        setGameRuleIfPresent(world, "doMobSpawning", false);
        setGameRuleIfPresent(world, "keepInventory", true);
        setGameRuleIfPresent(world, "fallDamage", false);
        setGameRuleIfPresent(world, "doImmediateRespawn", true);
        setGameRuleIfPresent(world, "naturalRegeneration", false);
    }

    private void startFreezeTimeTask(World world) {
        stopFreezeTimeTask();
        freezeTimeTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (session == null || session.world() == null) {
                stopFreezeTimeTask();
                return;
            }
            final World w = session.world();
            if (!w.equals(world)) return;

            setGameRuleIfPresent(w, "doDaylightCycle", false);
            setGameRuleIfPresent(w, "tickTime", false);
            w.setTime(6000);
        }, 0L, 20L);
    }

    private void stopFreezeTimeTask() {
        if (freezeTimeTask != null) {
            freezeTimeTask.cancel();
            freezeTimeTask = null;
        }
    }

    // --- Settings menus

    private void openSettingsMainMenu() {
        if (settingsMainInventory == null) {
            settingsMainInventory = Bukkit.createInventory(null, 9 * 4, MENU_SETTINGS_MAIN_TITLE);
        }
        redrawSettingsMainMenu();
        player.openInventory(settingsMainInventory);
    }

    private void openSettingsTempoMenu() {
        if (settingsTempoInventory == null) {
            settingsTempoInventory = Bukkit.createInventory(null, 9 * 4, MENU_SETTINGS_TEMPO_TITLE);
        }
        redrawSettingsTempoMenu();
        player.openInventory(settingsTempoInventory);
    }

    private void openSettingsLengthMenu() {
        if (settingsLengthInventory == null) {
            settingsLengthInventory = Bukkit.createInventory(null, 9 * 4, MENU_SETTINGS_LENGTH_TITLE);
        }
        redrawSettingsLengthMenu();
        player.openInventory(settingsLengthInventory);
    }

    private void redrawSettingsMainMenu() {
        settingsMainInventory.clear();

        final ItemStack tempo = new ItemStack(Material.CLOCK);
        final ItemMeta tempoMeta = tempo.getItemMeta();
        tempoMeta.setDisplayName(ChatColor.YELLOW + "Tempo");
        tempo.setItemMeta(tempoMeta);
        settingsMainInventory.setItem(MAIN_TEMPO_BUTTON_SLOT, tempo);

        final ItemStack length = new ItemStack(Material.OAK_SIGN);
        final ItemMeta lengthMeta = length.getItemMeta();
        lengthMeta.setDisplayName(ChatColor.YELLOW + "Track Length");
        lengthMeta.setLore(List.of(ChatColor.GRAY + "Current: " + trackLength));
        length.setItemMeta(lengthMeta);
        settingsMainInventory.setItem(MAIN_LENGTH_BUTTON_SLOT, length);

        final ItemStack loop = new ItemStack(loopEnabled ? Material.LIME_DYE : Material.GRAY_DYE);
        final ItemMeta loopMeta = loop.getItemMeta();
        loopMeta.setDisplayName(ChatColor.YELLOW + "Loop");
        loopMeta.setLore(List.of(ChatColor.GRAY + "Click to toggle", ChatColor.GRAY + "Current: " + (loopEnabled ? "ON" : "OFF")));
        loop.setItemMeta(loopMeta);
        settingsMainInventory.setItem(MAIN_LOOP_BUTTON_SLOT, loop);

        final ItemStack close = new ItemStack(Material.BARRIER);
        final ItemMeta closeMeta = close.getItemMeta();
        closeMeta.setDisplayName(ChatColor.GRAY + "Close");
        close.setItemMeta(closeMeta);
        settingsMainInventory.setItem(MAIN_CLOSE_SLOT, close);

        fillMenuBackground(settingsMainInventory);
    }

    private void redrawSettingsTempoMenu() {
        settingsTempoInventory.clear();

        final ItemStack tempo = new ItemStack(Material.CLOCK);
        final ItemMeta tempoMeta = tempo.getItemMeta();
        tempoMeta.setDisplayName(ChatColor.YELLOW + "Tempo: " + ChatColor.WHITE + ticksPerStep + " t/step");
        tempo.setItemMeta(tempoMeta);
        settingsTempoInventory.setItem(TEMPO_DISPLAY_SLOT, tempo);

        final ItemStack minus = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        final ItemMeta minusMeta = minus.getItemMeta();
        minusMeta.setDisplayName(ChatColor.RED + "- Slower");
        minus.setItemMeta(minusMeta);
        settingsTempoInventory.setItem(TEMPO_MINUS_SLOT, minus);

        final ItemStack plus = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        final ItemMeta plusMeta = plus.getItemMeta();
        plusMeta.setDisplayName(ChatColor.GREEN + "+ Faster");
        plus.setItemMeta(plusMeta);
        settingsTempoInventory.setItem(TEMPO_PLUS_SLOT, plus);

        final ItemStack back = new ItemStack(Material.ARROW);
        final ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName(ChatColor.YELLOW + "Back");
        back.setItemMeta(backMeta);
        settingsTempoInventory.setItem(TEMPO_BACK_SLOT, back);

        final ItemStack close = new ItemStack(Material.BARRIER);
        final ItemMeta closeMeta = close.getItemMeta();
        closeMeta.setDisplayName(ChatColor.GRAY + "Close");
        close.setItemMeta(closeMeta);
        settingsTempoInventory.setItem(TEMPO_CLOSE_SLOT, close);

        fillMenuBackground(settingsTempoInventory);
    }

    private void redrawSettingsLengthMenu() {
        settingsLengthInventory.clear();

        final ItemStack length = new ItemStack(Material.OAK_SIGN);
        final ItemMeta lengthMeta = length.getItemMeta();
        lengthMeta.setDisplayName(ChatColor.YELLOW + "Track Length: " + ChatColor.WHITE + trackLength);
        lengthMeta.setLore(List.of(ChatColor.GRAY + "Max: " + MAX_TRACK_TIME_LENGTH));
        length.setItemMeta(lengthMeta);
        settingsLengthInventory.setItem(LENGTH_DISPLAY_SLOT, length);

        settingsLengthInventory.setItem(LENGTH_PLUS_1_SLOT, namedButton(Material.LIME_STAINED_GLASS_PANE, ChatColor.GREEN + "+1"));
        settingsLengthInventory.setItem(LENGTH_PLUS_10_SLOT, namedButton(Material.LIME_STAINED_GLASS_PANE, ChatColor.GREEN + "+10"));
        settingsLengthInventory.setItem(LENGTH_PLUS_50_SLOT, namedButton(Material.LIME_STAINED_GLASS_PANE, ChatColor.GREEN + "+50"));

        settingsLengthInventory.setItem(LENGTH_MINUS_50_SLOT, namedButton(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "-50"));
        settingsLengthInventory.setItem(LENGTH_MINUS_10_SLOT, namedButton(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "-10"));
        settingsLengthInventory.setItem(LENGTH_MINUS_1_SLOT, namedButton(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "-1"));

        final ItemStack back = new ItemStack(Material.ARROW);
        final ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName(ChatColor.YELLOW + "Back");
        back.setItemMeta(backMeta);
        settingsLengthInventory.setItem(LENGTH_BACK_SLOT, back);

        final ItemStack close = new ItemStack(Material.BARRIER);
        final ItemMeta closeMeta = close.getItemMeta();
        closeMeta.setDisplayName(ChatColor.GRAY + "Close");
        close.setItemMeta(closeMeta);
        settingsLengthInventory.setItem(LENGTH_CLOSE_SLOT, close);

        fillMenuBackground(settingsLengthInventory);
    }

    private ItemStack namedButton(Material mat, String name) {
        final ItemStack it = new ItemStack(mat);
        final ItemMeta m = it.getItemMeta();
        m.setDisplayName(name);
        it.setItemMeta(m);
        return it;
    }

    private void fillMenuBackground(Inventory inv) {
        final ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        final ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);

        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }
    }

    private boolean isSettingsMainView(InventoryView view) {
        return settingsMainInventory != null && view.getTopInventory().equals(settingsMainInventory);
    }

    private boolean isSettingsTempoView(InventoryView view) {
        return settingsTempoInventory != null && view.getTopInventory().equals(settingsTempoInventory);
    }

    private boolean isSettingsLengthView(InventoryView view) {
        return settingsLengthInventory != null && view.getTopInventory().equals(settingsLengthInventory);
    }

    private void handleSettingsMainClick(int rawSlot) {
        if (rawSlot == MAIN_TEMPO_BUTTON_SLOT) {
            openSettingsTempoMenu();
        } else if (rawSlot == MAIN_LENGTH_BUTTON_SLOT) {
            openSettingsLengthMenu();
        } else if (rawSlot == MAIN_LOOP_BUTTON_SLOT) {
            loopEnabled = !loopEnabled;
            player.sendMessage(ChatColor.GRAY + "Loop " + (loopEnabled ? "enabled" : "disabled"));

            // If playback is active, apply immediately.
            if (playbackTask != null && loopEnabled) {
                playhead = playbackStartIndex();
            }

            redrawSettingsMainMenu();
        } else if (rawSlot == MAIN_CLOSE_SLOT) {
            player.closeInventory();
        }
    }

    private void handleSettingsTempoClick(int rawSlot) {
        if (rawSlot == TEMPO_MINUS_SLOT) {
            setTicksPerStep(Math.min(MAX_TICKS_PER_STEP, ticksPerStep + 1));
            redrawSettingsTempoMenu();
        } else if (rawSlot == TEMPO_PLUS_SLOT) {
            setTicksPerStep(Math.max(MIN_TICKS_PER_STEP, ticksPerStep - 1));
            redrawSettingsTempoMenu();
        } else if (rawSlot == TEMPO_BACK_SLOT) {
            openSettingsMainMenu();
        } else if (rawSlot == TEMPO_CLOSE_SLOT) {
            player.closeInventory();
        }
    }

    private void handleSettingsLengthClick(int rawSlot) {
        if (rawSlot == LENGTH_PLUS_1_SLOT) {
            adjustTrackLength(1);
        } else if (rawSlot == LENGTH_PLUS_10_SLOT) {
            adjustTrackLength(10);
        } else if (rawSlot == LENGTH_PLUS_50_SLOT) {
            adjustTrackLength(50);
        } else if (rawSlot == LENGTH_MINUS_1_SLOT) {
            adjustTrackLength(-1);
        } else if (rawSlot == LENGTH_MINUS_10_SLOT) {
            adjustTrackLength(-10);
        } else if (rawSlot == LENGTH_MINUS_50_SLOT) {
            adjustTrackLength(-50);
        } else if (rawSlot == LENGTH_BACK_SLOT) {
            openSettingsMainMenu();
            return;
        } else if (rawSlot == LENGTH_CLOSE_SLOT) {
            player.closeInventory();
            return;
        }

        redrawSettingsLengthMenu();
    }

    private void setTicksPerStep(int newValue) {
        if (newValue == ticksPerStep) return;
        ticksPerStep = newValue;
        player.sendMessage(ChatColor.GRAY + "Tempo set to " + ticksPerStep + " ticks/step");
        if (playbackTask != null) startPlayback();
    }

    private void adjustTrackLength(int delta) {
        final int oldLength = trackLength;
        final int newLength = clamp(trackLength + delta, MIN_TRACK_TIME_LENGTH, MAX_TRACK_TIME_LENGTH);
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

            // Also clear the (now out of track) world columns so nothing remains visually.
            clearWorldColumnsOutsideLength(newLength, oldLength);
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

        // Rebuild visuals and redraw existing notes.
        buildTrack(session.world());

        // Restart playback to respect new bounds.
        if (playbackTask != null) startPlayback();
    }

    private void pruneNotesOutsideLength(int newLength) {
        // Remove tick entries >= newLength in every layer.
        for (int layer = 0; layer < LAYER_COUNT; layer++) {
            final Map<Integer, List<NoteEvent>> map = scoreByLayer.get(layer);
            if (map == null || map.isEmpty()) continue;

            final Iterator<Map.Entry<Integer, List<NoteEvent>>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<Integer, List<NoteEvent>> e = it.next();
                if (e.getKey() < newLength) continue;

                final List<NoteEvent> list = e.getValue();
                if (list != null) {
                    for (NoteEvent n : list) {
                        refByPos.remove(n.pos());
                    }
                }
                it.remove();
            }
        }

        // Also purge any refByPos that points outside, just in case.
        refByPos.entrySet().removeIf(e -> {
            final BlockPos pos = e.getKey();
            final int dx = pos.x() - ORIGIN.getBlockX();
            return dx >= newLength;
        });
    }

    private void clearWorldColumnsOutsideLength(int startInclusive, int endExclusive) {
        if (session == null || session.world() == null) return;
        final World w = session.world();

        for (int dx = startInclusive; dx < endExclusive; dx++) {
            final int x = ORIGIN.getBlockX() + dx;

            for (int dz = 0; dz < TRACK_PITCH_WIDTH; dz++) {
                final int z = ORIGIN.getBlockZ() + dz;

                // Clear all note layers.
                for (int layer = 0; layer < LAYER_COUNT; layer++) {
                    w.getBlockAt(x, layerY(layer), z).setType(Material.AIR, false);
                }

                // Optional: clear floor too (helps visually when shrinking).
                w.getBlockAt(x, BASE_Y, z).setType(Material.AIR, false);
            }

            // Clear arrow strip column too.
            final int zArrow = ORIGIN.getBlockZ() + TRACK_PITCH_WIDTH;
            w.getBlockAt(x, BASE_Y, zArrow).setType(Material.AIR, false);
        }

        // Any overlays that were outside are now invalid.
        clearPlayhead();
        clearRangeOverlay();
    }

    // --- Records

    private record TrackCell(int timeIndex, int pitch) {
    }

    private record NoteEvent(BlockPos pos, int instrumentId, int pitch) {
    }

    private record NoteRef(int layerIndex, int tickIndex) {
    }

    private Block raycastTrackCellBlock() {
        if (player == null) return null;
        final World w = player.getWorld();
        if (!isInSessionWorld(w)) return null;

        final RayTraceResult rr = player.rayTraceBlocks(EDIT_RAY_DISTANCE, FluidCollisionMode.NEVER);
        if (rr == null) return null;

        Block hit = rr.getHitBlock();
        if (hit == null) return null;

        // When you target the side of a block, pick the adjacent block.
        final BlockFace face = rr.getHitBlockFace();
        if (face != null) {
            hit = hit.getRelative(face);
        }

        final int dx = hit.getX() - ORIGIN.getBlockX();
        final int dz = hit.getZ() - ORIGIN.getBlockZ();
        if (dx < 0 || dx >= trackLength) return null;
        if (dz < 0 || dz >= TRACK_PITCH_WIDTH) return null;

        return w.getBlockAt(hit.getX(), layerY(activeLayerIndex), hit.getZ());
    }

    private Integer raycastTimeIndex() {
        final Block b = raycastTrackCellBlock();
        if (b == null) return null;
        final int dx = b.getX() - ORIGIN.getBlockX();
        if (dx < 0 || dx >= trackLength) return null;
        return dx;
    }

    private void buildTrack(World world) {
        // Build BASE_Y floor once, and clear all layers above.
        for (int dx = 0; dx < trackLength; dx++) {
            for (int dz = 0; dz < TRACK_PITCH_WIDTH; dz++) {
                final int x = ORIGIN.getBlockX() + dx;
                final int z = ORIGIN.getBlockZ() + dz;

                final Material floor = (dz & 1) == 0 ? Material.BROWN_CONCRETE : Material.TERRACOTTA;
                world.getBlockAt(x, BASE_Y, z).setType(floor, false);

                for (int layer = 0; layer < LAYER_COUNT; layer++) {
                    world.getBlockAt(x, layerY(layer), z).setType(Material.AIR, false);
                }

                // Only keep the time borders black; pitch edges remain playable alternating floor.
                if (dx == 0 || dx == trackLength - 1) {
                    world.getBlockAt(x, BASE_Y, z).setType(Material.BLACK_CONCRETE, false);
                }
            }
        }

        // Direction indicator strip (no golden arrow head)
        final int zArrow = ORIGIN.getBlockZ() + TRACK_PITCH_WIDTH;
        for (int dx = 0; dx < trackLength; dx++) {
            world.getBlockAt(ORIGIN.getBlockX() + dx, BASE_Y, zArrow).setType(Material.DARK_OAK_PLANKS, false);
        }

        final Location view = new Location(world,
                ORIGIN.getX() + 1.5,
                TRACK_Y + 1.0,
                ORIGIN.getZ() + (TRACK_PITCH_WIDTH / 2.0) + 0.5,
                -90f,
                20f);
        player.teleportAsync(view);

        redrawRangeOverlay();
        redrawNotesFromScore();
    }

    private void redrawNotesFromScore() {
        if (session == null || session.world() == null) return;
        ensureScoreInitialized();

        final World w = session.world();
        for (int layer = 0; layer < LAYER_COUNT; layer++) {
            final Map<Integer, List<NoteEvent>> map = scoreByLayer.get(layer);
            if (map == null || map.isEmpty()) continue;

            final int y = layerY(layer);
            for (Map.Entry<Integer, List<NoteEvent>> e : map.entrySet()) {
                final int tickIndex = e.getKey();
                if (tickIndex < 0 || tickIndex >= trackLength) continue;

                final List<NoteEvent> list = e.getValue();
                if (list == null) continue;

                for (NoteEvent n : list) {
                    final BlockPos pos = n.pos();
                    final int dx = pos.x() - ORIGIN.getBlockX();
                    final int dz = pos.z() - ORIGIN.getBlockZ();
                    if (dx < 0 || dx >= trackLength) continue;
                    if (dz < 0 || dz >= TRACK_PITCH_WIDTH) continue;

                    final Material marker = InstrumentPalette.byId(n.instrumentId()).marker;
                    w.getBlockAt(pos.x(), y, pos.z()).setType(marker, false);
                }
            }
        }
    }

    private void clearRangeOverlay() {
        if (session == null || session.world() == null) {
            rangeOverlay.clear();
            return;
        }
        final World w = session.world();
        for (Map.Entry<BlockPos, Material> e : rangeOverlay.entrySet()) {
            final BlockPos pos = e.getKey();
            final Block b = w.getBlockAt(pos.x(), pos.y(), pos.z());
            if (b.getType() == Material.BLUE_STAINED_GLASS || b.getType() == Material.ORANGE_STAINED_GLASS) {
                b.setType(e.getValue(), false);
            }
        }
        rangeOverlay.clear();
    }

    private void redrawRangeOverlay() {
        clearRangeOverlay();
        if (session == null || session.world() == null) return;
        final World w = session.world();

        if (rangeBeginIndex != null) {
            drawRangeMarker(w, clamp(rangeBeginIndex, 0, trackLength - 1), Material.BLUE_STAINED_GLASS);
        }
        if (rangeEndIndex != null) {
            drawRangeMarker(w, clamp(rangeEndIndex, 0, trackLength - 1), Material.ORANGE_STAINED_GLASS);
        }
    }

    private void drawRangeMarker(World w, int timeIndex, Material mat) {
        final int x = ORIGIN.getBlockX() + timeIndex;
        final int y = layerY(activeLayerIndex);
        for (int dz = 0; dz < TRACK_PITCH_WIDTH; dz++) {
            final int z = ORIGIN.getBlockZ() + dz;
            final Block b = w.getBlockAt(x, y, z);
            if (b.getType() == Material.AIR) {
                final BlockPos pos = BlockPos.from(b.getLocation());
                rangeOverlay.put(pos, Material.AIR);
                b.setType(mat, false);
            }
        }
    }

    private void setRangeBegin(int idx) {
        rangeBeginIndex = idx;
        redrawRangeOverlay();

        // If we're looping, make a range change take effect immediately (and therefore next loop too).
        if (playbackTask != null && loopEnabled) {
            playhead = playbackStartIndex();
        }
    }

    private void setRangeEnd(int idx) {
        rangeEndIndex = idx;
        redrawRangeOverlay();

        if (playbackTask != null && loopEnabled) {
            playhead = playbackStartIndex();
        }
    }

    private void resetRange() {
        rangeBeginIndex = null;
        rangeEndIndex = null;
        redrawRangeOverlay();

        if (playbackTask != null && loopEnabled) {
            playhead = playbackStartIndex();
        }
    }

    private void clearRangeOverlayOnStop() {
        clearRangeOverlay();
    }

    private BukkitTask freezeTimeTask;

    private static void setGameRuleIfPresent(World world, String ruleName, boolean value) {
        try {
            world.getClass().getMethod("setGameRule", String.class, String.class)
                    .invoke(world, ruleName, Boolean.toString(value));
        } catch (Throwable ignored) {
        }
    }
}
