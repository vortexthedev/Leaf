package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import ru.minecomplex.mac.replica.CompensatedEntities;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

/**
 * A happy ghast: collidable from above, its passengers sit at its clamped passenger points, a player wearing it a
 * harness on its body slot drives it ({@code HappyGhast.getControllingPassenger}).
 */
public final class PacketEntityHappyGhast extends PacketEntityLivingMount {
    public PacketEntityHappyGhast(CompensatedEntities owner, int id, String type, ClientboundAddEntityPacket spawn) {
        super(owner, id, type, spawn);
    }

    private PacketEntityHappyGhast(CompensatedEntities owner, PacketEntityHappyGhast source) {
        super(owner, source);
    }

    @Override
    public PacketEntityHappyGhast copy(CompensatedEntities owner) {
        return copyMount(new PacketEntityHappyGhast(owner, this));
    }

    @Override
    public Kind kind() {
        return Kind.HAPPY_GHAST;
    }
}
