package ax.nk.noteblock.game.timeline.world;

import org.bukkit.Difficulty;
import org.bukkit.World;

/** Applies world rules suitable for timeline editing. */
public final class WorldRules {
    private WorldRules() {
    }

    public static void applyWorldRules(World world) {
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);
        world.setClearWeatherDuration(Integer.MAX_VALUE);

        // Freeze time using string gamerule to avoid deprecated enum constants on Paper 1.21.11+
        setGameRuleIfPresent(world, "doDaylightCycle", false);
        // Paper rule that hard-stops time ticking
        setGameRuleIfPresent(world, "tickTime", false);

        setGameRuleIfPresent(world, "doWeatherCycle", false);
        setGameRuleIfPresent(world, "doMobSpawning", false);
        setGameRuleIfPresent(world, "keepInventory", true);
        setGameRuleIfPresent(world, "fallDamage", false);
        setGameRuleIfPresent(world, "doImmediateRespawn", true);
        setGameRuleIfPresent(world, "naturalRegeneration", false);
    }

    public static void setGameRuleIfPresent(World world, String ruleName, boolean value) {
        try {
            world.getClass().getMethod("setGameRule", String.class, String.class)
                    .invoke(world, ruleName, Boolean.toString(value));
        } catch (Throwable ignored) {
        }
    }
}

