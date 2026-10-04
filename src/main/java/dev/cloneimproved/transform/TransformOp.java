package dev.cloneimproved.transform;

import java.util.Objects;

/**
 * One step of a {@code /clone} transform chain: {@code move}, {@code rotate (cw|ccw|reverse)}
 * or {@code mirror (x|z) <coordinate>}.
 *
 * <p>Ops are applied strictly in the order they appear in the command (design doc §4.1).
 * Grammar guarantees at most one of each kind; {@link TransformSpec} re-validates anyway.
 *
 * <p>This type is deliberately Minecraft-free so the math can be unit tested on a plain JVM.
 */
public sealed interface TransformOp permits TransformOp.Move, TransformOp.Rotate, TransformOp.Mirror {

    /** Relocates the placement so its lower-NW corner lands on the command's {@code <destination>}. */
    record Move() implements TransformOp {
        public static final Move INSTANCE = new Move();
    }

    /** Rotation around the vertical axis; the region's lower-NW anchor stays fixed while the content rotates within it (X/Z dims swap). */
    record Rotate(RotationDir dir) implements TransformOp {
        public Rotate {
            Objects.requireNonNull(dir, "dir");
        }
    }

    /**
     * Reflection through a vertical plane. {@code xAxis} is true for a plane perpendicular to the
     * X axis ({@code mirror x 1.0}), false for one perpendicular to Z ({@code mirror z 2.5}).
     * {@code coord} is the world coordinate of the plane and must satisfy {@code 2 * coord ∈ ℤ}
     * so block positions stay integral.
     */
    record Mirror(boolean xAxis, double coord) implements TransformOp {
    }

    /** {@code cw} = clockwise 90° seen from above (north→east), {@code ccw} = counter-clockwise, {@code reverse} = 180°. */
    enum RotationDir {
        CW("cw"),
        CCW("ccw"),
        REVERSE("reverse");

        private final String token;

        RotationDir(String token) {
            this.token = token;
        }

        /** Brigadier literal for this direction. */
        public String token() {
            return token;
        }
    }
}
