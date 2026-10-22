package ru.minecomplex.mac.fixture.outbound;

/** {@code entity_event}: {@code Entity.handleEntityEvent(id)}; for the own player event 9 completes the item use. */
public record EntityEvent(int entityId, int event) implements OutboundEvent {
    public static final int FINISHED_USING_ITEM = 9;
}
