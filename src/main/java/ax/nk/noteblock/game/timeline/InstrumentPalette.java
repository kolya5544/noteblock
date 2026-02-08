package ax.nk.noteblock.game.timeline;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;

import java.util.Arrays;

/**
 * 16 instruments. We represent each as:
 * - an inventory "token" (what the player holds to place that instrument)
 * - a placed marker material (visual on the timeline)
 * - a sound to play
 */
public enum InstrumentPalette {
    PIANO(0, ChatColor.WHITE + "Piano", Material.WHITE_WOOL, Material.WHITE_WOOL, Sound.BLOCK_NOTE_BLOCK_HARP),
    BASS(1, ChatColor.DARK_GRAY + "Bass", Material.GRAY_WOOL, Material.GRAY_WOOL, Sound.BLOCK_NOTE_BLOCK_BASS),
    SNARE(2, ChatColor.RED + "Snare", Material.RED_WOOL, Material.RED_WOOL, Sound.BLOCK_NOTE_BLOCK_SNARE),
    HAT(3, ChatColor.YELLOW + "Hat", Material.YELLOW_WOOL, Material.YELLOW_WOOL, Sound.BLOCK_NOTE_BLOCK_HAT),
    BASEDRUM(4, ChatColor.GOLD + "Basedrum", Material.ORANGE_WOOL, Material.ORANGE_WOOL, Sound.BLOCK_NOTE_BLOCK_BASEDRUM),
    BELL(5, ChatColor.AQUA + "Bell", Material.LIGHT_BLUE_WOOL, Material.LIGHT_BLUE_WOOL, Sound.BLOCK_NOTE_BLOCK_BELL),
    FLUTE(6, ChatColor.BLUE + "Flute", Material.BLUE_WOOL, Material.BLUE_WOOL, Sound.BLOCK_NOTE_BLOCK_FLUTE),
    CHIME(7, ChatColor.DARK_AQUA + "Chime", Material.CYAN_WOOL, Material.CYAN_WOOL, Sound.BLOCK_NOTE_BLOCK_CHIME),
    GUITAR(8, ChatColor.GREEN + "Guitar", Material.GREEN_WOOL, Material.GREEN_WOOL, Sound.BLOCK_NOTE_BLOCK_GUITAR),
    XYLOPHONE(9, ChatColor.DARK_GREEN + "Xylophone", Material.LIME_WOOL, Material.LIME_WOOL, Sound.BLOCK_NOTE_BLOCK_XYLOPHONE),
    IRON_XYLOPHONE(10, ChatColor.GRAY + "Iron Xylophone", Material.LIGHT_GRAY_WOOL, Material.LIGHT_GRAY_WOOL, Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE),
    COW_BELL(11, ChatColor.YELLOW + "Cow Bell", Material.BROWN_WOOL, Material.BROWN_WOOL, Sound.BLOCK_NOTE_BLOCK_COW_BELL),
    DIDGERIDOO(12, ChatColor.DARK_PURPLE + "Didgeridoo", Material.PURPLE_WOOL, Material.PURPLE_WOOL, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO),
    BIT(13, ChatColor.DARK_BLUE + "Bit", Material.MAGENTA_WOOL, Material.MAGENTA_WOOL, Sound.BLOCK_NOTE_BLOCK_BIT),
    BANJO(14, ChatColor.GOLD + "Banjo", Material.PINK_WOOL, Material.PINK_WOOL, Sound.BLOCK_NOTE_BLOCK_BANJO),
    PLING(15, ChatColor.LIGHT_PURPLE + "Pling", Material.BLACK_WOOL, Material.BLACK_WOOL, Sound.BLOCK_NOTE_BLOCK_PLING);

    public final int id;
    public final String displayName;
    public final Material token;
    public final Material marker;
    public final Sound sound;

    InstrumentPalette(int id, String displayName, Material token, Material marker, Sound sound) {
        this.id = id;
        this.displayName = displayName;
        this.token = token;
        this.marker = marker;
        this.sound = sound;
    }

    public static InstrumentPalette byId(int id) {
        return Arrays.stream(values()).filter(v -> v.id == id).findFirst().orElse(PIANO);
    }
}
