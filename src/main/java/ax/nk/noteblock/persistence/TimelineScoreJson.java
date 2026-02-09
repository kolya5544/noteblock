package ax.nk.noteblock.persistence;

import ax.nk.noteblock.game.timeline.NoteEvent;
import ax.nk.noteblock.game.timeline.score.TimelineScore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JSON serialization for songs stored in SQLite.
 */
public final class TimelineScoreJson {

    private TimelineScoreJson() {
    }

    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .create();

    public record SongPayload(int schemaVersion,
                              int trackLength,
                              int ticksPerStep,
                              int layerCount,
                              List<List<NoteDto>> layers) {
    }

    public record NoteDto(int t, int i, int p) {
    }

    public static String toJson(TimelineScore score, int trackLength, int ticksPerStep, int layerCount) {
        final List<List<NoteDto>> layers = new ArrayList<>(layerCount);

        final List<Map<Integer, List<NoteEvent>>> srcLayers = score.scoreByLayerView();
        for (int layer = 0; layer < layerCount; layer++) {
            final List<NoteDto> eventsOut = new ArrayList<>();
            final Map<Integer, List<NoteEvent>> byTick = srcLayers.get(layer);

            for (Map.Entry<Integer, List<NoteEvent>> e : byTick.entrySet()) {
                final int tickIndex = e.getKey();
                final List<NoteEvent> events = e.getValue();
                if (events == null) continue;

                for (NoteEvent n : events) {
                    eventsOut.add(new NoteDto(tickIndex, n.instrumentId(), n.pitch()));
                }
            }

            layers.add(eventsOut);
        }

        final SongPayload payload = new SongPayload(1, trackLength, ticksPerStep, layerCount, layers);
        return GSON.toJson(payload);
    }
}
