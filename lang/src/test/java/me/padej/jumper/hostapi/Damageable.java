package me.padej.jumper.hostapi;

/** A host API interface: a rule on it must reach every implementation. */
public interface Damageable {
    void damage(int n);
}
