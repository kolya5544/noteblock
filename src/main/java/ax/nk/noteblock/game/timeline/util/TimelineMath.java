package ax.nk.noteblock.game.timeline.util;

/** Utility math helpers for the timeline feature. */
public final class TimelineMath {
    private TimelineMath() {
    }

    public static int clamp(int v, int minInclusive, int maxInclusive) {
        return Math.max(minInclusive, Math.min(maxInclusive, v));
    }
}

