package ax.nk.noteblock.game.timeline.playback;

import ax.nk.noteblock.game.timeline.InstrumentPalette;
import ax.nk.noteblock.game.timeline.NoteEvent;
import ax.nk.noteblock.game.timeline.util.TimelineMath;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Owns playback scheduling and playhead progression for the timeline.
 *
 * Controller responsibilities:
 * - provide score access (scoreByLayer + layerCount)
 * - provide playhead and range overlay rendering callbacks
 */
public final class PlaybackEngine {

    private final Plugin plugin;

    private BukkitTask task;
    private int playhead;

    private int ticksPerStep;
    private boolean loopEnabled;

    public PlaybackEngine(Plugin plugin, int ticksPerStep, boolean loopEnabled) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.ticksPerStep = ticksPerStep;
        this.loopEnabled = loopEnabled;
    }

    public boolean isPlaying() {
        return task != null;
    }

    public int playhead() {
        return playhead;
    }

    public void setPlayhead(int playhead) {
        this.playhead = playhead;
    }

    public void setLoopEnabled(boolean enabled) {
        this.loopEnabled = enabled;
    }

    public boolean loopEnabled() {
        return loopEnabled;
    }

    public int ticksPerStep() {
        return ticksPerStep;
    }

    public void setTicksPerStep(int ticksPerStep) {
        this.ticksPerStep = ticksPerStep;
    }

    public void toggle(Player player,
                       World world,
                       int trackLength,
                       Integer rangeBegin,
                       Integer rangeEnd,
                       int layerCount,
                       List<Map<Integer, List<NoteEvent>>> scoreByLayer,
                       IntConsumer drawPlayhead,
                       Runnable clearPlayhead,
                       float pitchRowWidth,
                       java.util.function.IntFunction<Float> pitchFromRow,
                       Runnable onFinishedOrStopped) {
        if (task != null) {
            stop(clearPlayhead);
            if (onFinishedOrStopped != null) onFinishedOrStopped.run();
            return;
        }
        start(player, world, trackLength, rangeBegin, rangeEnd, layerCount, scoreByLayer, drawPlayhead, clearPlayhead, pitchRowWidth, pitchFromRow, onFinishedOrStopped);
    }

    public void start(Player player,
                      World world,
                      int trackLength,
                      Integer rangeBegin,
                      Integer rangeEnd,
                      int layerCount,
                      List<Map<Integer, List<NoteEvent>>> scoreByLayer,
                      IntConsumer drawPlayhead,
                      Runnable clearPlayhead,
                      float pitchRowWidth,
                      java.util.function.IntFunction<Float> pitchFromRow,
                      Runnable onFinished) {
        stop(clearPlayhead);

        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (player == null || !player.isOnline()) {
                stop(clearPlayhead);
                return;
            }
            if (world == null) {
                stop(clearPlayhead);
                return;
            }

            final int startIndex = playbackStartIndex(trackLength, rangeBegin, rangeEnd);
            final int endExclusive = playbackEndExclusive(trackLength, rangeBegin, rangeEnd);
            if (startIndex >= endExclusive) {
                stop(clearPlayhead);
                player.sendMessage(ChatColor.RED + "Invalid range.");
                return;
            }

            if (playhead < startIndex || playhead >= endExclusive) {
                playhead = startIndex;
            }

            if (drawPlayhead != null) drawPlayhead.accept(playhead);
            playStep(player, world, playhead, layerCount, scoreByLayer, pitchFromRow);
            playhead++;

            if (playhead >= endExclusive) {
                if (loopEnabled) {
                    playhead = startIndex;
                } else {
                    stop(clearPlayhead);
                    player.sendMessage(ChatColor.GRAY + "Playback finished.");
                    if (onFinished != null) onFinished.run();
                }
            }
        }, 0L, ticksPerStep);
    }

    public void stop(Runnable clearPlayhead) {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (clearPlayhead != null) clearPlayhead.run();
    }

    public int playbackStartIndex(int trackLength, Integer rangeBegin, Integer rangeEnd) {
        final int min = 0;
        final int max = trackLength; // exclusive
        if (rangeBegin == null && rangeEnd == null) return min;

        int a = rangeBegin == null ? min : TimelineMath.clamp(rangeBegin, min, max - 1);
        int b = rangeEnd == null ? (max - 1) : TimelineMath.clamp(rangeEnd, min, max - 1);
        return Math.min(a, b);
    }

    public int playbackEndExclusive(int trackLength, Integer rangeBegin, Integer rangeEnd) {
        final int min = 0;
        final int max = trackLength; // exclusive
        if (rangeBegin == null && rangeEnd == null) return max;

        int a = rangeBegin == null ? min : TimelineMath.clamp(rangeBegin, min, max - 1);
        int b = rangeEnd == null ? (max - 1) : TimelineMath.clamp(rangeEnd, min, max - 1);
        return Math.max(a, b) + 1;
    }

    private static void playStep(Player player,
                                 World world,
                                 int tickIndex,
                                 int layerCount,
                                 List<Map<Integer, List<NoteEvent>>> scoreByLayer,
                                 java.util.function.IntFunction<Float> pitchFromRow) {
        if (player == null || world == null || scoreByLayer == null) return;

        final int layersToPlay = Math.min(layerCount, scoreByLayer.size());
        for (int layer = 0; layer < layersToPlay; layer++) {
            final Map<Integer, List<NoteEvent>> layerMap = scoreByLayer.get(layer);
            if (layerMap == null) continue;

            final List<NoteEvent> events = layerMap.get(tickIndex);
            if (events == null || events.isEmpty()) continue;

            for (NoteEvent e : events) {
                final InstrumentPalette palette = InstrumentPalette.byId(e.instrumentId());
                final float pitch = pitchFromRow.apply(e.pitch());
                player.playSound(player.getLocation(), palette.sound, SoundCategory.RECORDS, 1.0f, pitch);

                final Location at = e.pos().toLocation(world).add(0.5, 0.8, 0.5);
                world.spawnParticle(Particle.NOTE, at, 1, 0, 0, 0, 1);
            }
        }
    }
}

