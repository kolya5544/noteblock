package ax.nk.noteblock.world;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

public final class WorldDeletion {

    private WorldDeletion() {
    }

    public static void deleteDirectoryWithRetries(Plugin plugin, Path path, int retries) {
        if (path == null) return;

        // Deleting right after unload can fail on Windows due to file locks.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                deleteDirectory(path);
            } catch (IOException e) {
                if (retries > 0) {
                    plugin.getLogger().warning("Failed to delete " + path + " (" + e.getMessage() + "), retrying...");
                    deleteDirectoryWithRetries(plugin, path, retries - 1);
                } else {
                    plugin.getLogger().severe("Failed to delete " + path + " after retries: " + e.getMessage());
                }
            }
        }, 20L);
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) return;

        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

