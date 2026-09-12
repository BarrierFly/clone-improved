package dev.cloneimproved.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.cloneimproved.engine.CloneExecutor;
import dev.cloneimproved.engine.MaskMode;
import dev.cloneimproved.transform.TransformOp;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.blocks.BlockPredicateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;

import java.util.ArrayList;
import java.util.List;

/**
 * Merges the extended {@code /clone} grammar into the vanilla command tree (design doc §3.2/§3.3).
 *
 * <p><b>Brigadier merge rules</b> (verified identical in brigadier 1.2.9 and 1.3.10):
 * registering a node whose name already exists merges the two recursively, and
 * {@code CommandNode#addChild} <em>overwrites the existing child's command</em> with ours when we
 * set one. Hence two hard rules for this class:
 * <ol>
 *   <li>Our copies of nodes that already exist in the vanilla tree ({@code clone}, {@code begin},
 *       {@code end}, {@code destination}, {@code from}/{@code to} and their dimension arguments,
 *       {@code replace}/{@code masked}/{@code filtered}, the vanilla {@code move}, {@code strict},
 *       {@code filter}) must never call {@code executes()} — that would hijack vanilla behaviour.</li>
 *   <li>Only brand-new literals ({@code undo}, {@code redo}, {@code mask_*}, first-class
 *       {@code move}, {@code rotate}, {@code mirror}, chain-terminal {@code force}) carry
 *       {@code executes()}.</li>
 * </ol>
 *
 * <p>The transform chain grammar (§3.1): {@code [mask_*] transform+} where {@code move} appears
 * exactly once, {@code rotate}/{@code mirror} at most once each, in command-written order, plus
 * an optional trailing {@code force}; a lone {@code mask_*} (optionally with {@code force}) is
 * also valid. {@link #attachContinuations} generates exactly those paths and only attaches
 * {@code executes()} at sequence endpoints after {@code move} — "rotate without move" or a
 * second "move" cannot even parse.
 */
public final class CloneCommandExtension {
    /** Dimension resolution + strict flag for one registration branch. */
    private record ChainContext(DimRef fromDim, DimRef toDim, boolean strict) {
    }

