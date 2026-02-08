package ax.nk.noteblock.game;

import ax.nk.noteblock.session.GameSession;
import org.bukkit.entity.Player;

/**
 * Owns minigame logic for a single player + their isolated world.
 * Implementations should avoid referencing global server state where possible.
 */
public interface GameController {

    /** Called after the world is created and the player was teleported into it. */
    void onStart(GameSession session, Player player);

    /** Called when the session is ending (quit/kick/shutdown). */
    void onStop(GameSession session);
}

