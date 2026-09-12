package dev.cloneimproved.transform;

import java.util.List;

/**
 * An ordered, immutable transform chain. The command grammar only produces valid chains, but the
 * spec still validates its invariants (design doc §3.1: {@code move} at most once required exactly
 * once for transforms, {@code rotate}/{@code mirror} at most once each).
 */
public final class TransformSpec {
    private static final TransformSpec EMPTY = new TransformSpec(List.of());

    private final List<TransformOp> ops;

    private TransformSpec(List<TransformOp> ops) {
        this.ops = ops;
    }

    /** @throws IllegalArgumentException if an op appears more than once (defence in depth; the grammar already prevents this). */
    public static TransformSpec of(List<TransformOp> ops) {
        int moves = 0;
        int rotates = 0;
        int mirrors = 0;
        for (TransformOp op : ops) {
            if (op instanceof TransformOp.Move) {
                moves++;
            } else if (op instanceof TransformOp.Rotate) {
                rotates++;
            } else if (op instanceof TransformOp.Mirror) {
                mirrors++;
            }
        }
        if (moves > 1 || rotates > 1 || mirrors > 1) {
            throw new IllegalArgumentException("Invalid transform chain: " + ops);
        }
        return new TransformSpec(List.copyOf(ops));
    }

    public static TransformSpec empty() {
        return EMPTY;
    }

    public List<TransformOp> ops() {
        return ops;
    }

    /** True when the source region must be cleared afterwards (a {@code move} is part of the chain). */
    public boolean movesSource() {
        for (TransformOp op : ops) {
            if (op instanceof TransformOp.Move) {
                return true;
            }
        }
        return false;
    }

    /** True when the placement mapping is a plain translation, so pending block ticks can be copied like vanilla does. */
    public boolean isPureTranslation() {
        for (TransformOp op : ops) {
            if (!(op instanceof TransformOp.Move)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Pure validation of the mirror-plane rule {@code 2c ∈ ℤ} (coordinates snap to blocks only on
     * 0.5 steps). Returns the first offending coordinate, or {@link Double#NaN} when all are valid.
     */
    public static double invalidMirrorCoord(List<TransformOp> ops) {
        for (TransformOp op : ops) {
            if (op instanceof TransformOp.Mirror mirror) {
                double doubled = mirror.coord() * 2;
                if (Double.isNaN(doubled) || Math.abs(doubled - Math.rint(doubled)) > 1.0E-9) {
                    return mirror.coord();
                }
            }
        }
        return Double.NaN;
    }
}
