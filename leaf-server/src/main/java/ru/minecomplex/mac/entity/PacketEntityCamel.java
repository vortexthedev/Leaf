package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import ru.minecomplex.mac.replica.CompensatedEntities;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

/** A camel or camel husk: a horse-family mount with its own dash and pose clock, without the seat animation. */
public final class PacketEntityCamel extends PacketEntityHorse {
    public PacketEntityCamel(CompensatedEntities owner, int id, String type, ClientboundAddEntityPacket spawn) {
        super(owner, id, type, spawn);
    }

    private PacketEntityCamel(CompensatedEntities owner, PacketEntityCamel source) {
        super(owner, source);
    }

    @Override
    public PacketEntityCamel copy(CompensatedEntities owner) {
        return copyHorse(new PacketEntityCamel(owner, this));
    }

    @Override
    public boolean seatAnimated() {
        return false;
    }
}
