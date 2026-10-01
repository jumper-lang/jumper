package me.padej.jumper.hostapi;

/** A host API class for the access-policy tests, the kind a game server hands to mods. */
public class Player {
    private int health = 20;
    public int health() { return health; }
    public void heal(int n) { health += n; }
    public void ban() { throw new IllegalStateException("ban called from a script"); }
}
