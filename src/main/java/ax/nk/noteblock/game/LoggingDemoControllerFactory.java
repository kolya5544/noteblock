package ax.nk.noteblock.game;

import ax.nk.noteblock.session.GameSession;
import org.bukkit.plugin.Plugin;

/**
 * Minimal demo controller: just logs start/stop.
 * Replace with your own factory + controller implementation.
 */
public final class LoggingDemoControllerFactory implements GameControllerFactory {

    private final Plugin plugin;

    public LoggingDemoControllerFactory(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public GameController create(GameSession session) {
        return new LoggingDemoController(plugin);
    }
}

