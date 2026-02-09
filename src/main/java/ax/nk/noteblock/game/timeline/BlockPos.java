package ax.nk.noteblock.game.timeline;

import org.bukkit.Location;
import org.bukkit.World;

public record BlockPos(int x, int y, int z) {

    public static BlockPos from(Location loc) {
        return new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public Location toLocation(World world) {
        return new Location(world, x, y, z);
    }
}
