package ru.minecomplex.mac.entity.attribute;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;

/**
 * One attribute of {@code update_attributes} as the client applies it: registry key (e.g.
 * {@code minecraft:movement_speed}), base value and modifiers in wire order.
 */
public record AttributeSnapshot(String attribute, double base, List<Modifier> modifiers) {
    public AttributeSnapshot {
        modifiers = List.copyOf(modifiers);
    }

    /**
     * @param id        modifier identifier, e.g. {@code minecraft:sprinting}
     * @param operation vanilla {@code AttributeModifier.Operation} id: 0 add value, 1 add multiplied base,
     *                  2 add multiplied total
     */
    public record Modifier(String id, double amount, int operation) {
    }

    /** A native 26.3 property: its constants are declared in vanilla id order (add value, multiplied base, multiplied total). */
    public static AttributeSnapshot of(ClientboundUpdateAttributesPacket.AttributeSnapshot property) {
        var modifiers = new ArrayList<Modifier>();
        for (var modifier : property.modifiers()) {
            modifiers.add(new Modifier(modifier.id().toString(), modifier.amount(), modifier.operation().ordinal()));
        }
        return new AttributeSnapshot(property.attribute().unwrapKey().orElseThrow().identifier().toString(), property.base(), modifiers);
    }

    /** Every property of a packet, in wire order. */
    public static List<AttributeSnapshot> of(ClientboundUpdateAttributesPacket packet) {
        var snapshots = new ArrayList<AttributeSnapshot>();
        for (var property : packet.getValues()) snapshots.add(of(property));
        return snapshots;
    }
}
