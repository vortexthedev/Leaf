package ru.minecomplex.mac.fixture.outbound;

import ru.minecomplex.mac.entity.attribute.AttributeSnapshot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code update_attributes}. For every snapshot the client calls {@code setBaseValue(base)}, {@code removeModifiers()}
 * and {@code addTransientModifier} for each modifier in wire order ({@code ClientPacketListener.handleUpdateAttributes}).
 * Snapshots are kept as a flat payload ({@link #encode}/{@link #snapshots}) so recordings stay flat records.
 */
public record EntityAttributes(int entityId, byte[] payload) implements OutboundEvent {

    public EntityAttributes {
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EntityAttributes that && entityId == that.entityId && java.util.Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return 31 * entityId + java.util.Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "EntityAttributes[entityId=" + entityId + ", snapshots=" + snapshots() + "]";
    }

    public static EntityAttributes of(int entityId, List<AttributeSnapshot> snapshots) {
        return new EntityAttributes(entityId, encode(snapshots));
    }

    public List<AttributeSnapshot> snapshots() {
        return decode(payload);
    }

    public static byte[] encode(List<AttributeSnapshot> snapshots) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(snapshots.size());
            for (AttributeSnapshot snapshot : snapshots) {
                out.writeUTF(snapshot.attribute());
                out.writeDouble(snapshot.base());
                out.writeInt(snapshot.modifiers().size());
                for (AttributeSnapshot.Modifier modifier : snapshot.modifiers()) {
                    out.writeUTF(modifier.id());
                    out.writeDouble(modifier.amount());
                    out.writeByte(modifier.operation());
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    public static List<AttributeSnapshot> decode(byte[] payload) {
        try {
            var in = new DataInputStream(new ByteArrayInputStream(payload));
            int count = in.readInt();
            if (count < 0 || count > payload.length) throw new IOException("Invalid attribute count " + count);
            List<AttributeSnapshot> snapshots = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String attribute = in.readUTF();
                double base = in.readDouble();
                int modifierCount = in.readInt();
                if (modifierCount < 0 || modifierCount > payload.length) throw new IOException("Invalid modifier count " + modifierCount);
                List<AttributeSnapshot.Modifier> modifiers = new ArrayList<>(modifierCount);
                for (int j = 0; j < modifierCount; j++) {
                    modifiers.add(new AttributeSnapshot.Modifier(in.readUTF(), in.readDouble(), in.readUnsignedByte()));
                }
                snapshots.add(new AttributeSnapshot(attribute, base, modifiers));
            }
            if (in.read() != -1) throw new IOException("Trailing attribute payload");
            return List.copyOf(snapshots);
        } catch (IOException malformed) {
            throw new UncheckedIOException(malformed);
        }
    }
}
