package ru.minecomplex.mac.fixture.outbound;

import ru.minecomplex.mac.fixture.WireItem;

/** Native set_equipment fixture for the saddle and body slots consumed by the vehicle model. */
public record EntityEquipment(int entityId, boolean saddleSent, byte[] saddle, boolean bodySent, byte[] body) implements OutboundEvent {

    public EntityEquipment {
        saddle = saddle.clone();
        body = body.clone();
    }

    /** @param saddle the saddle slot's stack, {@code null} when the packet does not list the slot; likewise {@code body} */
    public static EntityEquipment of(int entityId, WireItem saddle, WireItem body) {
        return new EntityEquipment(entityId, saddle != null, saddle == null ? new byte[0] : WireItem.encode(saddle),
                body != null, body == null ? new byte[0] : WireItem.encode(body));
    }

    /** The saddle slot's stack, {@code null} when the packet did not list the slot. */
    public WireItem saddleStack() {
        return saddleSent ? WireItem.decodeOne(saddle) : null;
    }

    /** The body slot's stack, {@code null} when the packet did not list the slot. */
    public WireItem bodyStack() {
        return bodySent ? WireItem.decodeOne(body) : null;
    }

    @Override
    public byte[] saddle() {
        return saddle.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EntityEquipment that && entityId == that.entityId && saddleSent == that.saddleSent
                && java.util.Arrays.equals(saddle, that.saddle) && bodySent == that.bodySent && java.util.Arrays.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        return ((31 * entityId + Boolean.hashCode(saddleSent)) * 31 + java.util.Arrays.hashCode(saddle)) * 31
                + java.util.Arrays.hashCode(body);
    }
}
