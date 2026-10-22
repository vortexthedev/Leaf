package ru.minecomplex.mac.entity;

import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;
import ru.minecomplex.mac.packet.Packets;
import net.minecraft.world.entity.Relative;
import net.minecraft.network.protocol.game.VecDelta.Linear;
import net.minecraft.network.protocol.game.VecDelta.Stepped;
import net.minecraft.network.protocol.game.VecDelta;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import java.util.List;
import java.util.function.Predicate;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.entity.interpolation.EntityInterpolation;
import ru.minecomplex.mac.entity.interpolation.SteppedEntityInterpolation;
import ru.minecomplex.mac.mechanics.movement.RotationUpdate;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * Where a client keeps another entity (a boat, a raft, a happy ghast): its transform, the {@code VecDeltaCodec} base that
 * relative moves decode against, and its interpolation, driven by the client's handlers of {@code move_entity_*},
 * {@code teleport_entity} and {@code entity_position_sync} ({@code ClientPacketListener}) and advanced once per client
 * tick. Such an entity moves on the client only by interpolation: a remote boat zeroes its velocity, a remote mob does
 * not travel; its velocity is therefore not kept.
 *
 * <p>Versions: 1.21.4 lerps ({@code lerpTo}: the boat's three-step lerp) and fills a partial packet from its lerp target;
 * 1.21.5–1.21.8 interpolate ({@code InterpolationHandler}) and fill from the current transform; 1.21.9 on fill from the
 * interpolation target ({@code MOVE_PACKET_FILLS_FROM_TARGET}); 26.3 decodes position paths and interpolates
 * living entities stepwise ({@link SteppedEntityInterpolation}).
 */
public final class EntityMotion {

    private final boolean living;

    public final PlayerState body = new PlayerState();

    /**
     * {@code getPositionCodec().getBase()}.
     */
    public Vec3d codecBase = Vec3d.ZERO;

    public EntityInterpolation interpolation;

    /**
     * @param living         a {@code LivingEntity} (26.3 interpolates it stepwise), else a boat
     * @param updateInterval its type's {@code updateInterval()}
     */
    public EntityMotion(boolean living, int updateInterval, float width, float height) {
        this.living = living;
        body.fixedBodyDimensions = true;
        body.bodyWidth = width;
        body.bodyHeight = height;
        interpolation = new EntityInterpolation(living, updateInterval);
    }

    public EntityMotion copy() {
        var copy = new EntityMotion(living, 0, body.bodyWidth, body.bodyHeight);
        copy.body.position = body.position;
        copy.body.oldPosition = body.oldPosition;
        copy.body.yRot = body.yRot;
        copy.body.xRot = body.xRot;
        copy.body.onGround = body.onGround;
        copy.codecBase = codecBase;
        copy.interpolation = interpolation.copy();
        return copy;
    }

    private boolean stepwise() {
        return interpolation.stepwise();
    }

    public boolean interpolating() {
        return interpolation.active();
    }

    /**
     * {@code Entity.recreateFromPacket}: the codec base and the transform ({@code snapTo}).
     */
    public void spawn(double x, double y, double z, float yRot, float xRot) {
        codecBase = new Vec3d(x, y, z);
        SteppedEntityInterpolation.snap(body, new Vec3d(x, y, z), yRot, xRot);
    }

    /**
     * {@code getPositionCodec().decode(xa, ya, za)}.
     */
    private Vec3d decode(Vec3d base, long xa, long ya, long za) {
        if (xa == 0L && ya == 0L && za == 0L)
            return base;
        double x = xa == 0L ? base.x() : (Math.round(base.x() * 4096.0) + xa) / 4096.0;
        double y = ya == 0L ? base.y() : (Math.round(base.y() * 4096.0) + ya) / 4096.0;
        double z = za == 0L ? base.z() : (Math.round(base.z() * 4096.0) + za) / 4096.0;
        return new Vec3d(x, y, z);
    }

    /**
     * {@code Mth.unpackDegrees}: a packed angle as the packet getters return it.
     */
    public static float unpack(byte packed) {
        return packed * 360 / 256.0F;
    }

    /**
     * The interpolation target, or the transform when not interpolating.
     */
    public Vec3d target() {
        return interpolation.targetPosition(body);
    }

    private Vec3d targetPosition() {
        return interpolation.targetPosition(body);
    }

    private float targetYaw() {
        return interpolation.targetYaw(body);
    }

    private float targetPitch() {
        return interpolation.targetPitch(body);
    }

    /**
     * {@code lerpTo}/{@code moveOrInterpolateTo} with every part given.
     */
    private void interpolateTo(Vec3d position, List<SteppedEntityInterpolation.PathStep> steps, float yRot, float xRot) {
        interpolation.interpolateTo(body, position, steps, yRot, xRot);
    }

    /**
     * {@code handleMoveEntity} of {@code move_entity_pos}/{@code _pos_rot}/{@code _rot} ({@link Packets#delta}: the
     * position delta, {@code null} for a rotation only).
     */
    public void move(Object packet, boolean controlledLocally) {
        VecDelta delta = Packets.delta(packet);
        boolean hasPosition = delta != null, hasRotation = Packets.rotates(packet);
        if (controlledLocally) {
            // the client's own vehicle: only the codec follows (26.3 decodes a position packet only)
            if (hasPosition)
                codecBase = path(delta).end;
            return;
        }
        float yRot = unpack(Packets.packedYaw(packet)), xRot = unpack(Packets.packedPitch(packet));
        if (hasPosition) {
            var path = path(delta);
            codecBase = path.end;
            if (hasRotation) {
                interpolateTo(path.end, path.steps, yRot, xRot);
            } else {
                if (stepwise())
                    interpolation.interpolateTo(body, path.end, path.steps, 0.0F, 0.0F, false);
                else
                    interpolateTo(path.end, null, targetYaw(), targetPitch());
            }
        } else if (hasRotation) {
            {
                if (stepwise())
                    interpolation.interpolateTo(body, null, null, yRot, xRot, true);
                else
                    interpolateTo(targetPosition(), null, yRot, xRot);
            }
        }
        body.onGround = Packets.onGround(packet);
    }

    private record Path(Vec3d end, List<SteppedEntityInterpolation.PathStep> steps) {
    }

    /**
     * The decoded position: linear, or a 26.3 stepped path decoded step by step from the codec base. A rotation-only
     * packet ({@code null}) decodes to the base itself.
     */
    private Path path(VecDelta delta) {
        if (delta == null)
            return new Path(codecBase, null);
        if (delta instanceof VecDelta.Linear linear) {
            return new Path(decode(codecBase, linear.xa(), linear.ya(), linear.za()), null);
        }
        var stepped = ((VecDelta.Stepped) delta).steps();
        var steps = new java.util.ArrayList<SteppedEntityInterpolation.PathStep>(stepped.size());
        Vec3d base = codecBase;
        for (var step : stepped) {
            Vec3d position = decode(base, step.xa(), step.ya(), step.za());
            steps.add(new SteppedEntityInterpolation.PathStep(position, step.ticks()));
            base = position;
        }
        return new Path(steps.getLast().position(), steps);
    }

    /**
     * {@code handleTeleportEntity} with {@code setValuesFromPositionPacket}.
     */
    public void teleport(ClientboundTeleportEntityPacket packet, boolean controlledLocally, boolean ticking) {
        int relatives = net.minecraft.world.entity.Relative.pack(packet.relatives());
        var values = packet.change();
        Vec3d target = new Vec3d(values.position().x(), values.position().y(), values.position().z());
        boolean relativeX = ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.X), relativeY = ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.Y), relativeZ = ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.Z);
        boolean relativePosition = relativeX || relativeY || relativeZ;
        boolean interpolate = ticking || !controlledLocally || relativePosition;
        // the current values are the interpolation target while interpolating (PositionMoveRotation.of, 26.3
        var clientValues = interpolation.clientValues(body);
        Vec3d current = clientValues.position();
        float currentYaw = clientValues.yRot(), currentPitch = clientValues.xRot();
        Vec3d position = new Vec3d((relativeX ? current.x() : 0.0) + target.x(), (relativeY ? current.y() : 0.0) + target.y(), (relativeZ ? current.z() : 0.0) + target.z());
        float yRot = RotationUpdate.absoluteYRot(currentYaw, values.yRot(), ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.Y_ROT));
        float xRot = RotationUpdate.absoluteXRot(currentPitch, values.xRot(), ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.X_ROT));
        if (interpolate && !(current.distanceToSqr(position) > 4096.0)) {
            interpolateTo(position, null, yRot, xRot);
        } else {
            body.position = VanillaMath.position(body.position, position);
            body.yRot = RotationUpdate.storeYRot(body.yRot, yRot);
            body.xRot = RotationUpdate.storeXRot(body.xRot, xRot);
            body.oldPosition = new Vec3d((relativeX ? body.oldPosition.x() : 0.0) + target.x(), (relativeY ? body.oldPosition.y() : 0.0) + target.y(), (relativeZ ? body.oldPosition.z() : 0.0) + target.z());
        }
        body.onGround = packet.onGround();
    }

    /**
     * {@code handleEntityPositionSync}.
     */
    public void sync(ClientboundEntityPositionSyncPacket packet, boolean controlledLocally, boolean ticking) {
        var path = packet.position();
        var endPosition = path.endPosition();
        Vec3d end = new Vec3d(endPosition.x(), endPosition.y(), endPosition.z());
        codecBase = end;
        if (controlledLocally)
            return;
        var steps = SteppedEntityInterpolation.steps(path);
        if (ticking && !(body.position.distanceToSqr(end) > 4096.0)) {
            interpolateTo(end, steps, packet.yRot(), packet.xRot());
        } else {
            SteppedEntityInterpolation.snap(body, end, packet.yRot(), packet.xRot());
        }
        body.onGround = packet.onGround();
    }

    /**
     * One client tick of the entity: the old position, then its interpolation step.
     *
     * @return whether a free check could change the interpolation target (a 26.3 stepped adjustment only checks a real
     *         displacement)
     */
    public boolean tick(Predicate<Aabb> collisionFree) {
        return tick(collisionFree, null, null);
    }

    /**
     * {@link #tick(Predicate)} with what the entity's own tick does around the interpolation step: {@code before} between
     * the old position and the step (a living entity's {@code baseTick} while the step is in {@code aiStep}), {@code after}
     * after it (the {@code baseTick} that follows the 26.3 {@code commonTick} step).
     */
    public boolean tick(Predicate<Aabb> collisionFree, Runnable before, Runnable after) {
        body.oldPosition = body.position;
        if (before != null)
            before.run();
        boolean asked = interpolation.tick(body, collisionFree);
        if (after != null)
            after.run();
        return asked;
    }

    /**
     * The entity's box.
     */
    public Aabb box() {
        return body.boundingBox();
    }
}
