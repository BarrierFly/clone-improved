package dev.cloneimproved.undo;

import dev.cloneimproved.multiver.MultiversionHelpers;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A full before/after snapshot of one affected region (design doc §6.2).
 *
 * <p>Whole regions are recorded on purpose — including masked-out air and every position of the
 * destination region, plus the whole source region for {@code move} — so an undo restores
 * region integrity instead of patching individual blocks.
 */
public final class RegionSnapshot {
    private final ResourceKey<Level> dimension;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final Long2ObjectOpenHashMap<BlockSnapshot> before = new Long2ObjectOpenHashMap<>();
    private Long2ObjectOpenHashMap<BlockSnapshot> after;

    private RegionSnapshot(ResourceKey<Level> dimension, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
        this.dimension = dimension;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
    }

    /** Creates the snapshot and captures the region's current state as the "before" image. */
    public static RegionSnapshot create(ServerLevel level, BoundingBox box) {
        RegionSnapshot snapshot = new RegionSnapshot(
            level.dimension(), box.minX(), box.minY(), box.minZ(), box.getXSpan(), box.getYSpan(), box.getZSpan());
        captureInto(level, snapshot.minX, snapshot.minY, snapshot.minZ, snapshot.sizeX, snapshot.sizeY, snapshot.sizeZ, snapshot.before);
        return snapshot;
    }

    /** Captures the region's state as the "after" image; call once, after all writes finished. */
    public void captureAfter(ServerLevel level) {
        if (!level.dimension().equals(dimension)) {
            throw new IllegalArgumentException(
                "Level " + level.dimension() + " does not match snapshot dimension " + dimension);
        }
        if (after != null) {
            throw new IllegalStateException("after image already captured for " + describe());
        }
        Long2ObjectOpenHashMap<BlockSnapshot> map = new Long2ObjectOpenHashMap<>();
        captureInto(level, minX, minY, minZ, sizeX, sizeY, sizeZ, map);
        this.after = map;
    }

    private static void captureInto(ServerLevel level, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
                                    Long2ObjectOpenHashMap<BlockSnapshot> map) {
        int maxX = minX + sizeX - 1;
        int maxY = minY + sizeY - 1;
        int maxZ = minZ + sizeZ - 1;
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockEntityData be = null;
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity != null) {
                        be = MultiversionHelpers.captureBe(level, blockEntity);
                    }
                    map.put(pos.asLong(), new BlockSnapshot(level.getBlockState(pos), be));
                }
            }
        }
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public Long2ObjectOpenHashMap<BlockSnapshot> before() {
        return before;
    }

    public Long2ObjectOpenHashMap<BlockSnapshot> after() {
        return after;
    }

    /** Number of recorded positions across both images; used for the memory budget. */
    public int recordedBlocks() {
        return before.size() + (after == null ? 0 : after.size());
    }

    /** Roughly counts block entities in both images; used for the memory budget. */
    public int recordedBlockEntities() {
        int n = 0;
        for (BlockSnapshot snapshot : before.values()) {
            if (snapshot.be() != null) {
                n++;
            }
        }
        if (after != null) {
            for (BlockSnapshot snapshot : after.values()) {
                if (snapshot.be() != null) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Human-readable region bounds for undo/redo proposals, e.g. {@code minecraft:overworld [0 64 0 -> 10 70 10]}. */
    public String describe() {
        String dim;
        //? if <1.21.11 {
        dim = dimension.location().toString();
        //?} else {
        dim = dimension.identifier().toString();
        //?}
        return dim + " [" + minX + " " + minY + " " + minZ
            + " -> " + (minX + sizeX - 1) + " " + (minY + sizeY - 1) + " " + (minZ + sizeZ - 1) + "]";
    }

    /** True when every chunk this region touches is loaded in {@code level} (chunk lookup is Y-independent). */
    public boolean isFullyLoaded(ServerLevel level) {
        return level.hasChunksAt(
            new BlockPos(minX, minY, minZ),
            new BlockPos(minX + sizeX - 1, minY + sizeY - 1, minZ + sizeZ - 1));
    }
}
