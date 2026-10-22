package ru.minecomplex.mac.fixture.outbound;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * {@code move_entity_pos}, {@code move_entity_pos_rot}, {@code move_entity_rot}: a position delta in 1/4096 block
 * ({@code VecDeltaCodec}, relative to the client's codec base) and/or packed angles. From 26.3 a position delta may be
 * stepped: several deltas, each decoded from the previous one, with a tick offset each ({@link #deltas()}).
 *
 * @param yRot packed yaw (256 steps per turn), as on the wire
 * @param xRot packed pitch
 * @param path the deltas as a flat payload: i32 step count (0: linear), then per delta three i16 and, when stepped, an i32
 *             tick offset
 */
public record EntityMove(int entityId, boolean hasPosition, boolean hasRotation, byte yRot, byte xRot, boolean onGround,
                         byte[] path) implements OutboundEvent {

    public EntityMove {
        path = path.clone();
    }

    /** A linear delta (vanilla 26.3; 26.3's {@code VecDelta.Linear}). */
    public static EntityMove linear(int entityId, boolean hasPosition, boolean hasRotation, short xa, short ya, short za, byte yRot,
                                    byte xRot, boolean onGround) {
        return new EntityMove(entityId, hasPosition, hasRotation, yRot, xRot, onGround, encode(new short[][]{{xa, ya, za}}, null));
    }

    /** A stepped delta (26.3 {@code VecDelta.Stepped}): per step three deltas and its tick offset. */
    public static EntityMove stepped(int entityId, boolean hasRotation, short[][] deltas, int[] ticks, byte yRot, byte xRot,
                                     boolean onGround) {
        return new EntityMove(entityId, true, hasRotation, yRot, xRot, onGround, encode(deltas, ticks));
    }

    private static byte[] encode(short[][] deltas, int[] ticks) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(ticks == null ? 0 : deltas.length);
            for (int i = 0; i < deltas.length; i++) {
                for (short d : deltas[i]) out.writeShort(d);
                if (ticks != null) out.writeInt(ticks[i]);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    /** 0 for a linear delta, else the number of steps. */
    public int stepCount() {
        return read().ticks == null ? 0 : read().ticks.length;
    }

    /** The deltas: one for a linear packet, one per step otherwise. */
    public short[][] deltas() {
        return read().deltas;
    }

    /** The steps' tick offsets, {@code null} for a linear delta. */
    public int[] ticks() {
        return read().ticks;
    }

    private record Decoded(short[][] deltas, int[] ticks) {
    }

    private Decoded read() {
        try {
            var in = new DataInputStream(new ByteArrayInputStream(path));
            int steps = in.readInt();
            short[][] deltas = new short[Math.max(steps, 1)][3];
            int[] ticks = steps == 0 ? null : new int[steps];
            for (int i = 0; i < deltas.length; i++) {
                for (int a = 0; a < 3; a++) deltas[i][a] = in.readShort();
                if (ticks != null) ticks[i] = in.readInt();
            }
            return new Decoded(deltas, ticks);
        } catch (IOException malformed) {
            throw new UncheckedIOException(malformed);
        }
    }

    @Override
    public byte[] path() {
        return path.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EntityMove that && entityId == that.entityId && hasPosition == that.hasPosition
                && hasRotation == that.hasRotation && yRot == that.yRot && xRot == that.xRot && onGround == that.onGround
                && java.util.Arrays.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(entityId, hasPosition, hasRotation, yRot, xRot, onGround) * 31 + java.util.Arrays.hashCode(path);
    }
}
