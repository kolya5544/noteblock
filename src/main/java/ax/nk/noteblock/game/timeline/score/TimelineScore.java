package ax.nk.noteblock.game.timeline.score;

import ax.nk.noteblock.game.timeline.BlockPos;
import ax.nk.noteblock.game.timeline.NoteEvent;

import java.util.*;

/**
 * Pure data structure for timeline notes.
 *
 * - Stores notes per layer by tickIndex.
 * - Keeps an index from BlockPos -> (layer,tick) for fast removals.
 */
public final class TimelineScore {

    public record NoteRef(int layerIndex, int tickIndex) {
    }

    private final int layerCapacity;

    private final List<Map<Integer, List<NoteEvent>>> scoreByLayer;
    private final Map<BlockPos, NoteRef> refByPos = new HashMap<>();

    public TimelineScore(int layerCapacity) {
        this.layerCapacity = layerCapacity;
        this.scoreByLayer = new ArrayList<>(layerCapacity);
        ensureInitialized();
    }

    public void clear() {
        for (Map<Integer, List<NoteEvent>> m : scoreByLayer) m.clear();
        refByPos.clear();
    }

    public List<Map<Integer, List<NoteEvent>>> scoreByLayerView() {
        return Collections.unmodifiableList(scoreByLayer);
    }

    /** Mutable view (used by playback). */
    public List<Map<Integer, List<NoteEvent>>> scoreByLayerMutable() {
        return scoreByLayer;
    }

    public Map<BlockPos, NoteRef> refByPosView() {
        return Collections.unmodifiableMap(refByPos);
    }

    public void ensureInitialized() {
        while (scoreByLayer.size() < layerCapacity) scoreByLayer.add(new HashMap<>());
    }

    public static int clampLayerIndex(int idx, int layerCapacity) {
        return Math.max(0, Math.min(layerCapacity - 1, idx));
    }

    public void upsertNote(TimelineCell cell, BlockPos pos, int instrumentId, int pitch, int layerIndex) {
        ensureInitialized();
        layerIndex = clampLayerIndex(layerIndex, layerCapacity);

        final int tickIndex = cell.timeIndex();
        final NoteRef old = refByPos.put(pos, new NoteRef(layerIndex, tickIndex));
        if (old != null) {
            final int oldLayer = clampLayerIndex(old.layerIndex(), layerCapacity);
            final List<NoteEvent> oldList = scoreByLayer.get(oldLayer).get(old.tickIndex());
            if (oldList != null) {
                oldList.removeIf(n -> n.pos().equals(pos));
                if (oldList.isEmpty()) scoreByLayer.get(oldLayer).remove(old.tickIndex());
            }
        }

        scoreByLayer.get(layerIndex)
                .computeIfAbsent(tickIndex, k -> new ArrayList<>())
                .add(new NoteEvent(pos, instrumentId, pitch));
    }

    public NoteEvent removeNoteAt(BlockPos pos) {
        ensureInitialized();

        final NoteRef ref = refByPos.remove(pos);
        if (ref == null) return null;

        final int refLayer = clampLayerIndex(ref.layerIndex(), layerCapacity);

        NoteEvent removed = null;
        final Map<Integer, List<NoteEvent>> layerScore = scoreByLayer.get(refLayer);
        final List<NoteEvent> list = layerScore.get(ref.tickIndex());
        if (list != null) {
            for (Iterator<NoteEvent> it = list.iterator(); it.hasNext(); ) {
                final NoteEvent n = it.next();
                if (n.pos().equals(pos)) {
                    removed = n;
                    it.remove();
                    break;
                }
            }
            if (list.isEmpty()) layerScore.remove(ref.tickIndex());
        }

        return removed;
    }

    public boolean isLayerEmpty(int layerIndex) {
        layerIndex = clampLayerIndex(layerIndex, layerCapacity);
        final Map<Integer, List<NoteEvent>> map = scoreByLayer.get(layerIndex);
        if (map == null || map.isEmpty()) return true;
        for (List<NoteEvent> list : map.values()) {
            if (list != null && !list.isEmpty()) return false;
        }
        return true;
    }

    /**
     * Drops notes with tickIndex >= newLength.
     *
     * @return number of removed note events
     */
    public int pruneNotesOutsideLength(int newLength, int originX) {
        int removedCount = 0;

        for (int layer = 0; layer < layerCapacity; layer++) {
            final Map<Integer, List<NoteEvent>> map = scoreByLayer.get(layer);
            if (map == null || map.isEmpty()) continue;

            final Iterator<Map.Entry<Integer, List<NoteEvent>>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<Integer, List<NoteEvent>> e = it.next();
                if (e.getKey() < newLength) continue;

                final List<NoteEvent> list = e.getValue();
                if (list != null) {
                    removedCount += list.size();
                    for (NoteEvent n : list) {
                        refByPos.remove(n.pos());
                    }
                }
                it.remove();
            }
        }

        refByPos.entrySet().removeIf(e -> (e.getKey().x() - originX) >= newLength);
        return removedCount;
    }
}
