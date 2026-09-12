package dev.cloneimproved.undo;

/** Which side of the LIFO pair a proposal operates on. */
public enum UndoKind {
    UNDO,
    REDO;

    public UndoKind opposite() {
        return this == UNDO ? REDO : UNDO;
    }
}
