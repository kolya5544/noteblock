package ax.nk.noteblock.game.timeline.world;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.function.Supplier;

/** Keeps time frozen with a repeating task (defensive against other plugins). */
public final class FreezeTimeService {

    private final Plugin plugin;
    private final Supplier<World> worldSupplier;

    private BukkitTask task;

    public FreezeTimeService(Plugin plugin, Supplier<World> worldSupplier) {
        this.plugin = plugin;
        this.worldSupplier = worldSupplier;
    }

    public void start(World expectedWorld) {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            final World w = worldSupplier.get();
            if (w == null) {
                stop();
                return;
            }
            if (!w.equals(expectedWorld)) return;

            WorldRules.setGameRuleIfPresent(w, "doDaylightCycle", false);
            WorldRules.setGameRuleIfPresent(w, "tickTime", false);

            // Freeze both time-of-day and day counter (moon phase) deterministically.
            w.setFullTime(6000L);
        }, 0L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
