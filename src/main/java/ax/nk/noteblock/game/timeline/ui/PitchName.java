package ax.nk.noteblock.game.timeline.ui;

/**
 * Maps timeline pitch rows (0..24) to note names.
 *
 * We keep it simple: C, C#, D, ... repeating.
 */
public final class PitchName {

    private static final String[] NAMES = {
            "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
    };

    private PitchName() {
    }

    public static String nameForRow(int pitchRow) {
        if (pitchRow < 0) return "?";
        final int note = pitchRow % 12;
        final int octave = pitchRow / 12; // 0..2 for 0..24
        return NAMES[note] + octave;
    }
}

