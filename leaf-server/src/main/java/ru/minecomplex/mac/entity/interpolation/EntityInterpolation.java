package ru.minecomplex.mac.entity.interpolation;

import java.util.List;
import java.util.function.Predicate;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * Native 26.3 interpolation: living entities use SteppedInterpolationHandler with their type's update interval;
 * boats use linear InterpolationHandler. The local player also has an interpolation handler for entity teleports.
 */
public final class EntityInterpolation {

    /**
     * {@code PositionMoveRotation.of(entity)}'s position and rotation.
     */
    public record Values(Vec3d position, float yRot, float xRot) {
    }

    private final boolean living;

    private LinearEntityInterpolation linear = new LinearEntityInterpolation();

    private SteppedEntityInterpolation stepped;

    /**
     * @param living         a {@code LivingEntity} (26.3 interpolates it stepwise), else a boat
     * @param updateInterval its type's {@code updateInterval()}
     */
    public EntityInterpolation(boolean living, int updateInterval) {
        this.living = living;
        stepped = new SteppedEntityInterpolation(updateInterval);
    }

    public EntityInterpolation copy() {
        var copy = new EntityInterpolation(living, 0);
        copy.linear = linear.copy();
        copy.stepped = stepped.copy();
        return copy;
    }

    /**
     * A non-living entity's handler (a boat's) with the given linear state.
     */
    public static EntityInterpolation linear(LinearEntityInterpolation source) {
        var handler = new EntityInterpolation(false, 0);
        handler.linear = source.copy();
        return handler;
    }

    /**
     * This non-living entity's linear state as a boat's interpolation.
     */
    public BoatInterpolation boatCopy() {
        if (living)
            throw new IllegalStateException("A living entity's interpolation is not a boat's");
        var copy = new BoatInterpolation();
        linear.copyTo(copy);
        return copy;
    }

    /**
     * Whether it is a 26.3 stepped handler.
     */
    public boolean stepwise() {
        return living;
    }

    /**
     * {@code isInterpolating()}.
     */
    public boolean active() {
        return stepwise() ? stepped.active() : linear.active();
    }

    /**
     * The interpolation target, or the body's transform when not interpolating.
     */
    public Vec3d targetPosition(PlayerState body) {
        return stepwise() ? stepped.targetPosition(body) : linear.active() ? linear.target().position() : body.position;
    }

    public float targetYaw(PlayerState body) {
        return stepwise() ? stepped.targetYaw(body) : linear.active() ? linear.target().yaw() : body.yRot;
    }

    public float targetPitch(PlayerState body) {
        return stepwise() ? stepped.targetPitch(body) : linear.active() ? linear.target().pitch() : body.xRot;
    }

    /** The position and rotation of getClientPositionAndRotation, including the current interpolation target. */
    public Values clientValues(PlayerState body) {
        return new Values(targetPosition(body), targetYaw(body), targetPitch(body));
    }

    /**
     * {@code lerpTo(…, 3)}/{@code moveOrInterpolateTo} with every part given ({@code steps}: a 26.3 stepped path).
     */
    public void interpolateTo(PlayerState body, Vec3d position, List<SteppedEntityInterpolation.PathStep> steps, float yRot, float xRot) {
        if (stepwise())
            stepped.interpolateTo(body, position, steps, yRot, xRot, true);
        else
            linear.receive(body, position, yRot, xRot);
    }

    /**
     * 26.3 {@code moveOrInterpolateTo(path, yRot, xRot, hasRotation)}: a missing part comes from the current target.
     */
    public void interpolateTo(PlayerState body, Vec3d end, List<SteppedEntityInterpolation.PathStep> steps, float yRot, float xRot, boolean hasRotation) {
        if (!stepwise())
            throw new IllegalStateException("Partial interpolation targets are a stepped handler's");
        stepped.interpolateTo(body, end, steps, yRot, xRot, hasRotation);
    }

    /**
     * One step: {@code getInterpolation().interpolate()} (1.21.4 {@code lerpPositionAndRotationStep}). An inactive linear
     * handler does nothing; a stepped one resets.
     *
     * @param collisionFree {@code level.noCollision(entity, box)} for a target adjusted by the entity's own displacement
     * @return whether a free check could change the interpolation target (a 26.3 stepped adjustment only checks a real
     *         displacement)
     */
    public boolean tick(PlayerState body, Predicate<Aabb> collisionFree) {
        if (stepwise()) {
            boolean[] asked = new boolean[1];
            stepped.tick(body, box -> {
                asked[0] = true;
                return collisionFree.test(box);
            });
            return asked[0];
        }
        linear.tick(body, collisionFree);
        return linear.queryMattered();
    }
}
