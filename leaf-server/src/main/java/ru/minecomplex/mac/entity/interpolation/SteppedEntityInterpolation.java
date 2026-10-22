package ru.minecomplex.mac.entity.interpolation;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.engine.movementtick.BoatPassengerPhysics;
import ru.minecomplex.mac.mechanics.movement.RotationUpdate;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * 26.3 {@code SteppedInterpolationHandler} (with {@code AbstractInterpolationHandler}) of a client living entity: targets
 * become steps of {@code updateInterval} ticks (or the path's own tick offsets), consumed at a speed that catches up with
 * the remaining ticks; deltas the entity made since the last step move the whole path when the moved box is free. The
 * level's relative tick speed is taken as 1 (a client under {@code /tick rate} is not modelled).
 */
public final class SteppedEntityInterpolation {
    /** One {@code PositionStep}: a position reached {@code tickOffset} ticks after the previous one. */
    public record PathStep(Vec3d position, int tickOffset) {
    }

    private static final class Pose {
        Vec3d position = Vec3d.ZERO;
        float yRot, xRot;

        void set(Vec3d position, float yRot, float xRot) {
            this.position = position;
            this.yRot = yRot;
            this.xRot = xRot;
        }

        boolean is(Vec3d position, float yRot, float xRot) {
            return this.yRot == yRot && this.xRot == xRot && Objects.equals(this.position, position);
        }

        Pose copy() {
            var p = new Pose();
            p.set(position, yRot, xRot);
            return p;
        }
    }

    private static final class Step {
        final Pose pose = new Pose();
        final int tickOffset;

        Step(Vec3d position, float yRot, float xRot, int tickOffset) {
            pose.set(position, yRot, xRot);
            this.tickOffset = tickOffset;
        }
    }

    private final int interpolationSteps;
    private final Pose data = new Pose();
    private final Pose last = new Pose();
    private final Pose lastStep = new Pose();
    private final LinkedList<Step> remaining = new LinkedList<>();
    private float currentStepTicks, remainingTicks, speed = 1.0F;

    public SteppedEntityInterpolation(int interpolationSteps) {
        this.interpolationSteps = interpolationSteps;
    }

    public SteppedEntityInterpolation copy() {
        var copy = new SteppedEntityInterpolation(interpolationSteps);
        copy.data.set(data.position, data.yRot, data.xRot);
        copy.last.set(last.position, last.yRot, last.xRot);
        copy.lastStep.set(lastStep.position, lastStep.yRot, lastStep.xRot);
        for (Step step : remaining) copy.remaining.add(new Step(step.pose.position, step.pose.yRot, step.pose.xRot, step.tickOffset));
        copy.currentStepTicks = currentStepTicks;
        copy.remainingTicks = remainingTicks;
        copy.speed = speed;
        return copy;
    }

    public boolean active() {
        return !remaining.isEmpty();
    }

    /** The current target position, or the body's when not interpolating ({@code interpolateTo}'s fallback). */
    public Vec3d targetPosition(PlayerState body) {
        return active() ? data.position : body.position;
    }

    public float targetYaw(PlayerState body) {
        return active() ? data.yRot : body.yRot;
    }

    public float targetPitch(PlayerState body) {
        return active() ? data.xRot : body.xRot;
    }

    /**
     * {@code interpolateTo(position, yRot, xRot, hasRotation)}: a missing position or rotation comes from the current
     * target (or the body); a target equal to the current one is ignored while interpolating.
     *
     * @param end   the path's end position, {@code null} for none
     * @param steps a stepped path's steps, {@code null} for a linear one
     */
    public void interpolateTo(PlayerState body, Vec3d end, List<PathStep> steps, float yRot, float xRot, boolean hasRotation) {
        Vec3d position = end != null ? end : targetPosition(body);
        float y = hasRotation ? yRot : targetYaw(body);
        float x = hasRotation ? xRot : targetPitch(body);
        if (end == null) steps = null;
        if (interpolationSteps == 0) {
            snap(body, position, y, x);
            cancel();
        } else if (!active() || !data.is(position, y, x)) {
            start(body, position, steps, y, x);
            last.set(body.position, body.yRot, body.xRot);
        }
    }

    private void start(PlayerState body, Vec3d end, List<PathStep> steps, float yRot, float xRot) {
        if (!active()) {
            lastStep.set(body.position, body.yRot, body.xRot);
            currentStepTicks = 1.0F;
        }
        if (Objects.equals(end, data.position)) {
            addStep(end, yRot, xRot, interpolationSteps);
        } else if (steps == null) {
            addStep(end, yRot, xRot, interpolationSteps);
        } else if (yRot == data.yRot && xRot == data.xRot) {
            for (PathStep step : steps) addStep(step.position(), yRot, xRot, step.tickOffset());
        } else {
            int total = 0;
            for (PathStep step : steps) total += step.tickOffset();
            int offset = 0;
            for (PathStep step : steps) {
                offset += step.tickOffset();
                float a = (float) offset / total;
                addStep(step.position(), rotLerp(a, data.yRot, yRot), VanillaMath.lerp(a, data.xRot, xRot), step.tickOffset());
            }
        }
        data.set(end, yRot, xRot);
    }

    private void addStep(Vec3d position, float yRot, float xRot, int ticks) {
        remaining.add(new Step(position, yRot, xRot, ticks));
        remainingTicks += ticks;
    }

    /** {@code interpolate()}: one client tick. */
    public void tick(PlayerState body, Predicate<Aabb> collisionFree) {
        if (!active()) {
            cancel();
            return;
        }
        Vec3d delta = body.position.subtract(last.position);
        if (delta.lengthSqr() > 1.0E-5F) {
            Vec3d adjusted = data.position.add(delta);
            if (collisionFree.test(body.makeBoundingBox(adjusted))) {
                data.position = data.position.add(delta);
                for (Step step : remaining) step.pose.position = step.pose.position.add(delta);
                lastStep.position = lastStep.position.add(delta);
            }
        }
        float dy = body.yRot - last.yRot, dx = body.xRot - last.xRot;
        data.yRot += dy;
        data.xRot += dx;
        for (Step step : remaining) {
            step.pose.yRot += dy;
            step.pose.xRot += dx;
        }
        lastStep.yRot += dy;
        lastStep.xRot += dx;
        Pose target = next();
        body.position = VanillaMath.position(body.position, target.position);
        body.yRot = RotationUpdate.storeYRot(body.yRot, target.yRot % 360.0F);
        body.xRot = RotationUpdate.storeXRot(body.xRot, target.xRot % 360.0F);
        advance(1.0F);
        last.set(body.position, body.yRot, body.xRot);
    }

    private Pose next() {
        while (!remaining.isEmpty()) {
            Step step = remaining.getFirst();
            int offset = step.tickOffset;
            if (currentStepTicks < offset) {
                float a = currentStepTicks / offset;
                var p = new Pose();
                p.set(lerp(lastStep.position, step.pose.position, a), rotLerp(a, lastStep.yRot, step.pose.yRot),
                        VanillaMath.lerp(a, lastStep.xRot, step.pose.xRot));
                return p;
            }
            currentStepTicks -= offset;
            lastStep.set(step.pose.position, step.pose.yRot, step.pose.xRot);
            remaining.removeFirst();
        }
        return data.copy();
    }

    private void advance(float ticks) {
        float targetSpeed = Math.max(remainingTicks / interpolationSteps, 1.0F);
        speed = VanillaMath.lerp(1.0F / interpolationSteps, speed, targetSpeed);
        if (ticks * speed < remainingTicks) {
            ticks *= speed;
        } else {
            ticks = remainingTicks;
            speed = 1.0F;
        }
        currentStepTicks += ticks;
        remainingTicks -= ticks;
    }

    public void cancel() {
        remaining.clear();
        remainingTicks = 0.0F;
        speed = 1.0F;
    }

    /** {@code Entity.snapTo}: position, raw yaw, stored pitch, old position. */
    public static void snap(PlayerState body, Vec3d position, float yRot, float xRot) {
        body.position = VanillaMath.position(body.position, position);
        body.yRot = RotationUpdate.storeYRot(body.yRot, yRot);
        body.xRot = RotationUpdate.storeXRot(body.xRot, xRot);
        body.oldPosition = body.position;
    }

    /** {@code Vec3.lerp(to, a)}. */
    static Vec3d lerp(Vec3d from, Vec3d to, double a) {
        return new Vec3d(VanillaMath.lerp(a, from.x(), to.x()), VanillaMath.lerp(a, from.y(), to.y()), VanillaMath.lerp(a, from.z(), to.z()));
    }

    /** {@code Mth.rotLerp(float a, float from, float to)}. */
    static float rotLerp(float a, float from, float to) {
        return from + a * BoatPassengerPhysics.wrap(to - from);
    }

    /** The steps of a 26.3 {@code entity_position_sync} path ({@code null}: a linear position). */
    public static List<PathStep> steps(net.minecraft.world.entity.PositionPath path) {
        if (!(path instanceof net.minecraft.world.entity.PositionPath.Stepped stepped)) return null;
        var steps = new ArrayList<PathStep>(stepped.steps().size());
        for (var step : stepped.steps()) steps.add(new PathStep(new Vec3d(step.position().x(), step.position().y(), step.position().z()), step.tickOffset()));
        return steps;
    }

    /** The steps a 26.3 stepped path makes from its positions and tick offsets. */
    public static List<PathStep> steps(double[][] positions, int[] ticks) {
        var steps = new ArrayList<PathStep>(positions.length);
        for (int i = 0; i < positions.length; i++) steps.add(new PathStep(new Vec3d(positions[i][0], positions[i][1], positions[i][2]), ticks[i]));
        return steps;
    }
}
