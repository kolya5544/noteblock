package ax.nk.noteblock.game.timeline.ui;

import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Text prompt abstraction.
 *
 * Song naming is now done via chat input (reliable across all clients).
 */
public final class TextPrompt {

    private final ChatPrompt chatPrompt;

    public TextPrompt(ChatPrompt chatPrompt) {
        this.chatPrompt = Objects.requireNonNull(chatPrompt);
    }

    public void shutdown() {
        // owned elsewhere
    }

    public void promptSongName(Player player, Consumer<String> onName) {
        chatPrompt.open(player, "Song name:", onName, null);
    }
}
