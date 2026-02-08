package ax.nk.noteblock.game.timeline;

import org.bukkit.Location;
import org.bukkit.World;

record BlockPos(int x, int y, int z) {

    static BlockPos from(Location loc) {
        return new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    Location toLocation(World world) {
        return new Location(world, x, y, z);
    }
}

