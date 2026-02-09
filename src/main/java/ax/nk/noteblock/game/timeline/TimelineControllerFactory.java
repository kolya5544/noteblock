package ax.nk.noteblock.game.timeline;

import ax.nk.noteblock.game.GameController;
import ax.nk.noteblock.game.GameControllerFactory;
import ax.nk.noteblock.game.timeline.ui.ChatPrompt;
import ax.nk.noteblock.game.timeline.ui.TextPrompt;
import ax.nk.noteblock.persistence.SongRepository;
import ax.nk.noteblock.session.GameSession;
import org.bukkit.plugin.Plugin;

public final class TimelineControllerFactory implements GameControllerFactory {

    private final Plugin plugin;
    private final SongRepository songRepository;
    private final ChatPrompt chatPrompt;
    private final TextPrompt textPrompt;

    public TimelineControllerFactory(Plugin plugin, SongRepository songRepository, ChatPrompt chatPrompt, TextPrompt textPrompt) {
        this.plugin = plugin;
        this.songRepository = songRepository;
        this.chatPrompt = chatPrompt;
        this.textPrompt = textPrompt;
    }

    @Override
    public GameController create(GameSession session) {
        return new TimelineController(plugin, songRepository, chatPrompt, textPrompt);
    }
}
