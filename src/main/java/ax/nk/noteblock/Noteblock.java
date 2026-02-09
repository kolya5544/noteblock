package ax.nk.noteblock;

import ax.nk.noteblock.game.GameControllerFactory;
import ax.nk.noteblock.game.timeline.TimelineControllerFactory;
import ax.nk.noteblock.game.timeline.ui.ChatPrompt;
import ax.nk.noteblock.game.timeline.ui.TextPrompt;
import ax.nk.noteblock.persistence.SongRepository;
import ax.nk.noteblock.persistence.SqliteDatabase;
import ax.nk.noteblock.session.SessionManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class Noteblock extends JavaPlugin implements Listener {

    private SessionManager sessionManager;

    private SqliteDatabase sqlite;
    private SongRepository songRepository;
    private ChatPrompt chatPrompt;
    private TextPrompt textPrompt;

    @Override
    public void onEnable() {
        // SQLite init
        try {
            final File dbFile = new File(getDataFolder(), "noteblock.db");
            sqlite = new SqliteDatabase(this, dbFile);
            sqlite.open();
            songRepository = new SongRepository(sqlite);
        } catch (Exception e) {
            getLogger().severe("Failed to initialize SQLite: " + e.getMessage());
            getLogger().severe("Disabling plugin because persistence is required for the Library feature.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Shared prompts
        chatPrompt = new ChatPrompt(this);
        textPrompt = new TextPrompt(chatPrompt);

        final GameControllerFactory controllerFactory = new TimelineControllerFactory(this, songRepository, chatPrompt, textPrompt);
        this.sessionManager = new SessionManager(this, controllerFactory);

        // Cleanup leftover session worlds from a previous server run/crash.
        sessionManager.cleanupLeftoverWorldsOnBoot();

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        if (sessionManager != null) {
            sessionManager.shutdown();
        }
        if (chatPrompt != null) {
            chatPrompt.shutdown();
            chatPrompt = null;
        }
        if (textPrompt != null) {
            textPrompt.shutdown();
            textPrompt = null;
        }
        if (sqlite != null) {
            sqlite.close();
            sqlite = null;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Make sure the player starts the session with a clean inventory.
        event.getPlayer().getInventory().clear();
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
