package ax.nk.noteblock.game;

import ax.nk.noteblock.session.GameSession;

/** Creates a new controller instance for each session. */
@FunctionalInterface
public interface GameControllerFactory {
    GameController create(GameSession session);
}

