package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import ru.minecomplex.mac.replica.CompensatedEntities;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

/** A boat or raft ({@code AbstractBoat}), chest variants included. */
public final class PacketEntityBoat extends PacketEntityMoving {
    /** The client body fields the local player's ride resolved ({@code null}: not known). */
    BoatState state;

    public PacketEntityBoat(CompensatedEntities owner, int id, String type, ClientboundAddEntityPacket spawn) {
        super(owner, id, type, false, spawn, new Object());
    }

    private PacketEntityBoat(CompensatedEntities owner, PacketEntityBoat source) {
        super(owner, source.id(), source.type(), false, source.spawn(), source.generation());
    }

    @Override
    public PacketEntityBoat copy(CompensatedEntities owner) {
        var copy = copyMoving(new PacketEntityBoat(owner, this));
        copy.state = state == null ? null : state.copy();
        return copy;
    }

    @Override
    public Kind kind() {
        return Kind.BOAT;
    }

    /** A raft ({@code Raft}): its passenger seat is higher. */
    public boolean raft() {
        return type().endsWith("_raft");
    }

    /** A chest boat or raft: its single passenger sits 0.15 forward. */
    public boolean chest() {
        return type().contains("_chest_");
    }

    public BoatState state() {
        return state;
    }

    /** Install fully resolved client body fields; a spawn transform alone cannot certify hidden tick state. */
    public void state(BoatState value) {
        if (value.generation != generation()) throw new IllegalArgumentException("Boat state belongs to a different client entity generation");
        state = value;
    }
}