    private CloneCommandExtension() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        dispatcher.register(
            Commands.literal("clone")
                // NOTE: no executes()/requires() on this node — it merges into the vanilla one.
                .then(UndoSubCommands.undoTree())
                .then(UndoSubCommands.redoTree())
                .then(beginEnd(buildContext, DimRef.sourceLevel(), DimRef.sourceLevel()))
                .then(
                    Commands.literal("from")
                        .then(
                            Commands.argument("sourceDimension", DimensionArgument.dimension())
                                .then(beginEnd(buildContext, DimRef.dimensionArg("sourceDimension"), DimRef.sourceLevel()))
                        )
                )
        );
    }

    /** Mirrors vanilla: {@code begin → end → (destination… | to → targetDimension → destination…)}. */
    private static RequiredArgumentBuilder<CommandSourceStack, ?> beginEnd(CommandBuildContext buildContext, DimRef fromDim, DimRef toDim) {
        return Commands.argument("begin", BlockPosArgument.blockPos())
            .then(
                Commands.argument("end", BlockPosArgument.blockPos())
                    .then(destinationTree(buildContext, fromDim, toDim))
                    .then(
                        Commands.literal("to")
                            .then(
                                Commands.argument("targetDimension", DimensionArgument.dimension())
                                    .then(destinationTree(buildContext, fromDim, DimRef.dimensionArg("targetDimension")))
                            )
                    )
            );
    }

    /**
     * {@code destination} plus — on 1.21.2+ — the {@code strict} branch with the identical chain
     * set, exactly where vanilla puts them.
     */
    private static RequiredArgumentBuilder<CommandSourceStack, ?> destinationTree(CommandBuildContext buildContext, DimRef fromDim, DimRef toDim) {
        RequiredArgumentBuilder<CommandSourceStack, ?> destination = Commands.argument("destination", BlockPosArgument.blockPos());
        attachChainSet(destination, buildContext, new ChainContext(fromDim, toDim, false));
        //? if >=1.21.2 {
        LiteralArgumentBuilder<CommandSourceStack> strict = Commands.literal("strict");
        attachChainSet(strict, buildContext, new ChainContext(fromDim, toDim, true));
        destination.then(strict);
        //?}
        return destination;
    }

    /**
     * Everything that may follow {@code destination} (or {@code strict}): the mask literals and
     * first-class transforms, plus rotate/mirror continuations hanging off the vanilla
     * {@code replace|masked|filtered → move} path.
     */
    private static void attachChainSet(ArgumentBuilder<CommandSourceStack, ?> node, CommandBuildContext buildContext, ChainContext chain) {
        for (MaskMode mask : MaskMode.MASKS) {
            node.then(maskEntry(chain, mask));
        }
        node.then(moveEntry(chain, MaskMode.NONE, null, List.of()));
        node.then(rotateEntry(chain, MaskMode.NONE, null, List.of()));
        node.then(mirrorEntry(chain, MaskMode.NONE, null, List.of()));
        // Our replace/masked/filtered copies carry no executes(); they merge into the vanilla
        // literals, and their move copies merge into the vanilla move literal.
        node.then(Commands.literal("replace").then(moveContinuation(chain, MaskMode.NONE, FilterRef.ALL)));
        node.then(Commands.literal("masked").then(moveContinuation(chain, MaskMode.BEGIN, null)));
        node.then(
            Commands.literal("filtered")
                .then(
                    Commands.argument("filter", BlockPredicateArgument.blockPredicate(buildContext))
                        .then(moveContinuation(chain, MaskMode.NONE, FilterRef.blockPredicate("filter")))
                )
        );
    }

    /** {@code mask_begin|mask_end|mask_both}: valid alone, with {@code force}, or followed by transforms. */
    private static LiteralArgumentBuilder<CommandSourceStack> maskEntry(ChainContext chain, MaskMode mask) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(mask.token());
        node.executes(runner(chain, CloneRequest.of(mask, null, List.of(), false, chain.strict())));
        node.then(Commands.literal("force").executes(runner(chain, CloneRequest.of(mask, null, List.of(), true, chain.strict()))));
        node.then(moveEntry(chain, mask, null, List.of()));
        node.then(rotateEntry(chain, mask, null, List.of()));
        node.then(mirrorEntry(chain, mask, null, List.of()));
        return node;
    }

    /** First-class {@code move}: a new literal, so it owns an executes() at its own endpoint. */
    private static LiteralArgumentBuilder<CommandSourceStack> moveEntry(ChainContext chain, MaskMode mask, FilterRef filter, List<TransformOp> prefix) {
        LiteralArgumentBuilder<CommandSourceStack> move = Commands.literal("move");
        attachContinuations(move, chain, mask, filter, append(prefix, TransformOp.Move.INSTANCE), true);
        return move;
    }

    /**
     * Our {@code move} copy under vanilla {@code replace|masked|filtered}. It merges into the
     * vanilla move node, which already has an executes() — so this copy must stay command-free
     * and only contribute rotate/mirror/force continuations.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> moveContinuation(ChainContext chain, MaskMode mask, FilterRef filter) {
        LiteralArgumentBuilder<CommandSourceStack> move = Commands.literal("move");
        attachContinuations(move, chain, mask, filter, append(List.of(), TransformOp.Move.INSTANCE), false);
        return move;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rotateEntry(ChainContext chain, MaskMode mask, FilterRef filter, List<TransformOp> prefix) {
        LiteralArgumentBuilder<CommandSourceStack> rotate = Commands.literal("rotate");
        for (TransformOp.RotationDir dir : TransformOp.RotationDir.values()) {
            LiteralArgumentBuilder<CommandSourceStack> dirNode = Commands.literal(dir.token());
            attachContinuations(dirNode, chain, mask, filter, append(prefix, new TransformOp.Rotate(dir)), true);
            rotate.then(dirNode);
        }
        return rotate;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mirrorEntry(ChainContext chain, MaskMode mask, FilterRef filter, List<TransformOp> prefix) {
        LiteralArgumentBuilder<CommandSourceStack> mirror = Commands.literal("mirror");
        for (boolean xAxis : new boolean[] {true, false}) {
            LiteralArgumentBuilder<CommandSourceStack> axis = Commands.literal(xAxis ? "x" : "z");
            // The Double coordinate is only known at execution time; the marker carries NaN and
            // CloneExecutor substitutes the parsed "coordinate" argument.
            TransformOp.Mirror marker = new TransformOp.Mirror(xAxis, Double.NaN);
            RequiredArgumentBuilder<CommandSourceStack, ?> coordinate =
                Commands.argument("coordinate", DoubleArgumentType.doubleArg());
            attachContinuations(coordinate, chain, mask, filter, append(prefix, marker), true);
            axis.then(coordinate);
            mirror.then(axis);
        }
        return mirror;
    }

    /**
     * Recursively attaches every still-valid next token to a chain node. {@code executes()} is
     * attached only once the chain contains a {@code move} (and the node is ours), optionally
     * followed by a terminal {@code force}.
     */
    private static void attachContinuations(ArgumentBuilder<CommandSourceStack, ?> node, ChainContext chain,
                                            MaskMode mask, FilterRef filter, List<TransformOp> ops, boolean allowExecutesHere) {
        boolean hasMove = false;
        boolean hasRotate = false;
        boolean hasMirror = false;
        for (TransformOp op : ops) {
            if (op instanceof TransformOp.Move) {
                hasMove = true;
            } else if (op instanceof TransformOp.Rotate) {
                hasRotate = true;
            } else if (op instanceof TransformOp.Mirror) {
                hasMirror = true;
            }
        }
        if (hasMove && allowExecutesHere) {
            node.executes(runner(chain, CloneRequest.of(mask, filter, ops, false, chain.strict())));
        }
        if (hasMove) {
            node.then(Commands.literal("force").executes(runner(chain, CloneRequest.of(mask, filter, ops, true, chain.strict()))));
        }
        if (!hasMove) {
            node.then(moveEntry(chain, mask, filter, ops));
        }
        if (!hasRotate) {
            node.then(rotateEntry(chain, mask, filter, ops));
        }
        if (!hasMirror) {
            node.then(mirrorEntry(chain, mask, filter, ops));
        }
    }

    private static List<TransformOp> append(List<TransformOp> ops, TransformOp op) {
        List<TransformOp> next = new ArrayList<>(ops.size() + 1);
        next.addAll(ops);
        next.add(op);
        return List.copyOf(next);
    }

    private static Command<CommandSourceStack> runner(ChainContext chain, CloneRequest request) {
        return ctx -> CloneExecutor.execute(ctx, request, chain.fromDim(), chain.toDim());
    }
}
