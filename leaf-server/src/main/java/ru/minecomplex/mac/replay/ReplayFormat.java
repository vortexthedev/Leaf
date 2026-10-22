package ru.minecomplex.mac.replay;


import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import ru.minecomplex.mac.packet.NativeRuntime;

/**
 * Binary recording format, version 2 (the packets' own bytes):
 * <pre>
 * "MACR" u16 version, i32 protocol (always 777), UTF description
 * repeated: u8 tag
 *   TAG_PACKET: i64 nanos, u8 stage ({@link Recording.Stage} ordinal), i32 protocol of the bytes (777), i32 packet id,
 *               u8 compressed, i32 length, bytes (deflated when compressed, of the original length)
 *   TAG_MARKER: i64 nanos, i32 marker id (allocation by the transport)
 * </pre>
 * Version 1 recordings (records of the former packet schema) are not read.
 */
public final class ReplayFormat {
    private ReplayFormat() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    private static final int MAGIC = 0x4D414352; // "MACR"
    private static final short VERSION = 2;
    private static final byte TAG_PACKET = 4;
    private static final byte TAG_MARKER = 3;
    /** Payloads from this size on are deflated (chunks, light, registries). */
    private static final int COMPRESS_FROM = 256;

    /** Streaming writer; not thread-safe. */
    public static final class Writer implements AutoCloseable {
        private final DataOutputStream out;
        private final Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        private final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        private final byte[] chunk = new byte[8192];

        public Writer(OutputStream stream, String description) throws IOException {
            this.out = new DataOutputStream(stream);
            out.writeInt(MAGIC);
            out.writeShort(VERSION);
            out.writeInt(NativeRuntime.PROTOCOL);
            out.writeUTF(description);
        }

        public void packet(Recording.Packet packet) throws IOException {
            out.writeByte(TAG_PACKET);
            out.writeLong(packet.nanos());
            out.writeByte(packet.stage().ordinal());
            out.writeInt(NativeRuntime.PROTOCOL);
            out.writeInt(packet.packetId());
            byte[] payload = packet.payload();
            if (payload.length < COMPRESS_FROM) {
                out.writeByte(0);
                out.writeInt(payload.length);
                out.write(payload);
                return;
            }
            deflater.reset();
            deflater.setInput(payload);
            deflater.finish();
            compressed.reset();
            while (!deflater.finished()) compressed.write(chunk, 0, deflater.deflate(chunk));
            out.writeByte(1);
            out.writeInt(payload.length);
            out.writeInt(compressed.size());
            compressed.writeTo(out);
        }

        public void markerAllocated(long nanos, int id) throws IOException {
            out.writeByte(TAG_MARKER);
            out.writeLong(nanos);
            out.writeInt(id);
        }

        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            deflater.end();
            out.close();
        }
    }

    public static void write(Recording recording, OutputStream stream) throws IOException {
        Writer writer = new Writer(stream, recording.description());
        for (Recording.Entry entry : recording.entries()) {
            switch (entry) {
                case Recording.Packet packet -> writer.packet(packet);
                case Recording.MarkerAllocated allocated -> writer.markerAllocated(allocated.nanos(), allocated.id());
            }
        }
        writer.flush();
    }

    public static Recording read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != MAGIC) {
            throw new IOException("Not a MAC recording");
        }
        short version = in.readShort();
        if (version != VERSION) {
            throw new IOException("Unsupported recording version " + version + " (version 1 recordings are no longer read)");
        }
        int protocol = in.readInt();
        if (protocol != NativeRuntime.PROTOCOL) {
            throw new IOException("Unsupported recording protocol " + protocol);
        }
        String description = in.readUTF();
        Recording.Stage[] stages = Recording.Stage.values();
        List<Recording.Entry> entries = new ArrayList<>();
        Inflater inflater = new Inflater();
        try {
            while (true) {
                int tag;
                try {
                    tag = in.readByte();
                } catch (EOFException end) {
                    break;
                }
                switch (tag) {
                    case TAG_PACKET -> {
                        long nanos = in.readLong();
                        Recording.Stage stage = stages[in.readUnsignedByte()];
                        int bytesProtocol = in.readInt();
                        if (bytesProtocol != NativeRuntime.PROTOCOL) {
                            throw new IOException("Unsupported packet protocol " + bytesProtocol);
                        }
                        int packetId = in.readInt();
                        boolean deflated = in.readUnsignedByte() != 0;
                        byte[] payload = new byte[in.readInt()];
                        if (deflated) {
                            byte[] input = new byte[in.readInt()];
                            in.readFully(input);
                            inflater.reset();
                            inflater.setInput(input);
                            try {
                                if (inflater.inflate(payload) != payload.length || !inflater.finished()) {
                                    throw new IOException("Corrupt packet payload");
                                }
                            } catch (DataFormatException corrupt) {
                                throw new IOException("Corrupt packet payload", corrupt);
                            }
                        } else {
                            in.readFully(payload);
                        }
                        entries.add(new Recording.Packet(nanos, stage, packetId, payload));
                    }
                    case TAG_MARKER -> entries.add(new Recording.MarkerAllocated(in.readLong(), in.readInt()));
                    default -> throw new IOException("Unknown tag " + tag);
                }
            }
        } finally {
            inflater.end();
        }
        return new Recording(description, List.copyOf(entries));
    }
}
