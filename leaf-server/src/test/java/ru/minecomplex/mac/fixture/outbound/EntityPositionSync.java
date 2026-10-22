package ru.minecomplex.mac.fixture.outbound;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * {@code entity_position_sync}: the entity's absolute position (the end of a 26.3 stepped path), rotation and ground flag.
 * Native entity sync carries the position and rotation used by the handler.
 *
 * @param steps a 26.3 stepped path as a flat payload: i32 count, then per step three f64 and an i32 tick offset; empty
 *              for a linear position
 */
public record EntityPositionSync(int entityId, double x, double y, double z, float yRot, float xRot, boolean onGround,
                                 byte[] steps) implements OutboundEvent {

    public EntityPositionSync {
        steps = steps.clone();
    }

    public static EntityPositionSync linear(int entityId, double x, double y, double z, float yRot, float xRot, boolean onGround) {
        return new EntityPositionSync(entityId, x, y, z, yRot, xRot, onGround, new byte[0]);
    }

    /** A stepped path; its last step is the end position. */
    public static EntityPositionSync stepped(int entityId, double[][] positions, int[] tickOffsets, float yRot, float xRot,
                                             boolean onGround) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(positions.length);
            for (int i = 0; i < positions.length; i++) {
                for (double v : positions[i]) out.writeDouble(v);
                out.writeInt(tickOffsets[i]);
            }
            double[] end = positions[positions.length - 1];
            return new EntityPositionSync(entityId, end[0], end[1], end[2], yRot, xRot, onGround, bytes.toByteArray());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    /** The stepped path's positions, {@code null} for a linear position. */
    public double[][] stepPositions() {
        if (steps.length == 0) return null;
        try {
            var in = new DataInputStream(new ByteArrayInputStream(steps));
            double[][] positions = new double[in.readInt()][3];
            for (double[] position : positions) {
                for (int a = 0; a < 3; a++) position[a] = in.readDouble();
                in.readInt();
            }
            return positions;
        } catch (IOException malformed) {
            throw new UncheckedIOException(malformed);
        }
    }

    /** The stepped path's tick offsets, {@code null} for a linear position. */
    public int[] stepTicks() {
        if (steps.length == 0) return null;
        try {
            var in = new DataInputStream(new ByteArrayInputStream(steps));
            int[] ticks = new int[in.readInt()];
            for (int i = 0; i < ticks.length; i++) {
                for (int a = 0; a < 3; a++) in.readDouble();
                ticks[i] = in.readInt();
            }
            return ticks;
        } catch (IOException malformed) {
            throw new UncheckedIOException(malformed);
        }
    }

    @Override
    public byte[] steps() {
        return steps.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EntityPositionSync that && entityId == that.entityId
                && Double.doubleToLongBits(x) == Double.doubleToLongBits(that.x) && Double.doubleToLongBits(y) == Double.doubleToLongBits(that.y)
                && Double.doubleToLongBits(z) == Double.doubleToLongBits(that.z) && Float.floatToIntBits(yRot) == Float.floatToIntBits(that.yRot)
                && Float.floatToIntBits(xRot) == Float.floatToIntBits(that.xRot) && onGround == that.onGround
                && java.util.Arrays.equals(steps, that.steps);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(entityId, x, y, z, yRot, xRot, onGround) * 31 + java.util.Arrays.hashCode(steps);
    }
}
