package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.replica.CompensatedEntities;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

/**
 * An entity whose client state the local player's movement depends on: it may be collided with or push the player.
 * The client ticks it in its place in the level's tick list ({@code ClientLevel.tickEntities}); it is in a section of
 * the entity storage ({@code TransientEntitySectionManager}) that collision and push queries visit.
 *
 * <p>Subclasses by what the client does with it: {@link PacketEntityPositions} keeps only the positions its packets
 * named (a reach for the gaps it leaves), {@link PacketEntityShulker} is placed at once, {@link PacketEntityMoving}
 * moves by its handlers and interpolation and is simulated exactly.
 */
public abstract class PacketEntityFollowed extends PacketEntity {
    /**
     * How an entity is followed: boats/rafts, happy ghasts and other living entities ({@code LIVING}) are simulated, a
     * shulker and the rest that may be pushable ({@code PUSHABLE}: minecarts, living types the client cannot construct
     * here, the ender dragon) keep their packet positions.
     */
    public enum Kind { BOAT, HAPPY_GHAST, LIVING, SHULKER, PUSHABLE }

    /** A player entity ticks wherever it is. */
    final boolean alwaysTicking;
    boolean ticking;
    long order;
    long sectionKey, sectionOrder;
    /** The exact transform is not known: a handling or tick this model does not follow. */
    boolean unknown;
    /**
     * A move the client may have handled a tick before this model ({@code CompensatedEntities#phaseUncertain}): its
     * interpolation may be a step ahead until it ends.
     */
    boolean phaseUncertain;
    /**
     * Its transform is a ride's estimate (the ride was not exact): not the client's until a move from the server set a
     * target both interpolate to and this model's interpolation ended there.
     */
    boolean estimate;
    /** ... and such a move came since. */
    boolean estimateSynced;
    /** It ticked at least once (a player entity has looked its {@code PlayerInfo} up). */
    boolean ticked;
    /**
     * A passenger its vehicle placed this or an earlier tick ({@code rideTick} → {@code positionRider} of a followed boat,
     * raft or happy ghast): its transform is known though it is a passenger.
     */
    boolean seated;

    protected PacketEntityFollowed(CompensatedEntities owner, int id, String type, boolean player,
                                   ClientboundAddEntityPacket spawn, Object generation) {
        super(owner, id, type, player, spawn, generation);
        this.alwaysTicking = player;
    }

    /** Copies the followed state into a copy of the same generation. */
    protected final <T extends PacketEntityFollowed> T copyFollowed(T copy) {
        copyInto(copy);
        PacketEntityFollowed target = copy;
        target.ticking = ticking;
        target.order = order;
        target.sectionKey = sectionKey;
        target.sectionOrder = sectionOrder;
        target.unknown = unknown;
        target.phaseUncertain = phaseUncertain;
        target.estimate = estimate;
        target.estimateSynced = estimateSynced;
        target.ticked = ticked;
        target.seated = seated;
        return copy;
    }

    public abstract Kind kind();

    /** Where it is: its simulated transform, or the last position its packets named. */
    public abstract Vec3d position();

    /** Whether its transform is simulated (a boat, a living entity, a shulker). */
    public boolean hasBody() {
        return false;
    }

    /** {@code getBoundingBox()} of a simulated entity. */
    public Aabb box() {
        throw new IllegalStateException(this + " is not simulated");
    }

    public boolean ticking() {
        return ticking;
    }

    public boolean unknown() {
        return unknown;
    }

    public boolean phaseUncertain() {
        return phaseUncertain;
    }

    public boolean estimate() {
        return estimate;
    }
}
