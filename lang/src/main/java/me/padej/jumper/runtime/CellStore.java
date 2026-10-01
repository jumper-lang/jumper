package me.padej.jumper.runtime;

/**
 * Store of top-level names that can hand out a stable cell per name.
 * Implemented by the ScriptEngine bindings; the parser asks for the cell once per access site.
 * If the store cannot do this (a foreign Bindings implementation), access stays via Map.
 */
public interface CellStore {
    /** Cell of a name; created on first access and lives as long as the store. */
    Cell cell(String name);
}
