package com.hiveworkshop.model;

/**
 * A configured external 3D program.
 * @param name      display name (e.g. "Blender")
 * @param command   path to the executable
 * @param arguments argument template — use {@code {file}} as placeholder for the model path
 */
public record ExternalProgram(String name, String command, String arguments) {
    /** Backwards-compatible constructor for entries without separate arguments. */
    public ExternalProgram(String name, String command) {
        this(name, command, "");
    }
}
