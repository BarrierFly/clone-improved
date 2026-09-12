package dev.cloneimproved.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.cloneimproved.CloneImproved;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code config/clone-improved.json}: capacity limits for the in-memory undo history
 * (design doc §6.2).
 */
public final class CloneImprovedConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static CloneImprovedConfig instance;

    /** Maximum undo records kept per player (oldest evicted first). */
    public int maxRecordsPerPlayer = 32;

    /** Global soft memory budget for all snapshots, in MiB. */
    public int maxTotalMemoryMiB = 64;

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("clone-improved.json");
        CloneImprovedConfig config = new CloneImprovedConfig();
        if (Files.exists(path)) {
            try {
                config = GSON.fromJson(Files.readString(path), CloneImprovedConfig.class);
            } catch (Exception e) {
                CloneImproved.LOGGER.warn("Could not read {}, using defaults: {}", path, e.toString());
                config = new CloneImprovedConfig();
            }
        }
        if (config == null) {
            config = new CloneImprovedConfig();
        }
        if (config.maxRecordsPerPlayer <= 0) {
            config.maxRecordsPerPlayer = 32;
        }
        if (config.maxTotalMemoryMiB <= 0) {
            config.maxTotalMemoryMiB = 64;
        }
        instance = config;
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(config));
            } catch (IOException e) {
                CloneImproved.LOGGER.warn("Could not write {}: {}", path, e.toString());
            }
        }
        CloneImproved.LOGGER.info("clone-improved config loaded: maxRecordsPerPlayer={}, maxTotalMemoryMiB={}",
            instance.maxRecordsPerPlayer, instance.maxTotalMemoryMiB);
    }

    public static CloneImprovedConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }
}
