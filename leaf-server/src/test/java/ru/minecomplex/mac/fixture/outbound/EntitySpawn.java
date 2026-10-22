package ru.minecomplex.mac.fixture.outbound;

/**
 * Decoded client entity creation; id reuse creates a new entity generation. {@code uuidMost}/{@code uuidLeast}: the
 * entity's UUID, whose string form names a non-player entity on the scoreboard ({@code Entity.getScoreboardName}) and
 * whose {@code PlayerInfo} gives a player entity its profile.
 */
public record EntitySpawn(int entityId, String type, double x, double y, double z, float yaw, float pitch,
                          float headYaw, int data, boolean hasVelocity, double velocityX, double velocityY,
                          double velocityZ, long uuidMost, long uuidLeast) implements OutboundEvent {

    /** A spawn whose UUID is not known (zero). */
    public EntitySpawn(int entityId, String type, double x, double y, double z, float yaw, float pitch, float headYaw, int data,
                       boolean hasVelocity, double velocityX, double velocityY, double velocityZ) {
        this(entityId, type, x, y, z, yaw, pitch, headYaw, data, hasVelocity, velocityX, velocityY, velocityZ, 0L, 0L);
    }

    public java.util.UUID uuid() {
        return new java.util.UUID(uuidMost, uuidLeast);
    }
}
