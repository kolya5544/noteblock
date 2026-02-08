package ax.nk.noteblock.world;

import org.bukkit.World;
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
        // Paper marks this helper deprecated but it is still the compatible way to create ChunkData on this API.
        return createChunkData(world);
    }
}
