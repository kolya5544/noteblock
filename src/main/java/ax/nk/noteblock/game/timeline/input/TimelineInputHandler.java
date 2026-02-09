package ax.nk.noteblock.game.timeline.input;

import ax.nk.noteblock.game.timeline.*;
import ax.nk.noteblock.game.timeline.edit.TimelineEditor;
import ax.nk.noteblock.game.timeline.score.TimelineCell;
import ax.nk.noteblock.game.timeline.score.TimelineScore;
import ax.nk.noteblock.game.timeline.ui.ControlItems;
import ax.nk.noteblock.game.timeline.ui.SettingsMenus;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * All Bukkit input handlers for timeline editing/playback.
 * Keeps TimelineController small.
 */
public final class TimelineInputHandler implements Listener {

    private final ControlItems controlItems;
    private final SettingsMenus settingsMenus;
    private final TimelineEditor editor;
    private final TimelineScore score;
    private final TrackTargeting targeting;

    private final Supplier<Player> player;
    private final Supplier<World> world;

    private final IntSupplier trackLength;
    private final IntSupplier activeLayerY;
    private final IntSupplier activeLayerIndex;

    private final Runnable togglePlayback;

    private final IntConsumer setRangeBegin;
    private final IntConsumer setRangeEnd;
    private final Runnable resetRange;

    private final Runnable onCycleLayer;
    private final Runnable onMaybeRemoveCurrentLayerOrAdd;

    private final Supplier<SettingsMenus.Callbacks> settingsCallbacks;

    private final Supplier<Boolean> debugInput;

    private long lastRightClickMs;

    public TimelineInputHandler(ControlItems controlItems,
                               SettingsMenus settingsMenus,
                               TimelineEditor editor,
                               TimelineScore score,
                               TrackTargeting targeting,
                               Supplier<Player> player,
                               Supplier<World> world,
                               IntSupplier trackLength,
                               IntSupplier activeLayerY,
                               IntSupplier activeLayerIndex,
                               Runnable togglePlayback,
                               IntConsumer setRangeBegin,
                               IntConsumer setRangeEnd,
                               Runnable resetRange,
                               Runnable onCycleLayer,
                               Runnable onMaybeRemoveCurrentLayerOrAdd,
                               Supplier<SettingsMenus.Callbacks> settingsCallbacks,
                               Supplier<Boolean> debugInput) {
        this.controlItems = Objects.requireNonNull(controlItems);
        this.settingsMenus = Objects.requireNonNull(settingsMenus);
        this.editor = Objects.requireNonNull(editor);
        this.score = Objects.requireNonNull(score);
        this.targeting = Objects.requireNonNull(targeting);
        this.player = Objects.requireNonNull(player);
        this.world = Objects.requireNonNull(world);
        this.trackLength = Objects.requireNonNull(trackLength);
        this.activeLayerY = Objects.requireNonNull(activeLayerY);
        this.activeLayerIndex = Objects.requireNonNull(activeLayerIndex);
        this.togglePlayback = Objects.requireNonNull(togglePlayback);
        this.setRangeBegin = Objects.requireNonNull(setRangeBegin);
        this.setRangeEnd = Objects.requireNonNull(setRangeEnd);
        this.resetRange = Objects.requireNonNull(resetRange);
        this.onCycleLayer = Objects.requireNonNull(onCycleLayer);
        this.onMaybeRemoveCurrentLayerOrAdd = Objects.requireNonNull(onMaybeRemoveCurrentLayerOrAdd);
        this.settingsCallbacks = Objects.requireNonNull(settingsCallbacks);
        this.debugInput = Objects.requireNonNull(debugInput);
    }

    private boolean isSessionPlayer(Player p) {
        final Player sp = player.get();
        return sp != null && p != null && p.getUniqueId().equals(sp.getUniqueId());
    }

