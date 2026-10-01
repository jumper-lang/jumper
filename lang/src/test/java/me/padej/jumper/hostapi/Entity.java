package me.padej.jumper.hostapi;

/** A game entity as a server hands it to mods: health readable by scripts, setHealth closed by the policy. */
public class Entity implements Damageable {
    protected int health = 20;

    public int getHealth() { return health; }

    public void setHealth(int h) { health = h; }

    @Override
    public void damage(int n) { health -= n; }

    public String name() { return getClass().getSimpleName(); }
}
