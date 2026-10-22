package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import ru.minecomplex.mac.engine.movementtick.OtherVehicleRiding;
import ru.minecomplex.mac.replica.CompensatedEntities;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

/**
 * A pig (and, as {@link PacketEntityStrider}, a strider): steered with an item on a stick. Its boost clock
 * ({@code ItemBasedSteering}) starts in the constructor and follows {@code DATA_BOOST_TIME}, before the first mount and
 * through dismounts.
 */
public class PacketEntityRideable extends PacketEntityLivingMount {
    /** The constructor/onSynced boost clock, kept even before the first locally controlled ride. */
    OtherVehicleRiding.State steering = new OtherVehicleRiding.State();
    boolean steeringKnown = true;

    public PacketEntityRideable(CompensatedEntities owner, int id, String type, ClientboundAddEntityPacket spawn) {
        super(owner, id, type, spawn);
    }

    protected PacketEntityRideable(CompensatedEntities owner, PacketEntityRideable source) {
        super(owner, source);
    }

    @Override
    public PacketEntityRideable copy(CompensatedEntities owner) {
        return copyRideable(new PacketEntityRideable(owner, this));
    }

    protected final <T extends PacketEntityRideable> T copyRideable(T copy) {
        copyMount(copy);
        PacketEntityRideable target = copy;
        target.steering = steering.copy();
        target.steeringKnown = steeringKnown;
        return copy;
    }

    /** Its steering kind. */
    public OtherVehicleRiding.Kind steeringKind() {
        return OtherVehicleRiding.Kind.PIG;
    }

    /** The {@code MAET} accessor prefix of its steering items ({@code Pig.}). */
    public String dataPrefix() {
        return "Pig.";
    }

    /** Copy of the steering clock ({@code null}: not known). */
    public OtherVehicleRiding.State steering() {
        return steeringKnown ? steering.copy() : null;
    }
}
