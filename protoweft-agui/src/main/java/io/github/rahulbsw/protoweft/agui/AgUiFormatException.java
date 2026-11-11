package io.github.rahulbsw.protoweft.agui;

/**
 * A field the AG-UI protocol describes carries a value its schema rejects, or a message breaks the
 * protocol's sequence rules. Per the spec's processing model this is fatal: the run (or the input)
 * is rejected, never repaired.
 */
public class AgUiFormatException extends IllegalArgumentException {

    public AgUiFormatException(String message) {
        super(message);
    }
}
