package dev.cloneimproved.engine;

import com.google.common.collect.Lists;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.cloneimproved.command.CloneRequest;
import dev.cloneimproved.command.FilterRef;
import dev.cloneimproved.multiver.MultiversionHelpers;
import dev.cloneimproved.transform.PlacementMapper;
import dev.cloneimproved.transform.TransformOp;
import dev.cloneimproved.transform.TransformSpec;
import dev.cloneimproved.transform.TransformState;
import dev.cloneimproved.undo.BlockEntityData;
import dev.cloneimproved.undo.CloneRecord;
import dev.cloneimproved.undo.RegionSnapshot;
import dev.cloneimproved.undo.UndoHistoryManager;
import dev.cloneimproved.i18n.Errors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The clone algorithm, behaviour-aligned with vanilla {@code CloneCommands} on every target
 * version (three-list ordering, BARRIER clearing, per-version update flags, pending-tick
 * copying), extended with the transform chain and the mask modes (design doc §4.3/§5).
 *
 * <p>All validation happens before any world write, so a failing command has no side effects.
 */
public final class CloneExecutor {
    public static final SimpleCommandExceptionType ERROR_OVERLAP =
        new SimpleCommandExceptionType(Component.translatable("commands.clone.overlap"));
    public static final Dynamic2CommandExceptionType ERROR_AREA_TOO_LARGE =
        new Dynamic2CommandExceptionType((max, count) -> Component.translatable("commands.clone.toobig", max, count));
    public static final SimpleCommandExceptionType ERROR_FAILED =
        new SimpleCommandExceptionType(Component.translatable("commands.clone.failed"));

    private CloneExecutor() {
    }

    /** One queued block write. */
    private record Write(BlockPos pos, BlockState state, BlockEntityData be, BlockState previousStateAtDestination) {
    }

    public static int execute(CommandContext<CommandSourceStack> ctx, CloneRequest request,
                              dev.cloneimproved.command.DimRef fromRef, dev.cloneimproved.command.DimRef toRef)
        throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel fromDim = fromRef.get(ctx);
        ServerLevel toDim = toRef.get(ctx);
        BlockPos begin = BlockPosArgument.getLoadedBlockPos(ctx, fromDim, "begin");
        BlockPos end = BlockPosArgument.getLoadedBlockPos(ctx, fromDim, "end");
        BlockPos destPos = BlockPosArgument.getLoadedBlockPos(ctx, toDim, "destination");

        TransformSpec spec = TransformSpec.of(resolveMirrorCoords(ctx, request.ops()));
        double invalidCoord = TransformSpec.invalidMirrorCoord(spec.ops());
        if (!Double.isNaN(invalidCoord)) {
            throw Errors.simple(source, "cloneimproved.mirror_invalid", invalidCoord);
        }

        BoundingBox from = BoundingBox.fromCorners(begin, end);
        PlacementMapper.Placement srcPlacement = new PlacementMapper.Placement(
            from.minX(), from.minY(), from.minZ(), from.getXSpan(), from.getYSpan(), from.getZSpan());
        PlacementMapper.Placement destPlacement = PlacementMapper.finalPlacement(
            spec, srcPlacement, destPos.getX(), destPos.getY(), destPos.getZ());
        BoundingBox destBox = new BoundingBox(destPlacement.minX(), destPlacement.minY(), destPlacement.minZ(),
            destPlacement.maxX(), destPlacement.maxY(), destPlacement.maxZ());
        boolean moveSource = spec.movesSource();

        if (!request.force() && fromDim == toDim && destBox.intersects(from)) {
            throw ERROR_OVERLAP.create();
        }

        long area = srcPlacement.volume();
        int limit = MultiversionHelpers.modificationLimit(source);
        if (area > limit) {
            throw ERROR_AREA_TOO_LARGE.create(limit, area);
        }

        if (!fromDim.hasChunksAt(begin, end)
            || !toDim.hasChunksAt(new BlockPos(destBox.minX(), destBox.minY(), destBox.minZ()),
            new BlockPos(destBox.maxX(), destBox.maxY(), destBox.maxZ()))) {
            throw BlockPosArgument.ERROR_NOT_LOADED.create();
        }
        if (MultiversionHelpers.isDebugWorld(toDim)) {
            throw ERROR_FAILED.create();
        }

        MaskMode mask = request.mask();
        Predicate<BlockInWorld> extraFilter = request.filter() == null ? FilterRef.ALL.resolve(ctx) : request.filter().resolve(ctx);

        List<Write> solid = new ArrayList<>();
        List<Write> withBlockEntity = new ArrayList<>();
        List<Write> other = new ArrayList<>();
        Deque<BlockPos> clearList = new ArrayDeque<>();

        for (int z = from.minZ(); z <= from.maxZ(); z++) {
            for (int y = from.minY(); y <= from.maxY(); y++) {
                for (int x = from.minX(); x <= from.maxX(); x++) {
                    BlockPos sourcePos = new BlockPos(x, y, z);
                    BlockInWorld block = new BlockInWorld(fromDim, sourcePos, false);
                    BlockState state = block.getState();
                    boolean copied = extraFilter.test(block) && (!mask.copiesOnlyNonAir() || !state.isAir());
                    if (!copied) {
                        continue;
                    }
                    int[] d = PlacementMapper.mapToDest(spec, srcPlacement, destPos.getX(), destPos.getY(), destPos.getZ(), x, y, z);
                    BlockPos destPos2 = new BlockPos(d[0], d[1], d[2]);
                    if (mask.requiresAirAtDestination() && !toDim.getBlockState(destPos2).isAir()) {
                        throw Errors.simple(source, "cloneimproved.mask_end_not_air", formatPos(destPos2));
                    }
                    var blockEntity = fromDim.getBlockEntity(sourcePos);
                    BlockState previousAtDest = toDim.getBlockState(destPos2);
                    BlockState transformed = TransformState.apply(spec, state);
                    if (blockEntity != null) {
                        withBlockEntity.add(new Write(destPos2, transformed, MultiversionHelpers.captureBe(fromDim, blockEntity), previousAtDest));
                        clearList.addLast(sourcePos);
                    } else if (MultiversionHelpers.isNonSolid(fromDim, sourcePos, state)) {
                        other.add(new Write(destPos2, transformed, null, previousAtDest));
                        clearList.addFirst(sourcePos);
                    } else {
                        solid.add(new Write(destPos2, transformed, null, previousAtDest));
                        clearList.addLast(sourcePos);
                    }
                }
            }
        }

