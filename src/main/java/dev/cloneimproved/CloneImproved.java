package dev.cloneimproved;

import dev.cloneimproved.command.CloneCommandExtension;
import dev.cloneimproved.config.CloneImprovedConfig;
import dev.cloneimproved.i18n.I18n;
import dev.cloneimproved.undo.UndoHistoryManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * clone-improved: extends the vanilla {@code /clone} command with composable
 * move/rotate/mirror transforms, {@code mask_begin}/{@code mask_end}/{@code mask_both}
 * and an undo/redo history — entirely server-side, no mixins (design doc §1/§2.4).
 */
public final class CloneImproved implements ModInitializer {
    public static final String MOD_ID = "clone-improved";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CloneImprovedConfig.load();
        I18n.init();
        // Fires after vanilla command registration, so our tree merges into the existing one.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            CloneCommandExtension.register(dispatcher, registryAccess));
        // Undo/redo state is per server run: wipe it on every start and stop so a proposal or
        // history from a previous run can never leak into a new world (records are per-run by design).
        ServerLifecycleEvents.SERVER_STARTED.register(server -> UndoHistoryManager.reset());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> UndoHistoryManager.reset());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> UndoHistoryManager.reset());
        LOGGER.info("clone-improved initialized");
    }
}
