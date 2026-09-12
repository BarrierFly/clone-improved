package dev.cloneimproved.undo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One executed {@code /clone}: identity, provenance and the dual region snapshots
 * (design doc §6.2). Kept in memory until the server stops.
 */
public record CloneRecord(UUID executor, String executorName, Instant time, String commandLine,
                          int affectedBlocks, boolean strict, List<RegionSnapshot> regions) {
}
