package ax.nk.noteblock;

import ax.nk.noteblock.game.GameControllerFactory;
import ax.nk.noteblock.game.timeline.TimelineControllerFactory;
import ax.nk.noteblock.session.SessionManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class Noteblock extends JavaPlugin implements Listener {

    private SessionManager sessionManager;

    @Override
    public void onEnable() {
        final GameControllerFactory controllerFactory = new TimelineControllerFactory(this);
        this.sessionManager = new SessionManager(this, controllerFactory);

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        if (sessionManager != null) {
            sessionManager.shutdown();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sessionManager.startSession(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessionManager.endSession(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        sessionManager.endSession(event.getPlayer().getUniqueId());
    }
}
