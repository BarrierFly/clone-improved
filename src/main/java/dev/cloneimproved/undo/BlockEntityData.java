package dev.cloneimproved.undo;

import net.minecraft.nbt.CompoundTag;

/**
 * Version-agnostic capture of a block entity's data (design doc §6.2/§7.2).
 *
 * <p>On 1.19.4 this wraps the {@code saveWithoutMetadata()} tag; on 1.21.2+ vanilla splits the
 * data into a tag plus a {@code DataComponentMap}, so both are carried. Instances are created
 * and consumed only through {@code MultiversionHelpers}, keeping the rest of the code
 * version-agnostic.
 */
public final class BlockEntityData {
    private final CompoundTag tag;

    //? if >=1.21.2 {
    private final net.minecraft.core.component.DataComponentMap components;

    public BlockEntityData(CompoundTag tag, net.minecraft.core.component.DataComponentMap components) {
        this.tag = tag;
        this.components = components;
    }

    public net.minecraft.core.component.DataComponentMap components() {
        return components;
    }
    //?} else {
    public BlockEntityData(CompoundTag tag) {
        this.tag = tag;
    }
    //?}

    public CompoundTag tag() {
        return tag;
    }
}
