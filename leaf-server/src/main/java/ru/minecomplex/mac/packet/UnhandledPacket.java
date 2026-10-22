package ru.minecomplex.mac.packet;


/**
 * A play packet the core does not read: only its place on the wire matters (frames count serverbound packets, the
 * marker timeline counts clientbound ones). {@code name} is the native 26.3 packet type name.
 */
public final class UnhandledPacket {
    private final boolean clientbound;
    private final String name;

    public UnhandledPacket(boolean clientbound, String name) {
        this.clientbound = clientbound;
        this.name = name;
    }

    public boolean clientbound() {
        return clientbound;
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "UnhandledPacket[" + (clientbound ? "clientbound " : "serverbound ") + name + "]";
    }
}
