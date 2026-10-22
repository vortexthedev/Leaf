package ru.minecomplex.mac.fixture.inbound;

/**
 * {@code accept_teleportation}. From protocol 777 it carries the client's position and rotation after applying
 * the teleport ({@link #hasPosition()}); earlier protocols follow it with a separate {@code PosRot}.
 */
public record TeleportAccept(int id, boolean hasPosition, double x, double y, double z, float yRot, float xRot)
        implements InboundEvent {

    public static TeleportAccept idOnly(int id) {
        return new TeleportAccept(id, false, 0, 0, 0, 0, 0);
    }

    public static TeleportAccept withPosition(int id, double x, double y, double z, float yRot, float xRot) {
        return new TeleportAccept(id, true, x, y, z, yRot, xRot);
    }
}
