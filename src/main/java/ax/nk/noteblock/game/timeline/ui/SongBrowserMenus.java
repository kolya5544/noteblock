package ax.nk.noteblock.game.timeline.ui;

import ax.nk.noteblock.persistence.SongRow;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Inventory UI to browse songs and pick one to load.
 *
 * Top 45 slots: songs (PAPER)
 * Bottom row: back/close
 */
public final class SongBrowserMenus {

    public enum Mode {
        LOAD,
        DELETE
    }

    public interface Callbacks {
        void back();

        void close();

        void prevPage(int newPage);

        void nextPage(int newPage);

        void toggleDeleteMode(boolean enabled);

        void loadSong(long songId);

        void requestDelete(long songId, String songName);
    }

    private static final String MENU_TITLE = "Library > Load";

    private static final int SONG_SLOTS = 9 * 5;
    private static final int PREV_SLOT = 45;
    private static final int MODE_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final int BACK_SLOT = 46;
    private static final int CLOSE_SLOT = 52;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final NamespacedKey keySongId;

    private Inventory inv;
    private int pageIndex = 0;
    private boolean hasPrev = false;
    private boolean hasNext = false;
    private Mode mode = Mode.LOAD;

    public int pageIndex() {
        return pageIndex;
    }

    public Mode mode() {
        return mode;
    }

    public SongBrowserMenus(Plugin plugin) {
        this.keySongId = new NamespacedKey(plugin, "nb_song_id");
    }

    public void invalidate() {
        inv = null;
    }

    public void open(Player player) {
        if (inv == null) {
            inv = Bukkit.createInventory(null, 9 * 6, MENU_TITLE);
        }
        player.openInventory(inv);
    }

    public boolean isView(InventoryView view) {
        return inv != null && view.getTopInventory().equals(inv);
    }

    public void render(List<SongRow> songs, int pageIndex, boolean hasPrev, boolean hasNext, Mode mode) {
        if (inv == null) return;
        this.pageIndex = Math.max(0, pageIndex);
        this.hasPrev = hasPrev;
        this.hasNext = hasNext;
        this.mode = mode == null ? Mode.LOAD : mode;

        inv.clear();

        if (songs != null) {
            int slot = 0;
            for (SongRow s : songs) {
                if (slot >= SONG_SLOTS) break;

                final ItemStack it = new ItemStack(Material.PAPER);
                final ItemMeta meta = it.getItemMeta();
                meta.setDisplayName((this.mode == Mode.DELETE ? ChatColor.RED : ChatColor.YELLOW) + s.name());
                meta.setLore(List.of(
                        ChatColor.GRAY + "Updated: " + TIME_FMT.format(Instant.ofEpochMilli(s.updatedAtMs())),
                        this.mode == Mode.DELETE
                                ? ChatColor.DARK_RED + "Click to delete"
                                : ChatColor.GRAY + "Click to load"
                ));
                meta.getPersistentDataContainer().set(keySongId, PersistentDataType.LONG, s.id());
                it.setItemMeta(meta);

                inv.setItem(slot, it);
                slot++;
            }
        }

        final ItemStack prev = new ItemStack(Material.ARROW);
        final ItemMeta prevMeta = prev.getItemMeta();
        prevMeta.setDisplayName(hasPrev ? ChatColor.YELLOW + "Previous" : ChatColor.DARK_GRAY + "Previous");
        prev.setItemMeta(prevMeta);
        inv.setItem(PREV_SLOT, prev);

        final ItemStack back = new ItemStack(Material.OAK_DOOR);
        final ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName(ChatColor.YELLOW + "Back");
        back.setItemMeta(backMeta);
        inv.setItem(BACK_SLOT, back);

        final ItemStack modeItem = new ItemStack(this.mode == Mode.DELETE ? Material.RED_DYE : Material.GRAY_DYE);
        final ItemMeta modeMeta = modeItem.getItemMeta();
        modeMeta.setDisplayName(this.mode == Mode.DELETE ? ChatColor.RED + "Delete mode: ON" : ChatColor.GRAY + "Delete mode: OFF");
        modeMeta.setLore(List.of(ChatColor.DARK_GRAY + "Toggle delete mode"));
        modeItem.setItemMeta(modeMeta);
        inv.setItem(MODE_SLOT, modeItem);

        final ItemStack close = new ItemStack(Material.BARRIER);
        final ItemMeta closeMeta = close.getItemMeta();
        closeMeta.setDisplayName(ChatColor.GRAY + "Close");
        close.setItemMeta(closeMeta);
        inv.setItem(CLOSE_SLOT, close);

        final ItemStack next = new ItemStack(Material.ARROW);
        final ItemMeta nextMeta = next.getItemMeta();
        nextMeta.setDisplayName(hasNext ? ChatColor.YELLOW + "Next" : ChatColor.DARK_GRAY + "Next");
        next.setItemMeta(nextMeta);
        inv.setItem(NEXT_SLOT, next);

        fillBackground(inv);
    }

    public void handleClick(Player player, InventoryView view, int rawSlot, Callbacks cb) {
        if (!isView(view)) return;

        if (rawSlot == BACK_SLOT) {
            cb.back();
            return;
        }
        if (rawSlot == CLOSE_SLOT) {
            cb.close();
            return;
        }
        if (rawSlot == PREV_SLOT && hasPrev) {
            cb.prevPage(pageIndex - 1);
            return;
        }
        if (rawSlot == NEXT_SLOT && hasNext) {
            cb.nextPage(pageIndex + 1);
            return;
        }
        if (rawSlot == MODE_SLOT) {
            final boolean enableDelete = mode != Mode.DELETE;
            cb.toggleDeleteMode(enableDelete);
            return;
        }

        if (rawSlot < 0 || rawSlot >= SONG_SLOTS) return;
        final ItemStack clicked = view.getTopInventory().getItem(rawSlot);
        if (clicked == null || !clicked.hasItemMeta()) return;

        final Long id = clicked.getItemMeta().getPersistentDataContainer().get(keySongId, PersistentDataType.LONG);
        if (id == null) return;

        final String songName = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());

        if (mode == Mode.DELETE) {
            cb.requestDelete(id, songName == null ? ("#" + id) : songName);
        } else {
            cb.loadSong(id);
        }
    }

    private void fillBackground(Inventory inv) {
        final ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        final ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);

        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }
    }
}
