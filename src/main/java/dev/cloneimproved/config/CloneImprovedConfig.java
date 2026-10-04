package dev.cloneimproved.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.cloneimproved.CloneImproved;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * {@code config/clone-improved.json}: capacity limits for the in-memory undo history
 * (design doc §6.2).
 */
public final class CloneImprovedConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Written by {@link #load()} on the mod-init thread, read from server threads: publish it safely. */
    private static volatile CloneImprovedConfig instance;

    /** Maximum undo records kept per player (oldest evicted first). */
    public int maxRecordsPerPlayer = 32;

    /** Global soft memory budget for all snapshots, in MiB. */
    public int maxTotalMemoryMiB = 64;

    public static synchronized void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("clone-improved.json");
        CloneImprovedConfig config = new CloneImprovedConfig();
        if (Files.exists(path)) {
            try {
                config = GSON.fromJson(Files.readString(path), CloneImprovedConfig.class);
            } catch (Exception e) {
                CloneImproved.LOGGER.warn("Could not read {}, using defaults", path, e);
                config = new CloneImprovedConfig();
            }
        }
        if (config == null) {
            CloneImproved.LOGGER.warn("{} is empty or not a JSON object; using defaults", path);
            config = new CloneImprovedConfig();
        }
        if (config.maxRecordsPerPlayer <= 0) {
            CloneImproved.LOGGER.warn("maxRecordsPerPlayer must be positive, got {}; using 32", config.maxRecordsPerPlayer);
            config.maxRecordsPerPlayer = 32;
        }
        if (config.maxTotalMemoryMiB <= 0) {
            CloneImproved.LOGGER.warn("maxTotalMemoryMiB must be positive, got {}; using 64", config.maxTotalMemoryMiB);
            config.maxTotalMemoryMiB = 64;
        }
        instance = config;
        if (!Files.exists(path)) {
            writeDefaults(path, config);
        }
        CloneImproved.LOGGER.info("clone-improved config loaded: maxRecordsPerPlayer={}, maxTotalMemoryMiB={}",
            instance.maxRecordsPerPlayer, instance.maxTotalMemoryMiB);
    }

    /** Writes the config through a temp file + atomic move so a crash can never leave a truncated file. */
    private static void writeDefaults(Path path, CloneImprovedConfig config) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = Files.createTempFile(path.getParent(), "clone-improved-", ".tmp");
            try {
                Files.writeString(tmp, GSON.toJson(config));
                try {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            CloneImproved.LOGGER.warn("Could not write {}: {}", path, e.toString());
        }
    }

    public static CloneImprovedConfig get() {
        CloneImprovedConfig config = instance;
        if (config == null) {
            load();
            config = instance;
        }
        return config;
    }
}
