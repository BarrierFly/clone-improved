package dev.cloneimproved.undo;

/**
 * Captured block entity payload for one position (see {@link BlockEntityData} for the
 * version-dependent contents).
 */
public record BlockSnapshot(net.minecraft.world.level.block.state.BlockState state,
                            dev.cloneimproved.undo.BlockEntityData be) {
}
