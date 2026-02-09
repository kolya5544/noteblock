package ax.nk.noteblock.game.timeline.ui;

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
 * Simple "Library" menu. For now it's just a single button: Save song...
 */
public final class LibraryMenus {

    public interface Callbacks {
        void saveSong();

        void loadSong();

        void close();
    }

    private static final String MENU_LIBRARY_TITLE = "Library";

    private static final int SAVE_BUTTON_SLOT = 12;
    private static final int LOAD_BUTTON_SLOT = 14;
    private static final int CLOSE_SLOT = 31;

    private Inventory libraryInventory;

    public void invalidate() {
        libraryInventory = null;
    }

    public void openMain(Player player, Callbacks cb) {
        if (libraryInventory == null) {
            libraryInventory = Bukkit.createInventory(null, 9 * 4, MENU_LIBRARY_TITLE);
        }
        redrawMain(cb);
        player.openInventory(libraryInventory);
    }

    public boolean isMainView(InventoryView view) {
        return libraryInventory != null && view.getTopInventory().equals(libraryInventory);
    }

    public boolean isAnyMenuView(InventoryView view) {
        return isMainView(view);
    }

    public void handleClick(Player player, InventoryView view, int rawSlot, Callbacks cb) {
        if (!isMainView(view)) return;
        if (rawSlot == SAVE_BUTTON_SLOT) {
            cb.saveSong();
        } else if (rawSlot == LOAD_BUTTON_SLOT) {
            cb.loadSong();
        } else if (rawSlot == CLOSE_SLOT) {
            cb.close();
        }
    }

    private void redrawMain(Callbacks cb) {
        libraryInventory.clear();

        final ItemStack save = new ItemStack(Material.WRITABLE_BOOK);
        final ItemMeta saveMeta = save.getItemMeta();
        saveMeta.setDisplayName(ChatColor.YELLOW + "Save song...");
        saveMeta.setLore(List.of(ChatColor.GRAY + "Save the current song to your library"));
        save.setItemMeta(saveMeta);
        libraryInventory.setItem(SAVE_BUTTON_SLOT, save);

        final ItemStack load = new ItemStack(Material.PAPER);
        final ItemMeta loadMeta = load.getItemMeta();
        loadMeta.setDisplayName(ChatColor.YELLOW + "Load song...");
        loadMeta.setLore(List.of(ChatColor.GRAY + "Replace the current song"));
        load.setItemMeta(loadMeta);
        libraryInventory.setItem(LOAD_BUTTON_SLOT, load);

        final ItemStack close = new ItemStack(Material.BARRIER);
        final ItemMeta closeMeta = close.getItemMeta();
        closeMeta.setDisplayName(ChatColor.GRAY + "Close");
        close.setItemMeta(closeMeta);
        libraryInventory.setItem(CLOSE_SLOT, close);

        fillMenuBackground(libraryInventory);
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
}
