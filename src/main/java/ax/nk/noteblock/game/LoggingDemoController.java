package ax.nk.noteblock.game;

import ax.nk.noteblock.session.GameSession;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class LoggingDemoController implements GameController {

    private final Plugin plugin;

    LoggingDemoController(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onStart(GameSession session, Player player) {
        plugin.getLogger().info("Session started for " + player.getName() + " in world " + session.world().getName());
    }

    @Override
    public void onStop(GameSession session) {
        plugin.getLogger().info("Session stopped for player " + session.playerId() + " world " + session.world().getName());
    }
}

