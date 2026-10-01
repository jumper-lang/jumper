package me.padej.jumper.workspace;

/**
 * Stands in for a value the host defines for its scripts (`server`, `log`) when a script is checked, not
 * run: the name exists, its value does not. `type` is what the host descriptor declares.
 */
public record HostGlobal(String name, String type) {
    @Override
    public String toString() {
        return "<" + name + ": " + type + ">";
    }
}
