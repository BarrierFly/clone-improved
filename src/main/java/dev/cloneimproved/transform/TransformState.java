package dev.cloneimproved.transform;

import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Applies the chain's state transforms to {@link BlockState}s, in command order (design doc §4.2).
 *
 * <p>Direction mapping (verified against vanilla sources): a mirror plane perpendicular to the
 * X axis flips X, which is vanilla {@link Mirror#FRONT_BACK} ({@code OctahedralGroup.INVERT_X});
 * a plane perpendicular to Z flips Z, which is {@link Mirror#LEFT_RIGHT} ({@code INVERT_Z}).
 *
 * <p>Block entity NBT needs no rotation on 1.19.4+ — orientation lives in the BlockState.
 */
public final class TransformState {
    private TransformState() {
    }

    public static BlockState apply(TransformSpec spec, BlockState state) {
        BlockState cur = state;
        for (TransformOp op : spec.ops()) {
            if (op instanceof TransformOp.Rotate rotate) {
                cur = switch (rotate.dir()) {
                    case CW -> cur.rotate(Rotation.CLOCKWISE_90);
                    case CCW -> cur.rotate(Rotation.COUNTERCLOCKWISE_90);
                    case REVERSE -> cur.rotate(Rotation.CLOCKWISE_180);
                };
            } else if (op instanceof TransformOp.Mirror mirror) {
                cur = cur.mirror(mirror.xAxis() ? Mirror.FRONT_BACK : Mirror.LEFT_RIGHT);
            }
        }
        return cur;
    }
}
