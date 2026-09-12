package dev.cloneimproved.undo;

import dev.cloneimproved.CloneImproved;
import dev.cloneimproved.config.CloneImprovedConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player LIFO undo/redo stacks plus the single global pending proposal (design doc §6.1/§6.3).
 *
 * <p>Records survive player disconnects and are only cleared when the server stops.
 * Undo/redo themselves are region writes too: a confirmed undo moves the record from the undo
 * stack to the redo stack and vice versa, keeping the pair strictly LIFO.
 */
public final class UndoHistoryManager {
    /** {@code proposer} proposed restoring {@code owner}'s {@code record}; at most one exists at a time. */
    public record PendingProposal(UUID proposer, String proposerName, UUID owner, String ownerName,
                                  CloneRecord record, UndoKind kind) {
    }

    private static final class PlayerHistory {
        final Deque<CloneRecord> undo = new ArrayDeque<>();
        final Deque<CloneRecord> redo = new ArrayDeque<>();
    }

    private static final Map<UUID, PlayerHistory> HISTORIES = new ConcurrentHashMap<>();
    /** Global insertion order used for memory-budget eviction. */
    private static final List<CloneRecord> INSERTION_ORDER = new ArrayList<>();
    private static PendingProposal pending;

    private UndoHistoryManager() {
    }

    public static void onServerStopping() {
        HISTORIES.clear();
        INSERTION_ORDER.clear();
        pending = null;
    }

    public static PendingProposal pending() {
        return pending;
    }

    public static void setPending(PendingProposal proposal) {
        pending = proposal;
    }

    /** Registers a freshly executed clone: clears the player's redo stack, pushes the record, applies capacity limits. */
    public static void recordClone(UUID executor, CloneRecord record) {
        PlayerHistory history = history(executor);
        history.redo.clear();
        history.undo.addLast(record);
        INSERTION_ORDER.add(record);
        // A pending proposal about this player's last record is now invalidated (its record is no
        // longer the stack top), so drop it instead of leaving a zombie that blocks new proposals.
        if (pending != null && pending.owner().equals(executor)) {
            pending = null;
        }
        trim(executor, history);
    }

    /** Stack top for the given kind, or {@code null} when the player has nothing there. */
    public static CloneRecord peek(UUID owner, UndoKind kind) {
        Deque<CloneRecord> stack = stack(owner, kind);
        return stack.isEmpty() ? null : stack.peekLast();
    }

    /** Moves the stack top across to the opposite stack; only call after a successful restore. */
    public static void transfer(UUID owner, UndoKind kind) {
        PlayerHistory history = history(owner);
        CloneRecord record = stack(owner, kind).pollLast();
        if (record != null) {
            stack(owner, kind.opposite()).addLast(record);
        }
    }

    private static Deque<CloneRecord> stack(UUID owner, UndoKind kind) {
        return kind == UndoKind.UNDO ? history(owner).undo : history(owner).redo;
    }

    private static PlayerHistory history(UUID owner) {
        return HISTORIES.computeIfAbsent(owner, id -> new PlayerHistory());
    }

    private static void trim(UUID executor, PlayerHistory history) {
        CloneImprovedConfig config = CloneImprovedConfig.get();
        while (history.undo.size() > config.maxRecordsPerPlayer) {
            CloneRecord evicted = history.undo.pollFirst();
            INSERTION_ORDER.remove(evicted);
        }
        long budget = (long) config.maxTotalMemoryMiB * 1024L * 1024L;
        long usage = estimateUsage();
        Iterator<CloneRecord> oldest = INSERTION_ORDER.iterator();
        while (usage > budget && oldest.hasNext()) {
            CloneRecord candidate = oldest.next();
            if (pending != null && pending.record() == candidate) {
                continue; // never evict the record a proposal is currently about
            }
            oldest.remove();
            removeFromStacks(candidate);
            usage -= estimate(candidate);
        }
        if (usage > budget) {
            CloneImproved.LOGGER.warn("Undo history exceeds the memory budget; oldest records were evicted");
        }
    }

    private static void removeFromStacks(CloneRecord record) {
        for (PlayerHistory history : HISTORIES.values()) {
            history.undo.remove(record);
            history.redo.remove(record);
        }
    }

    /**
     * Rough per-record footprint: block positions dominate; block-entity NBT is estimated flat
     * per entry (Reden-style heuristic; exact NBT sizing is not worth the cost on this path).
     */
    private static long estimateUsage() {
        long total = 0;
        for (PlayerHistory history : HISTORIES.values()) {
            for (CloneRecord record : history.undo) {
                total += estimate(record);
            }
            for (CloneRecord record : history.redo) {
                total += estimate(record);
            }
        }
        return total;
    }

    private static long estimate(CloneRecord record) {
        long blocks = 0;
        long blockEntities = 0;
        for (RegionSnapshot region : record.regions()) {
            blocks += region.recordedBlocks();
            blockEntities += region.recordedBlockEntities();
        }
        return blocks * 96L + blockEntities * 768L;
    }

    /** Test/inspection helper: number of players with any history. */
    public static int playerCount() {
        return HISTORIES.size();
    }
}
