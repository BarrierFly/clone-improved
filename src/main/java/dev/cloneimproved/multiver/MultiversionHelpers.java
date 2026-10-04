package dev.cloneimproved.multiver;

import dev.cloneimproved.undo.BlockEntityData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

//? if <1.21.2 {
import net.minecraft.world.Clearable;
//?} else {
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
//?}

/**
 * Every Minecraft-version divergence lives here (design doc §7). All other code is shared across
 * the four target versions; the stonecutter guards below are the only {@code //?} comments in the
 * project besides {@code CloneCommandExtension}'s {@code strict} registration.
 */
public final class MultiversionHelpers {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private MultiversionHelpers() {
    }

    /** §7.1 — the command block-modification limit gamerule. */
    public static int modificationLimit(CommandSourceStack source) {
        //? if <1.21.11 {
        return source.getLevel().getGameRules().getInt(net.minecraft.world.level.GameRules.RULE_COMMAND_MODIFICATION_BLOCK_LIMIT);
        //?} else {
        return source.getLevel().getGameRules().get(net.minecraft.world.level.gamerules.GameRules.MAX_BLOCK_MODIFICATIONS);
        //?}
    }

    /**
     * §7.2 — captures a block entity for later restore. The owning level is passed in because
     * the level accessor itself was renamed across versions.
     */
    public static BlockEntityData captureBe(ServerLevel level, BlockEntity be) {
        //? if <1.21.2 {
        return new BlockEntityData(be.saveWithoutMetadata());
        //?} else {
        ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER);
        try {
            TagValueOutput out = TagValueOutput.createWithContext(reporter.forChild(be.problemPath()), level.registryAccess());
            be.saveCustomOnly(out);
            return new BlockEntityData(out.buildResult(), be.components());
        } finally {
            reporter.close();
        }
        //?}
    }

    /** §7.2 — restores a captured block entity onto a freshly created one. Caller follows up with {@code setBlock}. */
    public static void loadBe(ServerLevel level, BlockEntity be, BlockEntityData data) {
        //? if <1.21.2 {
        be.load(data.tag());
        be.setChanged();
        //?} else {
        ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER);
        try {
            be.loadCustomOnly(TagValueInput.create(reporter.forChild(be.problemPath()), level.registryAccess(), data.tag()));
            be.setComponents(data.components());
            be.setChanged();
        } finally {
            reporter.close();
        }
        //?}
    }

    /**
     * §7.3 — the vanilla clone "neighbours updated" pass (lives on ServerLevel in 1.21.2+).
     * Vanilla notifies neighbours with the block it just placed on 1.19.4, and with the
     * previous state there from 1.21.2 on — callers pass both so every version keeps parity.
     */
    public static void updateNeighbours(ServerLevel level, BlockPos pos, BlockState placedState, BlockState previousState) {
        //? if <1.21.2 {
        level.blockUpdated(pos, placedState.getBlock());
        //?} else {
        level.updateNeighboursOnBlockSet(pos, previousState);
        //?}
    }

    /** §7.4 — update flags for writing the final block states. */
    public static int writeFlags(boolean strict) {
        //? if <1.21.2 {
        return 2;
        //?} else {
        return 2 | (strict ? 816 : 0);
        //?}
    }

    /** §7.4 — update flags for the BARRIER pass that clears positions before the real write. */
    public static int barrierFlags(boolean strict) {
        //? if <1.21.2 {
        return 2;
        //?} else {
        return (2 | (strict ? 816 : 0)) | 816;
        //?}
    }

    /** §7.4 — update flags for the AIR pass that empties a moved-out source region. */
    public static int airFlags(boolean strict) {
        //? if <1.21.2 {
        return 3;
        //?} else {
        return strict ? (2 | 816) : 3;
        //?}
    }

    /** Three-list classification: non-full blocks are written last (and cleared first on move), like vanilla. */
    public static boolean isNonSolid(ServerLevel level, BlockPos pos, BlockState state) {
        //? if <1.21.2 {
        return !state.isSolidRender(level, pos) && !state.isCollisionShapeFullBlock(level, pos);
        //?} else {
        return !state.isSolidRender() && !state.isCollisionShapeFullBlock(level, pos);
        //?}
    }

    /** Clears one source-region position during a move: 1.19.4 empties containers explicitly, newer versions suppress drops via flags. */
    public static void clearSourcePos(ServerLevel level, BlockPos pos, boolean strict) {
        //? if <1.21.2 {
        Clearable.tryClear(level.getBlockEntity(pos));
        level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), 2);
        //?} else {
        level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), barrierFlags(strict));
        //?}
    }

    /**
     * Prepares one destination position with a BARRIER before the real blocks are written
     * (vanilla parity). Returns whether the BARRIER actually changed the block.
     */
    public static boolean placeBarrier(ServerLevel level, BlockPos pos, boolean strict) {
        //? if <1.21.2 {
        Clearable.tryClear(level.getBlockEntity(pos));
        return level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), 2);
        //?} else {
        return level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), barrierFlags(strict));
        //?}
    }

    /** §7 — 1.21.2+ rejects debug worlds in /clone; 1.19.4 does not check. */
    public static boolean isDebugWorld(ServerLevel level) {
        //? if <1.21.2 {
        return false;
        //?} else {
        return level.isDebug();
        //?}
    }

    /** §7.5 — sendSuccess takes a plain Component on 1.19.4 and a Supplier from 1.20.2+ on. */
    public static void sendSuccess(CommandSourceStack source, Component message, boolean broadcastToAdmins) {
        //? if <1.21.2 {
        source.sendSuccess(message, broadcastToAdmins);
        //?} else {
        source.sendSuccess(() -> message, broadcastToAdmins);
        //?}
    }

    /**
     * Client-reported language for server-side message formatting (design doc §9.1). The synced
     * client language is only read on 1.21.10+ (the guard below); 1.19.4–1.21.11 players get
     * English, matching the {@code I18n} fallback.
     */
    public static String playerLanguage(ServerPlayer player) {
        //? if >=1.21.10 {
        return player.clientInformation().language();
        //?} else {
        return "en_us";
        //?}
    }
}
