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

/** Confirmation dialog for deleting a song. */
public final class DeleteConfirmMenu {

    public interface Callbacks {
        void confirmDelete(long songId);

        void cancel();
    }

    private static final String TITLE = "Delete song?";
    private static final int CONFIRM_SLOT = 11;
    private static final int CANCEL_SLOT = 15;

    private Inventory inv;
    private Long currentSongId;

    public void invalidate() {
        inv = null;
        currentSongId = null;
    }

    public void open(Player player, long songId, String songName) {
        if (inv == null) {
            inv = Bukkit.createInventory(null, 9 * 3, TITLE);
        }
        currentSongId = songId;
        redraw(songName);
        player.openInventory(inv);
    }

    public boolean isView(InventoryView view) {
        return inv != null && view.getTopInventory().equals(inv);
    }

    public void handleClick(InventoryView view, int rawSlot, Callbacks cb) {
        if (!isView(view)) return;
        if (currentSongId == null) return;

        if (rawSlot == CONFIRM_SLOT) {
            cb.confirmDelete(currentSongId);
        } else if (rawSlot == CANCEL_SLOT) {
            cb.cancel();
        }
    }

    private void redraw(String songName) {
        inv.clear();

        final ItemStack confirm = new ItemStack(Material.RED_CONCRETE);
        final ItemMeta confirmMeta = confirm.getItemMeta();
        confirmMeta.setDisplayName(ChatColor.RED + "Delete");
        confirmMeta.setLore(List.of(
                ChatColor.GRAY + "Song: " + ChatColor.WHITE + songName,
                ChatColor.DARK_RED + "This can't be undone."
        ));
        confirm.setItemMeta(confirmMeta);
        inv.setItem(CONFIRM_SLOT, confirm);

        final ItemStack cancel = new ItemStack(Material.GRAY_CONCRETE);
        final ItemMeta cancelMeta = cancel.getItemMeta();
        cancelMeta.setDisplayName(ChatColor.GRAY + "Cancel");
        cancel.setItemMeta(cancelMeta);
        inv.setItem(CANCEL_SLOT, cancel);

        fill(inv);
    }

    private void fill(Inventory inv) {
        final ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        final ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);

        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }
    }
}

