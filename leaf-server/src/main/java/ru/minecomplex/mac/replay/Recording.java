package ru.minecomplex.mac.replay;

import java.util.Arrays;
import java.util.List;

/**
 * A recorded session: every input of the session in the order it received them, with monotonic timestamps. Inputs are
 * the packets as native 26.3 delivered them (their bytes) and the transport's marker allocations; a replay feeds the
 * bytes through native 26.3 again ({@link Replayer}).
 *
 * @param description free text (player, server, build) for humans
 */
public record Recording(String description, List<Entry> entries) {

    /**
     * The connection facts the session was recorded with: {@code player=} and {@code uuid=} of the description (written by
     * the plugin), unknown when absent (synthetic recordings).
     */
    public ru.minecomplex.mac.player.connection.PlayerConnectionContext facts() {
        String name = null;
        java.util.UUID uuid = null;
        for (String part : description.split("\\s+")) {
            if (part.startsWith("player=")) name = part.substring(7);
            else if (part.startsWith("uuid=")) {
                try { uuid = java.util.UUID.fromString(part.substring(5)); } catch (IllegalArgumentException ignored) { }
            }
        }
        return new ru.minecomplex.mac.player.connection.PlayerConnectionContext(name, uuid);
    }

    public sealed interface Entry {
        long nanos();
    }

    /** Where a packet was taken from the connection. */
    public enum Stage {
        /** A native serverbound play packet. */
        SERVERBOUND,
        /** A native clientbound play packet as it goes on the wire. */
        CLIENTBOUND,
        /** A native clientbound configuration packet the session reads ({@code update_tags}). */
        CONFIGURATION,
        /**
         * A native clientbound configuration packet native 26.3 builds the connection's registries from
         * ({@code registry_data}, {@code finish_configuration}).
         */
        REGISTRY
    }

    /**
     * A packet's bytes after its id.
     *
     * @param packetId the packet id in the native 26.3 protocol of its stage
     */
    public record Packet(long nanos, Stage stage, int packetId, byte[] payload) implements Entry {

        @Override
        public boolean equals(Object other) {
            return other instanceof Packet packet && nanos == packet.nanos && stage == packet.stage
                    && packetId == packet.packetId && Arrays.equals(payload, packet.payload);
        }

        @Override
        public int hashCode() {
            return (Long.hashCode(nanos) * 31 + stage.hashCode()) * 31 + packetId * 17 + Arrays.hashCode(payload);
        }

        @Override
        public String toString() {
            return "Packet[" + stage + " #" + packetId + " " + payload.length + " bytes at " + nanos + "]";
        }
    }

    /** The transport reserved marker {@code id} before writing it. */
    public record MarkerAllocated(long nanos, int id) implements Entry {
    }
}
