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
    private static final int TRACK_TIME_LENGTH = 100;
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

    private static final int INVENTORY_SLOT_START = 8;
    private static final int INVENTORY_SLOT_SETTINGS = 7;
    private static final int INVENTORY_SLOT_LAYER_TOOL = 6;

    private static final int SETTINGS_TEMPO_SLOT = 11;
    private static final int SETTINGS_TEMPO_MINUS_SLOT = 20;
    private static final int SETTINGS_TEMPO_PLUS_SLOT = 24;
    private static final int SETTINGS_CLOSE_SLOT = 31;

    private static final int MIN_TICKS_PER_STEP = 1;
    private static final int MAX_TICKS_PER_STEP = 20;

    private static final double EDIT_RAY_DISTANCE = 30.0;

    // tempo is in server ticks per step
    private int ticksPerStep = (int) TICKS_PER_STEP;

    private final Plugin plugin;

    private NamespacedKey keyType;
    private NamespacedKey keyInstrument;

    private GameSession session;
    private Player player;

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

    private Inventory settingsInventory;

    private static final String MENU_SETTINGS_MAIN_TITLE = "Settings";
    private static final String MENU_SETTINGS_TEMPO_TITLE = "Settings > Tempo";

    private static final int MAIN_TEMPO_BUTTON_SLOT = 13;
    private static final int MAIN_CLOSE_SLOT = 31;

    private static final int TEMPO_DISPLAY_SLOT = 13;
    private static final int TEMPO_MINUS_SLOT = 20;
    private static final int TEMPO_PLUS_SLOT = 24;
    private static final int TEMPO_BACK_SLOT = 30;
    private static final int TEMPO_CLOSE_SLOT = 32;

    private Inventory settingsMainInventory;
    private Inventory settingsTempoInventory;

    private static final boolean DEBUG_INPUT = false;
    private long lastRightClickMs;

    private static final int LAYER_COUNT = 4;

    private static int layerY(int layerIndex) {
        return TRACK_Y + layerIndex;
    }

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
        buildTrack(session.world());
        giveItems(player);

        // Make sure hunger is full when entering.
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setHealth(player.getMaxHealth());

        player.sendMessage(ChatColor.GREEN + "Timeline ready. Place instrument blocks on the track, then right-click the Start item.");

        // Reset controller state for a clean session.
        ticksPerStep = (int) TICKS_PER_STEP;
        settingsInventory = null;
        settingsMainInventory = null;
        settingsTempoInventory = null;
        playheadOverlay.clear();
        layerCount = 1;
        activeLayerIndex = 0;
        scoreByLayer.clear();
        for (int i = 0; i < LAYER_COUNT; i++) scoreByLayer.add(new HashMap<>());
        refByPos.clear();
    }

    @Override
    public void onStop(GameSession session) {
        stopPlayback();
        clearPlayhead();
        if (player != null) {
            restoreFlight(player);
        }
        HandlerList.unregisterAll(this);
        for (Map<Integer, List<NoteEvent>> m : scoreByLayer) m.clear();
        refByPos.clear();
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

        upsertNote(target.getLocation(), instrumentId, cell.pitch, activeLayerIndex);
        // Preview the note on placement.
        previewNote(instrumentId, cell.pitch);
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
            previewNote(removed.instrumentId, removed.pitch);
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
                || event.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD
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

        // Left click: long-distance remove note on track.
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            final Block target = raycastTrackCellBlock();
            if (target != null) {
                // Assist: remove note from active layer first if present, otherwise remove whatever is there.
                final BlockPos pos = BlockPos.from(target.getLocation());
                final NoteEvent removed = removeNoteAt(target);
                if (removed != null) {
                    previewNote(removed.instrumentId, removed.pitch);
                    event.setCancelled(true);
                }
            }
            return;
        }

        // Right click: we handle BOTH AIR and BLOCK.
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        // Basic debounce (some clients send duplicates)
        final long now = System.currentTimeMillis();
        if (now - lastRightClickMs < 75) {
            event.setCancelled(true);
            return;
        }
        lastRightClickMs = now;

        final ItemStack item = event.getItem();

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
                    upsertNote(target.getLocation(), instrumentId, cell.pitch, activeLayerIndex);
                    previewNote(instrumentId, cell.pitch);
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
        playhead = 0;

        playbackTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (player == null || !player.isOnline()) {
                stopPlayback();
                return;
            }
            if (session == null || session.world() == null) {
                stopPlayback();
                return;
            }

            drawPlayhead(playhead);
            playStep(playhead);
            playhead++;
            if (playhead >= TRACK_TIME_LENGTH) {
                stopPlayback();
                player.sendMessage(ChatColor.GRAY + "Playback finished.");
            }
        }, 0L, ticksPerStep);
    }

    private void stopPlayback() {
        if (playbackTask != null) {
            playbackTask.cancel();
            playbackTask = null;
        }
        clearPlayhead();
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
        final TrackCell cell = toCell(loc);
        if (cell == null) return;

        final int tickIndex = cell.timeIndex;
        final BlockPos pos = BlockPos.from(loc);

        final NoteRef old = refByPos.put(pos, new NoteRef(layerIndex, tickIndex));
        if (old != null) {
            final List<NoteEvent> oldList = scoreByLayer.get(old.layerIndex).get(old.tickIndex);
            if (oldList != null) {
                oldList.removeIf(n -> n.pos.equals(pos));
                if (oldList.isEmpty()) scoreByLayer.get(old.layerIndex).remove(old.tickIndex);
            }
        }

        scoreByLayer.get(layerIndex)
                .computeIfAbsent(tickIndex, k -> new ArrayList<>())
                .add(new NoteEvent(pos, instrumentId, pitch));
    }

    private NoteEvent removeNoteAt(Block block) {
        final BlockPos pos = BlockPos.from(block.getLocation());
        final NoteRef ref = refByPos.remove(pos);
        if (ref == null) return null;

        NoteEvent removed = null;
        final Map<Integer, List<NoteEvent>> layerScore = scoreByLayer.get(ref.layerIndex);
        final List<NoteEvent> list = layerScore.get(ref.tickIndex);
        if (list != null) {
            for (Iterator<NoteEvent> it = list.iterator(); it.hasNext(); ) {
                final NoteEvent n = it.next();
                if (n.pos.equals(pos)) {
                    removed = n;
                    it.remove();
                    break;
                }
            }
            if (list.isEmpty()) layerScore.remove(ref.tickIndex);
        }

        final World w = block.getWorld();
        final Material old = block.getType();
        block.setType(Material.AIR, false);

        w.spawnParticle(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5), 12, 0.2, 0.2, 0.2, old.createBlockData());
        w.playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_WOOL_BREAK, SoundCategory.BLOCKS, 0.8f, 1.0f);

        return removed;
    }

    private Block raycastTrackCellBlock() {
        if (player == null) return null;
        final World w = player.getWorld();
        if (!isInSessionWorld(w)) return null;

        // Use Bukkit's precise ray trace to get the actual hit block (not just the first block in the track footprint).
        final RayTraceResult rr = player.rayTraceBlocks(EDIT_RAY_DISTANCE, FluidCollisionMode.NEVER);
        if (rr == null) return null;

        Block hit = rr.getHitBlock();
        if (hit == null) return null;

        // When you target the SIDE of a block, we want the adjacent placement block.
        final BlockFace face = rr.getHitBlockFace();
        if (face != null) {
            hit = hit.getRelative(face);
        }

        final int x = hit.getX();
        final int z = hit.getZ();

        // Ensure (x,z) is inside the track footprint.
        final int dx = x - ORIGIN.getBlockX();
        final int dz = z - ORIGIN.getBlockZ();
        if (dx < 0 || dx >= TRACK_TIME_LENGTH) return null;
        if (dz < 0 || dz >= TRACK_PITCH_WIDTH) return null;

        // Snap to active layer at that column.
        return w.getBlockAt(x, layerY(activeLayerIndex), z);
    }

    private void buildTrack(World world) {
        // Build BASE_Y floor once, and clear all layers above.
        for (int dx = 0; dx < TRACK_TIME_LENGTH; dx++) {
            for (int dz = 0; dz < TRACK_PITCH_WIDTH; dz++) {
                final int x = ORIGIN.getBlockX() + dx;
                final int z = ORIGIN.getBlockZ() + dz;

                final Material floor = (dz & 1) == 0 ? Material.BROWN_CONCRETE : Material.TERRACOTTA;
                world.getBlockAt(x, BASE_Y, z).setType(floor, false);

                for (int layer = 0; layer < LAYER_COUNT; layer++) {
                    world.getBlockAt(x, layerY(layer), z).setType(Material.AIR, false);
                }

                if (dx == 0 || dz == 0 || dx == TRACK_TIME_LENGTH - 1 || dz == TRACK_PITCH_WIDTH - 1) {
                    world.getBlockAt(x, BASE_Y, z).setType(Material.BLACK_CONCRETE, false);
                }
            }
        }

        // Direction indicator
        final int zArrow = ORIGIN.getBlockZ() + TRACK_PITCH_WIDTH;
        for (int dx = 0; dx < TRACK_TIME_LENGTH; dx++) {
            world.getBlockAt(ORIGIN.getBlockX() + dx, BASE_Y, zArrow).setType(Material.DARK_OAK_PLANKS, false);
        }
        world.getBlockAt(ORIGIN.getBlockX() + TRACK_TIME_LENGTH - 1, BASE_Y, zArrow).setType(Material.GOLD_BLOCK, false);
        world.getBlockAt(ORIGIN.getBlockX() + TRACK_TIME_LENGTH - 2, BASE_Y, zArrow - 1).setType(Material.GOLD_BLOCK, false);
        world.getBlockAt(ORIGIN.getBlockX() + TRACK_TIME_LENGTH - 2, BASE_Y, zArrow + 1).setType(Material.GOLD_BLOCK, false);

        // Pitch markers
        final int zLow = ORIGIN.getBlockZ();
        final int zHigh = ORIGIN.getBlockZ() + TRACK_PITCH_WIDTH - 1;
        for (int dx = 0; dx < TRACK_TIME_LENGTH; dx++) {
            world.getBlockAt(ORIGIN.getBlockX() + dx, BASE_Y, zLow).setType(Material.DEEPSLATE_TILES, false);
            world.getBlockAt(ORIGIN.getBlockX() + dx, BASE_Y, zHigh).setType(Material.QUARTZ_BLOCK, false);
        }

        final Location view = new Location(world,
                ORIGIN.getX() + 1.5,
                TRACK_Y + 1.0,
                ORIGIN.getZ() + (TRACK_PITCH_WIDTH / 2.0) + 0.5,
                -90f,
                20f);
        player.teleportAsync(view);
    }

    private void playStep(int tickIndex) {
        for (int layer = 0; layer < layerCount; layer++) {
            final List<NoteEvent> events = scoreByLayer.get(layer).get(tickIndex);
            if (events == null || events.isEmpty()) continue;

            for (NoteEvent e : events) {
                final InstrumentPalette palette = InstrumentPalette.byId(e.instrumentId);
                final float pitch = pitchFromRow(e.pitch);
                player.playSound(player.getLocation(), palette.sound, SoundCategory.RECORDS, 1.0f, pitch);

                final Location at = e.pos.toLocation(session.world()).add(0.5, 0.8, 0.5);
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
        for (int dx = 0; dx < TRACK_TIME_LENGTH; dx++) {
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
    }

    private TrackCell toCell(Location loc) {
        // Accept any layer plane.
        final int y = loc.getBlockY();
        if (y < TRACK_Y || y >= TRACK_Y + LAYER_COUNT) return null;

        final int dx = loc.getBlockX() - ORIGIN.getBlockX();
        final int dz = loc.getBlockZ() - ORIGIN.getBlockZ();

        if (dx < 0 || dx >= TRACK_TIME_LENGTH) return null;
        if (dz < 0 || dz >= TRACK_PITCH_WIDTH) return null;

        return new TrackCell(dx, dz);
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

    private boolean isLayerTool(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        final PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return TYPE_LAYER_TOOL.equals(pdc.get(keyType, PersistentDataType.STRING));
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
        layerToolMeta.getPersistentDataContainer().set(keyType, PersistentDataType.STRING, TYPE_LAYER_TOOL);
        layerTool.setItemMeta(layerToolMeta);
        player.getInventory().setItem(INVENTORY_SLOT_LAYER_TOOL, layerTool);
    }

    private void applyWorldRules(World world) {
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);
        world.setClearWeatherDuration(Integer.MAX_VALUE);

        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.FALL_DAMAGE, false);
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRule.NATURAL_REGENERATION, false);
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

    private void redrawSettingsMainMenu() {
        settingsMainInventory.clear();

        final ItemStack tempo = new ItemStack(Material.CLOCK);
        final ItemMeta tempoMeta = tempo.getItemMeta();
        tempoMeta.setDisplayName(ChatColor.YELLOW + "Tempo");
        tempo.setItemMeta(tempoMeta);
        settingsMainInventory.setItem(MAIN_TEMPO_BUTTON_SLOT, tempo);

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

    private void handleSettingsMainClick(int rawSlot) {
        if (rawSlot == MAIN_TEMPO_BUTTON_SLOT) {
            openSettingsTempoMenu();
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

    private void setTicksPerStep(int newValue) {
        if (newValue == ticksPerStep) return;
        ticksPerStep = newValue;
        player.sendMessage(ChatColor.GRAY + "Tempo set to " + ticksPerStep + " ticks/step");
        if (playbackTask != null) startPlayback();
    }

    private record TrackCell(int timeIndex, int pitch) {
    }

    private record NoteEvent(BlockPos pos, int instrumentId, int pitch) {
    }

    private record NoteRef(int layerIndex, int tickIndex) {
    }
}
