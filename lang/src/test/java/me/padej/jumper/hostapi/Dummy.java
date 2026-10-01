package me.padej.jumper.hostapi;

/** An unrelated class whose setHealth is allowed - to link a call site before a forbidden receiver arrives. */
public class Dummy {
    private int health = 5;

    public int getHealth() { return health; }

    public void setHealth(int h) { health = h; }
}
