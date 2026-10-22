package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import ru.minecomplex.mac.engine.movementtick.OtherVehicleRiding;
import ru.minecomplex.mac.replica.CompensatedEntities;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

/** A strider: steered like a pig, walking on lava. */
public final class PacketEntityStrider extends PacketEntityRideable {
    public PacketEntityStrider(CompensatedEntities owner, int id, String type, ClientboundAddEntityPacket spawn) {
        super(owner, id, type, spawn);
    }

    private PacketEntityStrider(CompensatedEntities owner, PacketEntityStrider source) {
        super(owner, source);
    }

    @Override
    public PacketEntityStrider copy(CompensatedEntities owner) {
        return copyRideable(new PacketEntityStrider(owner, this));
    }

    @Override
    public OtherVehicleRiding.Kind steeringKind() {
        return OtherVehicleRiding.Kind.STRIDER;
    }

    @Override
    public String dataPrefix() {
        return "Strider.";
    }
}
