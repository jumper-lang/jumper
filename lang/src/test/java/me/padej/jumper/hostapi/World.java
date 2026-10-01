package me.padej.jumper.hostapi;

/** Hands out entities as Object: the script holds them in `dyn`, with no static type to check against. */
public class World {
    public final Entity entity = new Entity();
    public final Zombie zombie = new Zombie();
    public final Dummy dummy = new Dummy();

    public Object get(String kind) {
        return switch (kind) {
            case "entity" -> entity;
            case "zombie" -> zombie;
            default -> dummy;
        };
    }
}
