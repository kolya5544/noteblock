package ax.nk.noteblock.game.timeline.ui;

import ax.nk.noteblock.game.timeline.util.TimelineMath;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Owns and renders the timeline settings GUIs and translates clicks into callbacks.
 */
public final class SettingsMenus {

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

    // Minus should be on the left, plus on the right.
    private static final int LENGTH_MINUS_50_SLOT = 19;
    private static final int LENGTH_MINUS_10_SLOT = 20;
    private static final int LENGTH_MINUS_1_SLOT = 21;

    private static final int LENGTH_PLUS_1_SLOT = 25;
    private static final int LENGTH_PLUS_10_SLOT = 24;
    private static final int LENGTH_PLUS_50_SLOT = 23;
    private static final int LENGTH_BACK_SLOT = 30;
    private static final int LENGTH_CLOSE_SLOT = 32;

    public interface Callbacks {
        void openTempo();

        void openLength();

        void close();

        void setLoopEnabled(boolean enabled);

        void setTicksPerStep(int newValue);

        void adjustTrackLength(int delta);

        // getters
        int ticksPerStep();

        int trackLength();

        int maxTrackLength();

        int minTrackLength();

        boolean loopEnabled();

        boolean isPlaying();

        void resetPlayheadToRangeStartIfLooping();
    }

    private Inventory settingsMainInventory;
    private Inventory settingsTempoInventory;
    private Inventory settingsLengthInventory;

    private long lastTempoClickMs;
    private long lastLengthClickMs;

    private static final long CLICK_DEBOUNCE_MS = 140;

    public void invalidate() {
        settingsMainInventory = null;
        settingsTempoInventory = null;
        settingsLengthInventory = null;
    }

    public void openMain(Player player, Callbacks cb) {
        if (settingsMainInventory == null) {
            settingsMainInventory = Bukkit.createInventory(null, 9 * 4, MENU_SETTINGS_MAIN_TITLE);
        }
        redrawMain(cb);
        player.openInventory(settingsMainInventory);
    }

    public void openTempo(Player player, Callbacks cb) {
        if (settingsTempoInventory == null) {
            settingsTempoInventory = Bukkit.createInventory(null, 9 * 4, MENU_SETTINGS_TEMPO_TITLE);
        }
        redrawTempo(cb);
        player.openInventory(settingsTempoInventory);
    }

    public void openLength(Player player, Callbacks cb) {
        if (settingsLengthInventory == null) {
            settingsLengthInventory = Bukkit.createInventory(null, 9 * 4, MENU_SETTINGS_LENGTH_TITLE);
        }
        redrawLength(cb);
        player.openInventory(settingsLengthInventory);
    }

    public boolean isAnyMenuView(InventoryView view) {
        return isMainView(view) || isTempoView(view) || isLengthView(view);
    }

    public boolean isMainView(InventoryView view) {
        return settingsMainInventory != null && view.getTopInventory().equals(settingsMainInventory);
    }

    public boolean isTempoView(InventoryView view) {
        return settingsTempoInventory != null && view.getTopInventory().equals(settingsTempoInventory);
    }

    public boolean isLengthView(InventoryView view) {
        return settingsLengthInventory != null && view.getTopInventory().equals(settingsLengthInventory);
    }

    public void handleClick(Player player, InventoryView view, int rawSlot, Callbacks cb) {
        if (isMainView(view)) {
            handleMainClick(player, rawSlot, cb);
            return;
        }
        if (isTempoView(view)) {
            handleTempoClick(player, rawSlot, cb);
            return;
        }
        if (isLengthView(view)) {
            handleLengthClick(player, rawSlot, cb);
        }
    }

    private void handleMainClick(Player player, int rawSlot, Callbacks cb) {
        if (rawSlot == MAIN_TEMPO_BUTTON_SLOT) {
            cb.openTempo();
        } else if (rawSlot == MAIN_LENGTH_BUTTON_SLOT) {
            cb.openLength();
        } else if (rawSlot == MAIN_LOOP_BUTTON_SLOT) {
            final boolean enabled = !cb.loopEnabled();
            cb.setLoopEnabled(enabled);
            player.sendMessage(ChatColor.GRAY + "Loop " + (enabled ? "enabled" : "disabled"));

            if (cb.isPlaying() && enabled) {
                cb.resetPlayheadToRangeStartIfLooping();
            }

            redrawMain(cb);
        } else if (rawSlot == MAIN_CLOSE_SLOT) {
            cb.close();
        }
    }

    private static boolean shouldDebounce(long nowMs, long lastMs) {
        return nowMs - lastMs < CLICK_DEBOUNCE_MS;
    }

