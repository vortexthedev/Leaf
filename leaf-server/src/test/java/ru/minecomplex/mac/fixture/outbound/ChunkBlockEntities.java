package ru.minecomplex.mac.fixture.outbound;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/** Flat recording payload preserving chunk block-entity order, positions, registry keys and typed NBT. */
public final class ChunkBlockEntities {
    private ChunkBlockEntities() { }

    public static byte[] encode(List<BlockEntityData> entries) {
        if (entries.isEmpty()) return new byte[0];
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(entries.size());
            for (var entry : entries) {
                out.writeInt(entry.x()); out.writeInt(entry.y()); out.writeInt(entry.z());
                out.writeUTF(entry.type());
                byte[] nbt = entry.nbt();
                out.writeInt(nbt.length); out.write(nbt);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    public static List<BlockEntityData> decode(byte[] bytes) {
        if (bytes.length == 0) return List.of();
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            int count = in.readInt();
            if (count < 0 || count > bytes.length / 18) throw new IOException("Invalid block-entity count " + count);
            var entries = new ArrayList<BlockEntityData>(count);
            for (int i = 0; i < count; i++) {
                int x = in.readInt(), y = in.readInt(), z = in.readInt();
                String type = in.readUTF();
                int length = in.readInt();
                if (length < 0 || length > in.available()) throw new IOException("Invalid NBT length " + length);
                byte[] nbt = new byte[length]; in.readFully(nbt);
                entries.add(new BlockEntityData(x, y, z, type, nbt));
            }
            if (in.read() != -1) throw new IOException("Trailing block-entity payload");
            return List.copyOf(entries);
        } catch (IOException malformed) { throw new UncheckedIOException(malformed); }
    }
}
