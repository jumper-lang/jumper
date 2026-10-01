package me.padej.jumper.script;

import me.padej.jumper.runtime.Cell;
import me.padej.jumper.runtime.CellStore;

import javax.script.Bindings;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Engine Bindings on top of cells: a name's value lives in a {@link Cell}, not in a Map entry.
 * Compiled script code reads the cell directly (one field load instead of Map.get by string),
 * and the host reads and writes the same cell via ordinary get/put - so the behavior is the same as SimpleBindings.
 *
 * <p>Once created, a cell is never removed: compiled code holds a reference to it, and replacing
 * the cell after remove+put would split host and script apart. So remove clears the value and
 * drops the present flag, while the cell itself stays.
 */
public final class CellBindings extends AbstractMap<String, Object> implements Bindings, CellStore {
    private final Map<String, Cell> cells = new LinkedHashMap<>();

    @Override
    public synchronized Cell cell(String name) {
        return cells.computeIfAbsent(name, Cell::new);
    }

    private static void checkKey(Object key) {
        if (key == null) throw new NullPointerException("key can not be null");
        if (!(key instanceof String s)) throw new ClassCastException("key should be a String");
        if (s.isEmpty()) throw new IllegalArgumentException("key can not be empty");
    }

    @Override
    public synchronized Object put(String name, Object value) {
        checkKey(name);
        Cell c = cell(name);
        Object old = c.present ? c.v : null;
        c.set(value);
        return old;
    }

    @Override
    public synchronized Object get(Object key) {
        checkKey(key);
        Cell c = cells.get(key);
        return c == null || !c.present ? null : c.v;
    }

    @Override
    public synchronized boolean containsKey(Object key) {
        checkKey(key);
        Cell c = cells.get(key);
        return c != null && c.present;
    }

    @Override
    public synchronized Object remove(Object key) {
        checkKey(key);
        Cell c = cells.get(key);
        if (c == null || !c.present) return null;
        Object old = c.v;
        c.clear();   // the cell is not discarded: compiled code refers to it
        return old;
    }

    @Override
    public synchronized void putAll(Map<? extends String, ?> m) {
        for (Map.Entry<? extends String, ?> e : m.entrySet()) put(e.getKey(), e.getValue());
    }

    @Override
    public synchronized Set<Entry<String, Object>> entrySet() {
        Set<Entry<String, Object>> out = new LinkedHashSet<>();
        for (Cell c : cells.values()) if (c.present) out.add(new SimpleEntry<>(c.name, c.v));
        return out;
    }
}
