package me.padej.jumper.hostapi;

/** A subclass that overrides the closed method: the override is the same method for the policy. */
public class Zombie extends Entity {
    @Override
    public void setHealth(int h) { health = Math.min(h, 40); }

    @Override
    public void damage(int n) { health -= 2 * n; }
}
