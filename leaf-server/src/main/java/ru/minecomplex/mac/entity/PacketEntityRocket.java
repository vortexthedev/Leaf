package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.replica.CompensatedEntities;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

/**
 * A {@code FireworkRocketEntity}: never pushed nor collided with, only ticked in its place in the tick list. Attached to
 * the local player ({@code DATA_ATTACHED_TO_TARGET}) it pushes it while it glides.
 */
public final class PacketEntityRocket extends PacketEntity {
    Vec3d position;
    boolean ticking;
    long order;
    long sectionKey, sectionOrder;
    /** {@code attachedToEntity}: the entity its tick found for {@code DATA_ATTACHED_TO_TARGET}, -1 before. */
    int target = -1;
    /** The local player generation it found. */
    int playerGeneration;

    public PacketEntityRocket(CompensatedEntities owner, int id, String type, ClientboundAddEntityPacket spawn) {
        super(owner, id, type, false, spawn);
    }

    private PacketEntityRocket(CompensatedEntities owner, PacketEntityRocket source) {
        super(owner, source.id(), source.type(), false, source.spawn(), source.generation());
    }

    @Override
    public PacketEntityRocket copy(CompensatedEntities owner) {
        var copy = copyInto(new PacketEntityRocket(owner, this));
        copy.position = position;
        copy.ticking = ticking;
        copy.order = order;
        copy.sectionKey = sectionKey;
        copy.sectionOrder = sectionOrder;
        copy.target = target;
        copy.playerGeneration = playerGeneration;
        return copy;
    }

    public Vec3d position() {
        return position;
    }

    public boolean ticking() {
        return ticking;
    }

    /** Whether its tick has not found the entity it is attached to yet ({@code attachedToEntity} is null). */
    public boolean unresolved() {
        return target < 0;
    }
}
