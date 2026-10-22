package ru.minecomplex.mac.world;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

/**
 * Typed unnamed NBT used by block-entity physics; packet capture retains the original decoded payload separately.
 */
public final class BinaryNbt {
    public static final Tag EMPTY = new Tag(0, null);

    public static Tag root(byte[] bytes) {
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            Tag tag = read(in, in.readUnsignedByte(), 0);
            if (in.read() != -1) throw new IOException("Trailing NBT bytes");
            return tag;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static int length(DataInputStream in, int width) throws IOException {
        int count = in.readInt();
        if (count < 0 || (long) count * width > in.available()) throw new IOException("Invalid NBT length " + count);
        return count;
    }

    private static Tag read(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 512) throw new IOException("NBT too deep");
        Object value = switch (type) {
            case 0 -> null;
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> {
                float n = in.readFloat();
                yield n == 0.0F ? 0.0F : n;
            }
            case 6 -> {
                double n = in.readDouble();
                yield n == 0.0 ? 0.0 : n;
            }
            case 7 -> {
                byte[] data = new byte[length(in, 1)];
                in.readFully(data);
                yield data;
            }
            case 8 -> in.readUTF();
            case 9 -> {
                int child = in.readUnsignedByte(), count = length(in, 1);
                var list = new ArrayList<Tag>(count);
                for (int i = 0; i < count; i++) list.add(read(in, child, depth + 1));
                yield List.copyOf(list);
            }
            case 10 -> {
                var map = new LinkedHashMap<String, Tag>();
                for (int child; (child = in.readUnsignedByte()) != 0; ) {
                    String name = in.readUTF();
                    map.put(name, read(in, child, depth + 1));
                }
                yield Collections.unmodifiableMap(map);
            }
            case 11 -> {
                int[] data = new int[length(in, 4)];
                for (int i = 0; i < data.length; i++) data[i] = in.readInt();
                yield data;
            }
            case 12 -> {
                long[] data = new long[length(in, 8)];
                for (int i = 0; i < data.length; i++) data[i] = in.readLong();
                yield data;
            }
            default -> throw new IOException("Unknown NBT type " + type);
        };
        return new Tag(type, value);
    }

    public record Tag(int type, Object value) {
        @SuppressWarnings("unchecked")
        public Map<String, Tag> compound() {
            return type == 10 ? (Map<String, Tag>) value : Map.of();
        }

        public String string() {
            return type == 8 ? (String) value : "";
        }

        public Number number() {
            return type >= 1 && type <= 6 ? (Number) value : 0;
        }

        public int integer() {
            if (type == 5 || type == 6) {
                double value = number().doubleValue();
                int truncated = (int) value;
                return value < truncated ? truncated - 1 : truncated;
            }
            return number().intValue();
        }

        public byte byteValue() {
            return type == 5 || type == 6 ? (byte) (integer() & 255) : number().byteValue();
        }
    }
}