        // Pre-write snapshot of every affected region (whole region, incl. masked air), then write.
        List<RegionSnapshot> regions = new ArrayList<>(2);
        if (moveSource) {
            regions.add(RegionSnapshot.create(fromDim, from));
        }
        RegionSnapshot destSnapshot = RegionSnapshot.create(toDim, destBox);
        regions.add(destSnapshot);

        int flags = MultiversionHelpers.writeFlags(request.strict());
        boolean sourceCleared = false;
        if (moveSource) {
            for (BlockPos pos : clearList) {
                MultiversionHelpers.clearSourcePos(fromDim, pos, request.strict());
            }
            int airFlags = MultiversionHelpers.airFlags(request.strict());
            for (BlockPos pos : clearList) {
                if (fromDim.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), airFlags)) {
                    sourceCleared = true;
                }
            }
        }

        List<Write> all = new ArrayList<>(solid.size() + withBlockEntity.size() + other.size());
        all.addAll(solid);
        all.addAll(withBlockEntity);
        all.addAll(other);
        List<Write> reverse = Lists.reverse(all);

        int barrierChanges = 0;
        for (Write write : reverse) {
            if (MultiversionHelpers.placeBarrier(toDim, write.pos(), request.strict())) {
                barrierChanges++;
            }
        }

        int count = 0;
        for (Write write : all) {
            if (toDim.setBlock(write.pos(), write.state(), flags)) {
                count++;
            }
        }

        for (Write write : withBlockEntity) {
            var newBlockEntity = toDim.getBlockEntity(write.pos());
            if (write.be() != null && newBlockEntity != null) {
                MultiversionHelpers.loadBe(toDim, newBlockEntity, write.be());
            }
            toDim.setBlock(write.pos(), write.state(), flags);
        }

        if (!request.strict()) {
            for (Write write : reverse) {
                MultiversionHelpers.updateNeighbours(toDim, write.pos(), write.state(), write.previousStateAtDestination());
            }
        }

        // Pending ticks are only copyable for plain translations (vanilla only supports offsets).
        if (spec.isPureTranslation()) {
            BlockPos offset = new BlockPos(
                destBox.minX() - from.minX(), destBox.minY() - from.minY(), destBox.minZ() - from.minZ());
            toDim.getBlockTicks().copyAreaFrom(fromDim.getBlockTicks(), from, offset);
        }

        if (count == 0) {
            // Vanilla reports failure after the writes; if the run still changed the world
            // (source cleared for move, or the barrier pass replaced blocks), keep an undo
            // record so the change stays reversible despite the error.
            if (barrierChanges > 0 || sourceCleared) {
                destSnapshot.captureAfter(toDim);
                if (moveSource) {
                    regions.get(0).captureAfter(fromDim);
                }
                recordUndo(ctx, request, 0, regions);
            }
            throw ERROR_FAILED.create();
        }

        destSnapshot.captureAfter(toDim);
        if (moveSource) {
            regions.get(0).captureAfter(fromDim);
        }
        recordUndo(ctx, request, count, regions);

        MultiversionHelpers.sendSuccess(source, Component.translatable("commands.clone.success", count), true);
        return count;
    }

    /** Persists the before/after snapshots as the executor's newest undo record. */
    private static void recordUndo(CommandContext<CommandSourceStack> ctx, CloneRequest request, int affected,
                                   List<RegionSnapshot> regions) {
        CommandSourceStack source = ctx.getSource();
        UUID executorId;
        String executorName;
        if (source.getEntity() instanceof ServerPlayer player) {
            executorId = player.getUUID();
            executorName = player.getName().getString();
        } else {
            executorId = new UUID(0L, 0L);
            executorName = "Server";
        }
        UndoHistoryManager.recordClone(executorId, new CloneRecord(
            executorId, executorName, Instant.now(), ctx.getInput(), affected, request.strict(), List.copyOf(regions)));
    }

    /**
     * The command tree only knows at build time that a mirror happens, not its coordinate; the
     * builder emits a NaN marker that is replaced here with the parsed {@code coordinate} argument.
     */
    private static List<TransformOp> resolveMirrorCoords(CommandContext<CommandSourceStack> ctx, List<TransformOp> ops) {
        boolean hasMarker = false;
        for (TransformOp op : ops) {
            if (op instanceof TransformOp.Mirror mirror && Double.isNaN(mirror.coord())) {
                hasMarker = true;
                break;
            }
        }
        if (!hasMarker) {
            return ops;
        }
        double coordinate = ctx.getArgument("coordinate", Double.class);
        List<TransformOp> resolved = new ArrayList<>(ops.size());
        for (TransformOp op : ops) {
            if (op instanceof TransformOp.Mirror mirror && Double.isNaN(mirror.coord())) {
                op = new TransformOp.Mirror(mirror.xAxis(), coordinate);
            }
            resolved.add(op);
        }
        return List.copyOf(resolved);
    }

    private static String formatPos(BlockPos pos) {
        return "[" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "]";
    }
}
