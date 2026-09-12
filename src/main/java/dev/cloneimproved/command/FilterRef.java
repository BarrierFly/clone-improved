package dev.cloneimproved.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockPredicateArgument;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;

import java.util.function.Predicate;

/**
 * Source-side copy filter, resolved lazily at execution time.
 *
 * <p>{@link #ALL} is used for {@code replace}/first-class entries, {@link #NON_AIR} for vanilla
 * {@code masked} (≡ {@code mask_begin}), and {@link #blockPredicate(String)} for vanilla
 * {@code filtered <filter>}.
 */
@FunctionalInterface
public interface FilterRef {
    Predicate<BlockInWorld> resolve(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;

    FilterRef ALL = ctx -> b -> true;

    FilterRef NON_AIR = ctx -> b -> !b.getState().isAir();

    static FilterRef blockPredicate(String argumentName) {
        return ctx -> BlockPredicateArgument.getBlockPredicate(ctx, argumentName);
    }
}
