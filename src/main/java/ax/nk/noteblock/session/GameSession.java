package ax.nk.noteblock.session;

import ax.nk.noteblock.game.GameController;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

public record GameSession(
        UUID playerId,
        World world,
        Location spawn,
        GameController controller
) {}

