package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import ru.minecomplex.mac.replica.CompensatedEntities;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

/**
 * A horse, donkey, mule, skeleton or zombie horse ({@code AbstractHorse} with a seat animation): its stand animation is
 * advanced also while the server controls its movement. A saddled one the local player rides is driven by the ride
 * ({@link HorseState}).
 */
public class PacketEntityHorse extends PacketEntityLiving {
    /**
     * {@code AbstractHorse}'s seat animation of a horse the server moves ({@code standing}, {@code standCounter},
     * {@code standAnimation} and its previous value): taken over from the local ride when it ends, then advanced from the
     * synched standing flag (such a horse has no effective AI on this client).
     */
    boolean seatStanding;
    int seatStandCounter;
    float standAnimation, oldStandAnimation;
    /** The assignment stamp of the flags the seat animation took its standing state from. */
    long seatFlags = Long.MIN_VALUE;
    /** The client body fields the local player's ride resolved ({@code null}: not known). */
    HorseState state;

    public PacketEntityHorse(CompensatedEntities owner, int id, String type, ClientboundAddEntityPacket spawn) {
        super(owner, id, type, false, spawn);
    }

    protected PacketEntityHorse(CompensatedEntities owner, PacketEntityHorse source) {
        super(owner, source);
    }

    @Override
    public PacketEntityHorse copy(CompensatedEntities owner) {
        return copyHorse(new PacketEntityHorse(owner, this));
    }

    protected final <T extends PacketEntityHorse> T copyHorse(T copy) {
        copyLiving(copy);
        PacketEntityHorse target = copy;
        target.seatStanding = seatStanding;
        target.seatStandCounter = seatStandCounter;
        target.standAnimation = standAnimation;
        target.oldStandAnimation = oldStandAnimation;
        target.seatFlags = seatFlags;
        target.state = state == null ? null : state.copy();
        return copy;
    }

    /** Whether its tick advances the seat animation ({@code AbstractHorse}'s, not a camel's). */
    public boolean seatAnimated() {
        return true;
    }

    public HorseState state() {
        return state;
    }

    public void state(HorseState value) {
        if (value.generation != generation()) throw new IllegalArgumentException("Horse state belongs to a different client entity generation");
        state = value;
    }

    /** The seat's previous stand animation (0 before its first tick). */
    public float seatAnimation() {
        return oldStandAnimation;
    }
}
