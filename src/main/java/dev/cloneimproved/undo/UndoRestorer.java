package dev.cloneimproved.undo;

import dev.cloneimproved.multiver.MultiversionHelpers;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes a snapshot back into the world (design doc §6.4).
 *
 * <p>UNDO restores the {@code before} image, REDO the {@code after} image. Update flags mirror
 * the original clone run ({@code strict}-aware), and the neighbour-update pass matches vanilla's
 * non-strict behaviour. Runs on the server thread (brigadier executes there anyway).
 */
public final class UndoRestorer {
    private UndoRestorer() {
    }

    public static void restore(MinecraftServer server, CloneRecord record, UndoKind kind) {
        for (RegionSnapshot region : record.regions()) {
            ServerLevel level = server.getLevel(region.dimension());
            if (level == null) {
                continue;
            }
            Long2ObjectOpenHashMap<BlockSnapshot> target = kind == UndoKind.UNDO ? region.before() : region.after();
            int flags = MultiversionHelpers.writeFlags(record.strict());
            List<BlockPos> changed = new ArrayList<>();
            List<BlockState> previous = new ArrayList<>();
            for (var entry : target.long2ObjectEntrySet()) {
                BlockPos pos = BlockPos.of(entry.getLongKey());
                BlockSnapshot snapshot = entry.getValue();
                BlockState old = level.getBlockState(pos);
                if (old == snapshot.state() && snapshot.be() == null && level.getBlockEntity(pos) == null) {
                    continue;
                }
                level.setBlock(pos, snapshot.state(), flags);
                if (snapshot.be() != null) {
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be != null) {
                        MultiversionHelpers.loadBe(level, be, snapshot.be());
                    }
                }
                if (!record.strict()) {
                    changed.add(pos);
                    previous.add(old);
                }
            }
            for (int i = 0; i < changed.size(); i++) {
                MultiversionHelpers.updateNeighbours(level, changed.get(i), previous.get(i));
            }
        }
    }
}
