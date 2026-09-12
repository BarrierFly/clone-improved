package dev.cloneimproved.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.cloneimproved.i18n.Errors;
import dev.cloneimproved.multiver.MultiversionHelpers;
import dev.cloneimproved.undo.CloneRecord;
import dev.cloneimproved.undo.RegionSnapshot;
import dev.cloneimproved.undo.UndoHistoryManager;
import dev.cloneimproved.undo.UndoKind;
import dev.cloneimproved.undo.UndoMessages;
import dev.cloneimproved.undo.UndoRestorer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

//? if >=1.21.10 {
import net.minecraft.server.players.NameAndId;
//?} else {
import com.mojang.authlib.GameProfile;
//?}

import java.util.Collection;
import java.util.UUID;

/**
 * {@code /clone undo|redo [<player>] [confirm|cancel]} — the proposal-confirm state machine
 * (design doc §6.3). Permission level 2 is inherited from the vanilla {@code clone} root node;
 * confirming/cancelling is additionally restricted to the proposer and the record owner.
 */
public final class UndoSubCommands {
    private UndoSubCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> undoTree() {
        return tree("undo", UndoKind.UNDO);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> redoTree() {
        return tree("redo", UndoKind.REDO);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tree(String name, UndoKind kind) {
        return Commands.literal(name)
            .executes(ctx -> propose(ctx, kind, selfIdentity(ctx.getSource())))
            .then(Commands.literal("confirm").executes(ctx -> confirm(ctx, kind)))
            .then(Commands.literal("cancel").executes(ctx -> cancel(ctx, kind)))
            .then(
                Commands.argument("target", GameProfileArgument.gameProfile())
                    .executes(ctx -> propose(ctx, kind, resolveTarget(ctx)))
                    .then(Commands.literal("confirm").executes(ctx -> confirm(ctx, kind)))
                    .then(Commands.literal("cancel").executes(ctx -> cancel(ctx, kind)))
            );
    }

    private record Identity(UUID uuid, String name) {
    }

    private static Identity selfIdentity(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return new Identity(player.getUUID(), player.getName().getString());
        }
        return new Identity(new UUID(0L, 0L), "Server");
    }

    /** Resolves the single {@code <target>} argument; 1.21.10+ wraps profiles in {@code NameAndId}. */
    private static Identity resolveTarget(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        //? if >=1.21.10 {
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(ctx, "target");
        if (profiles.size() != 1) {
            throw Errors.simple(ctx.getSource(), "cloneimproved.undo.one_player");
        }
        NameAndId target = profiles.iterator().next();
        return new Identity(target.id(), target.name());
        //?} else {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "target");
        if (profiles.size() != 1) {
            throw Errors.simple(ctx.getSource(), "cloneimproved.undo.one_player");
        }
        GameProfile target = profiles.iterator().next();
        return new Identity(target.getId(), target.getName());
        //?}
    }

    private static int propose(CommandContext<CommandSourceStack> ctx, UndoKind kind, Identity owner) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        if (UndoHistoryManager.pending() != null) {
            throw Errors.simple(source, "cloneimproved.undo.pending_exists");
        }
        CloneRecord record = UndoHistoryManager.peek(owner.uuid(), kind);
        if (record == null) {
            throw Errors.simple(source, "cloneimproved." + (kind == UndoKind.UNDO ? "undo" : "redo") + ".empty", owner.name());
        }
        int modifiedBlocks = countDiverged(source.getServer(), record, kind);
        Identity proposer = selfIdentity(source);
        UndoHistoryManager.PendingProposal proposal = new UndoHistoryManager.PendingProposal(
            proposer.uuid(), proposer.name(), owner.uuid(), owner.name(), record, kind);
        UndoHistoryManager.setPending(proposal);
        MultiversionHelpers.sendSuccess(source, UndoMessages.proposal(source, proposal, modifiedBlocks), false);
        ServerPlayer ownerPlayer = source.getServer().getPlayerList().getPlayer(owner.uuid());
        if (ownerPlayer != null && !ownerPlayer.getUUID().equals(proposer.uuid())) {
            ownerPlayer.sendSystemMessage(UndoMessages.proposalForOwner(ownerPlayer, proposal, modifiedBlocks));
        }
        return 1;
    }

    private static int confirm(CommandContext<CommandSourceStack> ctx, UndoKind kind) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        UndoHistoryManager.PendingProposal proposal = UndoHistoryManager.pending();
        if (proposal == null || proposal.kind() != kind) {
            throw Errors.simple(source, "cloneimproved.undo.no_proposal");
        }
        UUID actor = selfIdentity(source).uuid();
        if (!actor.equals(proposal.proposer()) && !actor.equals(proposal.owner())) {
            throw Errors.simple(source, "cloneimproved.undo.not_allowed");
        }
        if (UndoHistoryManager.peek(proposal.owner(), kind) != proposal.record()) {
            throw Errors.simple(source, "cloneimproved.undo.stale");
        }
        UndoRestorer.restore(source.getServer(), proposal.record(), kind);
        UndoHistoryManager.transfer(proposal.owner(), kind);
        UndoHistoryManager.setPending(null);
        MultiversionHelpers.sendSuccess(source, UndoMessages.confirmed(source, proposal), false);
        ServerPlayer other = otherParty(source, proposal, actor);
        if (other != null) {
            other.sendSystemMessage(UndoMessages.confirmedFor(other, proposal));
        }
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx, UndoKind kind) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        UndoHistoryManager.PendingProposal proposal = UndoHistoryManager.pending();
        if (proposal == null || proposal.kind() != kind) {
            throw Errors.simple(source, "cloneimproved.undo.no_proposal");
        }
        UUID actor = selfIdentity(source).uuid();
        if (!actor.equals(proposal.proposer()) && !actor.equals(proposal.owner())) {
            throw Errors.simple(source, "cloneimproved.undo.not_allowed");
        }
        UndoHistoryManager.setPending(null);
        MultiversionHelpers.sendSuccess(source, UndoMessages.cancelled(source, kind), false);
        ServerPlayer other = otherParty(source, proposal, actor);
        if (other != null) {
            other.sendSystemMessage(UndoMessages.cancelledFor(other, kind));
        }
        return 1;
    }

    private static ServerPlayer otherParty(CommandSourceStack source, UndoHistoryManager.PendingProposal proposal, UUID actor) {
        UUID otherUuid = actor.equals(proposal.proposer()) ? proposal.owner() : proposal.proposer();
        return source.getServer().getPlayerList().getPlayer(otherUuid);
    }

    /** Counts positions where the world no longer matches the snapshot an undo/redo would write. */
    private static int countDiverged(net.minecraft.server.MinecraftServer server, CloneRecord record, UndoKind kind) {
        int mismatches = 0;
        for (RegionSnapshot region : record.regions()) {
            net.minecraft.server.level.ServerLevel level = server.getLevel(region.dimension());
            if (level == null) {
                continue;
            }
            var reference = kind == UndoKind.UNDO ? region.after() : region.before();
            for (var entry : reference.long2ObjectEntrySet()) {
                if (level.getBlockState(net.minecraft.core.BlockPos.of(entry.getLongKey())) != entry.getValue().state()) {
                    mismatches++;
                }
            }
        }
        return mismatches;
    }
}
