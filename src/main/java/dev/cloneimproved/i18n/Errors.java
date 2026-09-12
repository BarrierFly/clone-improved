package dev.cloneimproved.i18n;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;

/**
 * Command errors whose text must be localized server-side (unlike vanilla's
 * {@code translatable} keys, which vanilla clients localize themselves).
 */
public final class Errors {
    private Errors() {
    }

    public static CommandSyntaxException simple(CommandSourceStack source, String key, Object... args) {
        return new SimpleCommandExceptionType(I18n.text(source, key, args)).create();
    }
}