    private boolean isInSessionWorld(World w) {
        final World sw = world.get();
        return sw != null && w != null && sw.equals(w);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (!isSessionPlayer(event.getPlayer())) return;
        if (!isInSessionWorld(event.getBlock().getWorld())) return;

        final ItemStack inHand = event.getItemInHand();
        final Integer instrumentId = controlItems.getInstrumentId(inHand);
        if (instrumentId == null) return;

        final Location loc = event.getBlockPlaced().getLocation();
        final World w = loc.getWorld();
        if (w == null) return;

        final Block target = w.getBlockAt(loc.getBlockX(), activeLayerY.getAsInt(), loc.getBlockZ());
        final TimelineCell cell = editor.toCell(target.getLocation(), trackLength.getAsInt());
        if (cell == null) {
            event.setCancelled(true);
            final Player p = player.get();
            if (p != null) p.sendMessage(ChatColor.RED + "Place notes on the track only.");
            return;
        }

        final Material marker = InstrumentPalette.byId(instrumentId).marker;
        target.setType(marker, false);

        event.setCancelled(true);
        event.getPlayer().getInventory().setItem(event.getHand(), controlItems.normalizeTokenStack(inHand));

        editor.upsertNote(score, target.getLocation(), instrumentId, activeLayerIndex.getAsInt(), trackLength.getAsInt());
        editor.previewNote(player.get(), instrumentId, cell.pitch());

        // If currently playing, restart so new notes are picked up immediately.
        final SettingsMenus.Callbacks cb = settingsCallbacks.get();
        if (cb != null && cb.isPlaying()) {
            cb.setTicksPerStep(cb.ticksPerStep());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isSessionPlayer(event.getPlayer())) return;
        if (!isInSessionWorld(event.getBlock().getWorld())) return;

        final Block block = event.getBlock();
        final NoteEvent removed = editor.removeNoteAt(score, block);
        if (removed != null) {
            event.setCancelled(true);
            editor.previewNote(player.get(), removed.instrumentId(), removed.pitch());

            final SettingsMenus.Callbacks cb = settingsCallbacks.get();
            if (cb != null && cb.isPlaying()) {
                cb.setTicksPerStep(cb.ticksPerStep());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (!isSessionPlayer(p)) return;
        if (!isInSessionWorld(p.getWorld())) return;

        if (settingsMenus.isAnyMenuView(event.getView())) {
            event.setCancelled(true);
            settingsMenus.handleClick(p, event.getView(), event.getRawSlot(), settingsCallbacks.get());
            return;
        }

        final ItemStack current = event.getCurrentItem();
        final ItemStack cursor = event.getCursor();
        if (!controlItems.isStartItem(current) && !controlItems.isStartItem(cursor)
                && !controlItems.isSettingsItem(current) && !controlItems.isSettingsItem(cursor)) {
            return;
        }

        if (event.getClick() == ClickType.DROP
                || event.getClick() == ClickType.CONTROL_DROP
                || event.getAction() == InventoryAction.HOTBAR_SWAP
                || event.getAction() == InventoryAction.CLONE_STACK) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        final Player sp = player.get();
        final World sw = world.get();
        if (sp == null || sw == null) return;

        if (!isSessionPlayer(event.getPlayer())) return;
        if (!isInSessionWorld(event.getPlayer().getWorld())) return;

        if (Boolean.TRUE.equals(debugInput.get())) {
            final String item = event.getItem() == null ? "null" : event.getItem().getType().name();
            sp.sendMessage(ChatColor.DARK_GRAY + "Interact action=" + event.getAction() + " item=" + item);
        }

        final Action action = event.getAction();
        final ItemStack item = event.getItem();

        if (controlItems.isRangeTool(item)
                && (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK
                || action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) {
            event.setCancelled(true);

            if (event.getPlayer().isSneaking()) {
                resetRange.run();
                sp.sendActionBar(ChatColor.GRAY + "Range cleared");
                sp.playSound(sp.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.4f, 0.9f);
                return;
            }

            final Integer idx = targeting.raycastTimeIndex(sp, sw, trackLength.getAsInt(), activeLayerY.getAsInt());
            if (idx == null) {
                sp.sendActionBar(ChatColor.RED + "Look at the track to set a range.");
                return;
            }

            if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                setRangeBegin.accept(idx);
                sp.sendActionBar(ChatColor.AQUA + "Begin: " + ChatColor.WHITE + idx);
            } else {
                setRangeEnd.accept(idx);
                sp.sendActionBar(ChatColor.GOLD + "End: " + ChatColor.WHITE + idx);
            }
            sp.playSound(sp.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.4f, 1.2f);
            return;
        }

        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            final Block target = targeting.raycastTrackCellBlock(sp, sw, trackLength.getAsInt(), activeLayerY.getAsInt());
            if (target != null) {
                final NoteEvent removed = editor.removeNoteAt(score, target);
                if (removed != null) {
                    editor.previewNote(sp, removed.instrumentId(), removed.pitch());
                    event.setCancelled(true);
                }
            }
            return;
        }

        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        final long now = System.currentTimeMillis();
        if (now - lastRightClickMs < 75) {
            event.setCancelled(true);
            return;
        }
        lastRightClickMs = now;

        if (controlItems.isSettingsItem(item)) {
            event.setCancelled(true);
            settingsMenus.openMain(sp, settingsCallbacks.get());
            return;
        }

        if (controlItems.isStartItem(item)) {
            event.setCancelled(true);
            togglePlayback.run();
            return;
        }

        if (controlItems.isLayerTool(item)) {
            event.setCancelled(true);
            if (event.getPlayer().isSneaking()) {
                onMaybeRemoveCurrentLayerOrAdd.run();
            } else {
                onCycleLayer.run();
            }
            sp.playSound(sp.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.4f, 1.2f);
            return;
        }

        final Integer instrumentId = controlItems.getInstrumentId(item);
        if (instrumentId != null) {
            final Block target = targeting.raycastTrackCellBlock(sp, sw, trackLength.getAsInt(), activeLayerY.getAsInt());
            if (target != null) {
                final TimelineCell cell = editor.toCell(target.getLocation(), trackLength.getAsInt());
                if (cell != null) {
                    event.setCancelled(true);
                    final Material marker = InstrumentPalette.byId(instrumentId).marker;
                    target.setType(marker, false);
                    editor.upsertNote(score, target.getLocation(), instrumentId, activeLayerIndex.getAsInt(), trackLength.getAsInt());
                    editor.previewNote(sp, instrumentId, cell.pitch());
                    sp.getInventory().setItemInMainHand(controlItems.normalizeTokenStack(item));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!isSessionPlayer(event.getPlayer())) return;
        if (!isInSessionWorld(event.getPlayer().getWorld())) return;

        final Integer id = controlItems.getInstrumentId(event.getMainHandItem());
        if (id == null) return;

        event.setCancelled(true);
        onCycleLayer.run();
    }
}
