package ax.nk.noteblock.game.timeline.ui;

import ax.nk.noteblock.game.timeline.InstrumentPalette;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Creates and identifies timeline control items (instrument tokens, start/settings/layer/range tools).
 */
public final class ControlItems {
    public static final String TYPE_INSTRUMENT = "instrument";
    public static final String TYPE_START = "start";
    public static final String TYPE_SETTINGS = "settings";
    public static final String TYPE_LIBRARY = "library";
    public static final String TYPE_LAYER_TOOL = "layer_tool";
    public static final String TYPE_RANGE_TOOL = "range_tool";

    // Player inventory indices:
    // - Hotbar: 0..8
    // - Row above hotbar: 9..17
    // - Next row: 18..26
    // - Top row: 27..35
    public static final int INVENTORY_SLOT_LIBRARY = 35;     // top-right
    public static final int INVENTORY_SLOT_RANGE_TOOL = 26;  // below library
    public static final int INVENTORY_SLOT_LAYER_TOOL = 17;  // below range

    public static final int INVENTORY_SLOT_START = 8;
    public static final int INVENTORY_SLOT_SETTINGS = 7;

    private final NamespacedKey keyType;
    private final NamespacedKey keyInstrument;

    public ControlItems(Plugin plugin) {
        this.keyType = new NamespacedKey(plugin, "nb_type");
        this.keyInstrument = new NamespacedKey(plugin, "nb_instrument");
    }

    public boolean isLayerTool(ItemStack item) {
        return hasType(item, TYPE_LAYER_TOOL);
    }

    public boolean isRangeTool(ItemStack item) {
        return hasType(item, TYPE_RANGE_TOOL);
    }

    public boolean isStartItem(ItemStack item) {
        return hasType(item, TYPE_START);
    }

    public boolean isSettingsItem(ItemStack item) {
        return hasType(item, TYPE_SETTINGS);
    }

    public boolean isLibraryItem(ItemStack item) {
        return hasType(item, TYPE_LIBRARY);
    }

    public Integer getInstrumentId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        final ItemMeta meta = item.getItemMeta();
        final PersistentDataContainer pdc = meta.getPersistentDataContainer();
        final String type = pdc.get(keyType, PersistentDataType.STRING);
        if (!TYPE_INSTRUMENT.equals(type)) return null;
        return pdc.get(keyInstrument, PersistentDataType.INTEGER);
    }

    public ItemStack normalizeTokenStack(ItemStack inHand) {
        if (inHand == null) return null;
        if (getInstrumentId(inHand) == null) return inHand;
        if (inHand.getAmount() == 1) return inHand;
        final ItemStack copy = inHand.clone();
        copy.setAmount(1);
        return copy;
    }

    public void giveItems(Player player) {
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

        final ItemStack library = new ItemStack(Material.BOOK, 1);
        final ItemMeta libraryMeta = library.getItemMeta();
        libraryMeta.setDisplayName(ChatColor.GOLD + "Library");
        libraryMeta.setLore(List.of(ChatColor.GRAY + "Save / load songs"));
        libraryMeta.getPersistentDataContainer().set(keyType, PersistentDataType.STRING, TYPE_LIBRARY);
        library.setItemMeta(libraryMeta);
        player.getInventory().setItem(INVENTORY_SLOT_LIBRARY, library);

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

    private boolean hasType(ItemStack item, String expected) {
        if (item == null || !item.hasItemMeta()) return false;
        final PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return expected.equals(pdc.get(keyType, PersistentDataType.STRING));
    }
}
