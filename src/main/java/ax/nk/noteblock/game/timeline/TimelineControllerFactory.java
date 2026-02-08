package ax.nk.noteblock.game.timeline;

import ax.nk.noteblock.game.GameController;
import ax.nk.noteblock.game.GameControllerFactory;
import ax.nk.noteblock.session.GameSession;
import org.bukkit.plugin.Plugin;

public final class TimelineControllerFactory implements GameControllerFactory {

    private final Plugin plugin;

    public TimelineControllerFactory(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public GameController create(GameSession session) {
        return new TimelineController(plugin);
    }
}

