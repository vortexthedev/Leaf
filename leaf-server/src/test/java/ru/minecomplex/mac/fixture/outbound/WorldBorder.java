package ru.minecomplex.mac.fixture.outbound;

/** Decoded client border handler input. Duration uses the receiving client's wire units. */
public record WorldBorder(Operation operation, double centerX, double centerZ, double oldSize, double newSize,
                          long duration, int absoluteMax) implements OutboundEvent {
    public enum Operation { INITIALIZE, CENTER, SIZE, LERP }
}
