package dev.cloneimproved.undo;

import dev.cloneimproved.i18n.I18n;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/**
 * Assembles the undo/redo proposal and result messages (design doc §6.3), localized server-side
 * per recipient (§9.1).
 */
public final class UndoMessages {
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private UndoMessages() {
    }

    /** Shown to whoever created the proposal. */
    public static Component proposal(CommandSourceStack source, UndoHistoryManager.PendingProposal p, int modifiedBlocks) {
        return withWarning(I18n.text(source, key(p.kind(), "proposed"), bodyArgs(p)),
            I18n.text(source, "cloneimproved.undo.modified_warning", modifiedBlocks), modifiedBlocks);
    }

    /** Shown to the record owner when they are online and not the proposer. */
    public static Component proposalForOwner(ServerPlayer owner, UndoHistoryManager.PendingProposal p, int modifiedBlocks) {
        Object[] body = bodyArgs(p);
        Object[] args = new Object[body.length + 1];
        args[0] = p.proposerName();
        System.arraycopy(body, 0, args, 1, body.length);
        return withWarning(I18n.text(owner, key(p.kind(), "proposed_by"), args),
            I18n.text(owner, "cloneimproved.undo.modified_warning", modifiedBlocks), modifiedBlocks);
    }

    public static Component confirmed(CommandSourceStack source, UndoHistoryManager.PendingProposal p) {
        return I18n.text(source, key(p.kind(), "confirmed"), p.record().executorName(), p.record().affectedBlocks());
    }

    public static Component confirmedFor(ServerPlayer player, UndoHistoryManager.PendingProposal p) {
        return I18n.text(player, key(p.kind(), "confirmed"), p.record().executorName(), p.record().affectedBlocks());
    }

    public static Component cancelled(CommandSourceStack source, UndoKind kind) {
        return I18n.text(source, key(kind, "cancelled"));
    }

    public static Component cancelledFor(ServerPlayer player, UndoKind kind) {
        return I18n.text(player, key(kind, "cancelled"));
    }

    private static Object[] bodyArgs(UndoHistoryManager.PendingProposal p) {
        String regions = p.record().regions().stream()
            .map(RegionSnapshot::describe)
            .collect(Collectors.joining(", "));
        return new Object[] {
            p.record().commandLine(),
            TIME_FORMAT.format(p.record().time()),
            regions,
            p.record().affectedBlocks(),
        };
    }

    private static Component withWarning(Component text, Component warning, int modifiedBlocks) {
        if (modifiedBlocks <= 0) {
            return text;
        }
        return text.copy().append("\n").append(warning);
    }

    private static String key(UndoKind kind, String suffix) {
        return "cloneimproved." + (kind == UndoKind.UNDO ? "undo" : "redo") + "." + suffix;
    }
}
