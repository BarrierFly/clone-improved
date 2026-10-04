package dev.cloneimproved.engine;

import java.util.List;

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

    /** The three mask literals, in registration order; immutable so the command tree cannot be reshaped externally. */
    public static final List<MaskMode> MASKS = List.of(BEGIN, END, BOTH);

    /** Brigadier literal; only the three mask modes reach the command tree ({@link #NONE} has none). */
    public String token() {
        return switch (this) {
            case BEGIN -> "mask_begin";
            case END -> "mask_end";
            case BOTH -> "mask_both";
            case NONE -> throw new IllegalStateException("NONE has no command literal");
        };
    }

    /** BEGIN/BOTH skip air blocks in the source region. */
    public boolean copiesOnlyNonAir() {
        return this == BEGIN || this == BOTH;
    }

    /** Whether the destination position must currently be air (END/BOTH reject the command otherwise). */
    public boolean requiresAirAtDestination() {
        return this == END || this == BOTH;
    }
}