    private void handleTempoClick(Player player, int rawSlot, Callbacks cb) {
        final long now = System.currentTimeMillis();
        if (shouldDebounce(now, lastTempoClickMs)) return;
        lastTempoClickMs = now;

        if (rawSlot == TEMPO_MINUS_SLOT) {
            cb.setTicksPerStep(cb.ticksPerStep() + 1);
            redrawTempo(cb);
        } else if (rawSlot == TEMPO_PLUS_SLOT) {
            cb.setTicksPerStep(cb.ticksPerStep() - 1);
            redrawTempo(cb);
        } else if (rawSlot == TEMPO_BACK_SLOT) {
            cb.close();
            openMain(player, cb);
        } else if (rawSlot == TEMPO_CLOSE_SLOT) {
            cb.close();
        }
    }

    private void handleLengthClick(Player player, int rawSlot, Callbacks cb) {
        final long now = System.currentTimeMillis();
        if (shouldDebounce(now, lastLengthClickMs)) return;
        lastLengthClickMs = now;

        if (rawSlot == LENGTH_PLUS_1_SLOT) {
            cb.adjustTrackLength(1);
        } else if (rawSlot == LENGTH_PLUS_10_SLOT) {
            cb.adjustTrackLength(10);
        } else if (rawSlot == LENGTH_PLUS_50_SLOT) {
            cb.adjustTrackLength(50);
        } else if (rawSlot == LENGTH_MINUS_1_SLOT) {
            cb.adjustTrackLength(-1);
        } else if (rawSlot == LENGTH_MINUS_10_SLOT) {
            cb.adjustTrackLength(-10);
        } else if (rawSlot == LENGTH_MINUS_50_SLOT) {
            cb.adjustTrackLength(-50);
        } else if (rawSlot == LENGTH_BACK_SLOT) {
            cb.close();
            openMain(player, cb);
            return;
        } else if (rawSlot == LENGTH_CLOSE_SLOT) {
            cb.close();
            return;
        }

        redrawLength(cb);
    }

    private void redrawMain(Callbacks cb) {
        settingsMainInventory.clear();

        final ItemStack tempo = new ItemStack(Material.CLOCK);
        final ItemMeta tempoMeta = tempo.getItemMeta();
        tempoMeta.setDisplayName(ChatColor.YELLOW + "Tempo");
        tempo.setItemMeta(tempoMeta);
        settingsMainInventory.setItem(MAIN_TEMPO_BUTTON_SLOT, tempo);

        final ItemStack length = new ItemStack(Material.OAK_SIGN);
        final ItemMeta lengthMeta = length.getItemMeta();
        lengthMeta.setDisplayName(ChatColor.YELLOW + "Track Length");
        lengthMeta.setLore(List.of(ChatColor.GRAY + "Current: " + cb.trackLength()));
        length.setItemMeta(lengthMeta);
        settingsMainInventory.setItem(MAIN_LENGTH_BUTTON_SLOT, length);

        final ItemStack loop = new ItemStack(cb.loopEnabled() ? Material.LIME_DYE : Material.GRAY_DYE);
        final ItemMeta loopMeta = loop.getItemMeta();
        loopMeta.setDisplayName(ChatColor.YELLOW + "Loop");
        loopMeta.setLore(List.of(ChatColor.GRAY + "Click to toggle", ChatColor.GRAY + "Current: " + (cb.loopEnabled() ? "ON" : "OFF")));
        loop.setItemMeta(loopMeta);
        settingsMainInventory.setItem(MAIN_LOOP_BUTTON_SLOT, loop);

        final ItemStack close = new ItemStack(Material.BARRIER);
        final ItemMeta closeMeta = close.getItemMeta();
        closeMeta.setDisplayName(ChatColor.GRAY + "Close");
        close.setItemMeta(closeMeta);
        settingsMainInventory.setItem(MAIN_CLOSE_SLOT, close);

        fillMenuBackground(settingsMainInventory);
    }

    private void redrawTempo(Callbacks cb) {
        settingsTempoInventory.clear();

        final ItemStack tempo = new ItemStack(Material.CLOCK);
        final ItemMeta tempoMeta = tempo.getItemMeta();
        tempoMeta.setDisplayName(ChatColor.YELLOW + "Tempo: " + ChatColor.WHITE + cb.ticksPerStep() + " t/step");
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

    private void redrawLength(Callbacks cb) {
        settingsLengthInventory.clear();

        final ItemStack length = new ItemStack(Material.OAK_SIGN);
        final ItemMeta lengthMeta = length.getItemMeta();
        lengthMeta.setDisplayName(ChatColor.YELLOW + "Track Length: " + ChatColor.WHITE + cb.trackLength());
        lengthMeta.setLore(List.of(ChatColor.GRAY + "Max: " + cb.maxTrackLength()));
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

    public int clampTicksPerStep(int ticksPerStep, int min, int max) {
        return TimelineMath.clamp(ticksPerStep, min, max);
    }

    public int clampTrackLength(int trackLength, int min, int max) {
        return TimelineMath.clamp(trackLength, min, max);
    }
}

