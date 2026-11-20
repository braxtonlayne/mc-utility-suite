package com.utilitysuite.jobs.schematic.format;

/** A schematic file is malformed or of an unsupported format. */
public class SchematicParseException extends Exception {

    public SchematicParseException(String message) {
        super(message);
    }

    public SchematicParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
