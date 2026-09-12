package dev.cloneimproved.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.server.level.ServerLevel;

/**
 * How the source/target dimension of a clone path is resolved — mirrors vanilla's overloads:
 * plain {@code /clone begin end destination}, {@code from <sourceDimension> ...} and
 * {@code to <targetDimension> ...}.
 */
@FunctionalInterface
public interface DimRef {
    ServerLevel get(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;

    static DimRef sourceLevel() {
        return ctx -> ctx.getSource().getLevel();
    }

    static DimRef dimensionArg(String name) {
        return ctx -> DimensionArgument.getDimension(ctx, name);
    }
}
