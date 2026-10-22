package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import ru.minecomplex.mac.replica.CompensatedEntities;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

/**
 * A living mount with a whole-tick engine other than the horse family: pig, strider, happy ghast, nautilus and zombie
 * nautilus ({@code OtherVehicleRiding}). One the local player drives keeps the ride's state ({@link LivingVehicleState}).
 */
public abstract class PacketEntityLivingMount extends PacketEntityLiving {
    /** The client body fields the local player's ride resolved ({@code null}: not known). */
    LivingVehicleState state;

    protected PacketEntityLivingMount(CompensatedEntities owner, int id, String type, ClientboundAddEntityPacket spawn) {
        super(owner, id, type, false, spawn);
    }

    protected PacketEntityLivingMount(CompensatedEntities owner, PacketEntityLivingMount source) {
        super(owner, source);
    }

    protected final <T extends PacketEntityLivingMount> T copyMount(T copy) {
        copyLiving(copy);
        ((PacketEntityLivingMount) copy).state = state == null ? null : state.copy();
        return copy;
    }

    public LivingVehicleState state() {
        return state;
    }

    public void state(LivingVehicleState value) {
        if (value.generation != generation()) throw new IllegalArgumentException("Vehicle state belongs to a different client entity generation");
        state = value;
    }
}
