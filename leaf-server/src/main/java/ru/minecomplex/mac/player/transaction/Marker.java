package ru.minecomplex.mac.player.transaction;

/**
 * One of our pings as observed on the wire.
 *
 * @param id          ping id
 * @param outboundSeq wire position among clientbound packets
 * @param sentNanos   {@link System#nanoTime()} at observation
 */
public record Marker(int id, long outboundSeq, long sentNanos) {
}
