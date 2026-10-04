package dev.cloneimproved.command;

import dev.cloneimproved.engine.MaskMode;
import dev.cloneimproved.transform.TransformOp;

import java.util.List;

/**
 * Everything one {@code /clone} invocation needs beyond the vanilla coordinate arguments.
 * Built statically by the command-tree builder (the tree path determines the whole spec),
 * evaluated at execution time by {@code CloneExecutor}.
 *
 * @param mask   destination-side mask mode ({@code mask_begin/mask_end/mask_both}; NONE otherwise)
 * @param filter source-side predicate (vanilla {@code filtered}); {@code null} = copy everything
 * @param ops    transform ops in command order; empty when no transform was chained
 * @param force  vanilla {@code force} (allows source/destination overlap)
 * @param strict 1.21.2+ {@code strict} branch
 */
public record CloneRequest(MaskMode mask, FilterRef filter, List<TransformOp> ops, boolean force, boolean strict) {

    /** Normalizes the transform list so the record is immutable on every construction path (and null fails fast here). */
    public CloneRequest {
        ops = List.copyOf(ops);
    }

    public static CloneRequest of(MaskMode mask, FilterRef filter, List<TransformOp> ops, boolean force, boolean strict) {
        return new CloneRequest(mask, filter, ops, force, strict);
    }
}
