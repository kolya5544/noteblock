package ax.nk.noteblock.world;

import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.ChunkGenerator;

import java.util.Random;

/**
 * Generates completely empty chunks (void).
 *
 * Spawn platform is placed separately by {@code ax.nk.noteblock.session.SessionManager}.
 */
public final class VoidChunkGenerator extends ChunkGenerator {

    @Override
    @SuppressWarnings("deprecation")
    public ChunkData generateChunkData(World world, Random random, int x, int z, BiomeGrid biome) {
        // Force a uniform biome so block tinting is consistent.
        // THE_VOID is a good neutral choice for void worlds.
        for (int bx = 0; bx < 16; bx++) {
            for (int bz = 0; bz < 16; bz++) {
                biome.setBiome(bx, bz, Biome.THE_VOID);
            }
        }

        // Paper marks this helper deprecated but it is still the compatible way to create ChunkData on this API.
        return createChunkData(world);
    }
}
