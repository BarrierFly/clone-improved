package dev.cloneimproved.i18n;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.cloneimproved.CloneImproved;
import dev.cloneimproved.multiver.MultiversionHelpers;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side translation with literal text (design doc §9.1).
 *
 * <p>Custom {@code Component.translatable} keys would show up raw on vanilla clients, so messages
 * are formatted on the server using the player's reported language and sent as literal text.
 * The language is only known on 1.21.10+ (see {@link MultiversionHelpers#playerLanguage}); anything
 * unknown falls back to English.
 */
public final class I18n {
    private static final String LANG_DIR = "/assets/clone-improved/lang/";
    /**
     * Built once during {@link #init()} on the mod-init thread, then only read from arbitrary
     * server threads: published as an immutable map through a volatile field for safe publication.
     */
    private static volatile Map<String, Map<String, String>> LANGS = Map.of();

    private I18n() {
    }

    public static void init() {
        Map<String, Map<String, String>> langs = new HashMap<>();
        for (String lang : List.of("en_us", "zh_cn")) {
            Map<String, String> parsed = load(lang);
            if (parsed != null) {
                langs.put(lang, Map.copyOf(parsed));
            }
        }
        LANGS = Map.copyOf(langs);
    }

    /** Returns the parsed table for {@code lang}, or {@code null} when the file is missing/empty/broken. */
    private static Map<String, String> load(String lang) {
        Type type = new TypeToken<Map<String, String>>() {}.getType();
        try (InputStream in = I18n.class.getResourceAsStream(LANG_DIR + lang + ".json")) {
            if (in == null) {
                CloneImproved.LOGGER.warn("Missing built-in language file: {}", lang);
                return null;
            }
            Map<String, String> parsed = new Gson().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), type);
            return parsed == null ? null : Map.copyOf(parsed);
        } catch (Exception e) {
            CloneImproved.LOGGER.warn("Failed to load language {}", lang, e);
            return null;
        }
    }

    /** Picks the best available language for a command source (players by their client locale, else English). */
    public static String locale(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return normalize(MultiversionHelpers.playerLanguage(player));
        }
        return "en_us";
    }

    public static String locale(ServerPlayer player) {
        return normalize(MultiversionHelpers.playerLanguage(player));
    }

    private static String normalize(String lang) {
        if (lang != null) {
            String lower = lang.toLowerCase(Locale.ROOT);
            if (LANGS.containsKey(lower)) {
                return lower;
            }
            if (lower.startsWith("zh")) {
                return "zh_cn";
            }
        }
        return "en_us";
    }

    public static Component text(CommandSourceStack source, String key, Object... args) {
        return Component.literal(format(locale(source), key, args));
    }

    public static Component text(ServerPlayer player, String key, Object... args) {
        return Component.literal(format(locale(player), key, args));
    }

    private static String format(String locale, String key, Object... args) {
        Map<String, String> lang = LANGS.get(locale);
        String template = lang == null ? null : lang.get(key);
        if (template == null) {
            Map<String, String> english = LANGS.get("en_us");
            template = english == null ? null : english.get(key);
        }
        if (template == null) {
            return key;
        }
        try {
            // Locale.ROOT: numeric/date conversions must not follow the server's default locale
            // (the surrounding text already follows the player's language).
            return String.format(Locale.ROOT, template, args);
        } catch (IllegalFormatException e) {
            CloneImproved.LOGGER.warn("Bad translation format for {}: {}", key, e.toString());
            return template;
        }
    }
}
