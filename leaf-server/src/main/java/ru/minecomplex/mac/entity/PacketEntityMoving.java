package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.replica.CompensatedEntities;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

/**
 * An entity the client moves by its handlers and interpolation, simulated exactly ({@link EntityMotion}): boats, rafts,
 * happy ghasts and the living types the client constructs. One the local player drives is the ride's: while it owns
 * the entity, its transform is what the ride gave ({@code CompensatedEntities#ride}).
 */
public abstract class PacketEntityMoving extends PacketEntityFollowed {
    /** The exact transform. */
    EntityMotion motion;
    /** The local player controls it: its transform is the ride's. */
    boolean owned;
    /** A teleport reached it while owned: its interpolation then follows the ride's movement (not modelled). */
    boolean ownedTeleport;
    /** The ride gave its transform on the last owned tick. */
    boolean ownedKnown;
    /** ... and the interpolation it followed, so a pending one is known when the ownership ends. */
    boolean ownedInterpolationKnown;
    /** ... and they were the client's, not the ride's estimate. */
    boolean ownedExact = true;
    /** Its client delta movement: what the client starts from when the local player begins to drive it. */
    Vec3d velocity = Vec3d.ZERO;
    /** {@link #velocity} follows the client's ({@code false}: a change this model does not follow). */
    boolean velocityKnown;
    /** A {@code set_entity_motion} reached it while the local player drives it: the ride applies it. */
    Vec3d rideVelocity;
    /** The local player is its first passenger and controls it or may, as of its last tick. */
    boolean drivable;
    /** Drivable: its client transform and interpolation before this tick's step ({@code null}: not known). */
    EntityMotion rideStart;
    /** Pre-tick transform estimate even when a handler/controller phase lacks a certificate. */
    EntityMotion rideStartEstimate;
    /** Drivable: its client delta movement before this tick's step ({@code null}: not known). */
    Vec3d rideStartVelocity;
    /** Pre-tick estimate retained independently of its velocity certificate. */
    Vec3d rideStartVelocityEstimate;
    /** The {@link #rideStart} transform is this model's estimate, possibly a step behind the client's. */
    boolean rideStartPhaseUncertain;

    protected PacketEntityMoving(CompensatedEntities owner, int id, String type, boolean player,
                                 ClientboundAddEntityPacket spawn, Object generation) {
        super(owner, id, type, player, spawn, generation);
    }

    /** Copies the moving state into a copy of the same generation. */
    protected final <T extends PacketEntityMoving> T copyMoving(T copy) {
        copyFollowed(copy);
        PacketEntityMoving target = copy;
        target.motion = motion == null ? null : motion.copy();
        target.owned = owned;
        target.ownedTeleport = ownedTeleport;
        target.ownedKnown = ownedKnown;
        target.ownedInterpolationKnown = ownedInterpolationKnown;
        target.ownedExact = ownedExact;
        target.velocity = velocity;
        target.velocityKnown = velocityKnown;
        target.rideVelocity = rideVelocity;
        target.drivable = drivable;
        target.rideStart = rideStart == null ? null : rideStart.copy();
        target.rideStartEstimate = rideStartEstimate == null ? null : rideStartEstimate.copy();
        target.rideStartVelocity = rideStartVelocity;
        target.rideStartVelocityEstimate = rideStartVelocityEstimate;
        target.rideStartPhaseUncertain = rideStartPhaseUncertain;
        return copy;
    }

    public EntityMotion motion() {
        return motion;
    }

    @Override
    public Vec3d position() {
        return motion.body.position;
    }

    @Override
    public boolean hasBody() {
        return true;
    }

    @Override
    public Aabb box() {
        return motion.box();
    }

    public boolean owned() {
        return owned;
    }
}
