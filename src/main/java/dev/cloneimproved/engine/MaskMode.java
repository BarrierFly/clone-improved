package dev.cloneimproved.engine;

/**
 * The three mask literals that replace vanilla {@code masked}, plus {@code NONE} for
 * {@code replace}-style copying (design doc §5).
 *
 * <ul>
 *   <li>{@link #BEGIN}: a source block is only copied when it is not air (≡ vanilla {@code masked}).</li>
 *   <li>{@link #END}: everything is copied, but every destination position must be air or the
 *       whole command fails with zero writes.</li>
 *   <li>{@link #BOTH}: like BEGIN, plus destination positions that will receive a non-air block
 *       must be air.</li>
 * </ul>
 *
 * <p>Masks only affect what gets copied into the destination region; {@code move}'s clearing of
 * the source region is untouched (vanilla parity).
 */
public enum MaskMode {
    NONE,
    BEGIN,
    END,
    BOTH;

    public static final MaskMode[] MASKS = {BEGIN, END, BOTH};

    /** Brigadier literal; {@link #NONE} has no literal (default copy). */
    public String token() {
        return switch (this) {
            case BEGIN -> "mask_begin";
            case END -> "mask_end";
            case BOTH -> "mask_both";
            case NONE -> "replace";
        };
    }

    /** BEGIN/BOTH skip air blocks in the source region. */
    public boolean copiesOnlyNonAir() {
        return this == BEGIN || this == BOTH;
    }

    /** Whether the destination position receiving {@code sourceNonAir} must currently be air. */
    public boolean requiresAirAtDestination(boolean sourceNonAir) {
        return this == END || (this == BOTH && sourceNonAir);
    }
}
