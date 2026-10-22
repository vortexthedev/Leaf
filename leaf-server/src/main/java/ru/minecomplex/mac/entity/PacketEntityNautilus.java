package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import ru.minecomplex.mac.replica.CompensatedEntities;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

/** A nautilus or zombie nautilus ({@code AbstractNautilus}): a saddled, tamed one dashes under its rider's control. */
public final class PacketEntityNautilus extends PacketEntityLivingMount {
    public PacketEntityNautilus(CompensatedEntities owner, int id, String type, ClientboundAddEntityPacket spawn) {
        super(owner, id, type, spawn);
    }

    private PacketEntityNautilus(CompensatedEntities owner, PacketEntityNautilus source) {
        super(owner, source);
    }

    @Override
    public PacketEntityNautilus copy(CompensatedEntities owner) {
        return copyMount(new PacketEntityNautilus(owner, this));
    }
}
