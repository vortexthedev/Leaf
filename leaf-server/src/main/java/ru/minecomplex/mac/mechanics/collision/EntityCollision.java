package ru.minecomplex.mac.mechanics.collision;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.Axis;
import ru.minecomplex.mac.collision.Shapes;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * Port of {@code Entity.collide}, {@code collideBoundingBox}, {@code collectColliders},
 * {@code collectCandidateStepUpHeights} and {@code collideWithShapes} (1.21.4 – 26.3; the only difference is the
 * entity-collider query box, {@code ENTITY_COLLISION_STEP_EXPANSION}). Axis order and box movement of the
 * 1.21.4 form and the 26.3 form were checked to compute the same values.
 *
 * <p>Known gap: the world border collider ({@code WorldBorder.getCollisionShape}, a {@code Shapes.join} result) is
 * not modelled yet; {@link Environment#borderShape} must return {@code null} unless a verified shape is available.
 */
public final class EntityCollision {
    private EntityCollision() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    /**
     * What the colliding entity can bump into.
     */
    public static interface Environment {

        BlockSource blocks();

        BlockRegistry registry();

        BlockCollisions.ShapeContext shapes();

        /**
         * {@code level.getEntityCollisions(entity, box)}: shapes of collidable entities intersecting {@code box}.
         */
        List<VoxelShape> entityCollisions(Aabb box);

        /**
         * The border shape when {@code isInsideCloseToBorder}, else {@code null}.
         */
        VoxelShape borderShape(Aabb box);
    }

    /**
     * {@code Entity.collide(movement)} for an entity with bounding box {@code box}.
     */
    public static Vec3d collide(Environment environment, Aabb box, Vec3d movement, boolean onGround, float maxUpStep) {
        Aabb entityQuery = box.expandTowards(movement.x(), movement.y(), movement.z());
        {
            entityQuery = entityQuery.expandTowards(0.0, maxUpStep, 0.0);
        }
        List<VoxelShape> entityColliders = environment.entityCollisions(entityQuery);
        Vec3d movementStep = movement.lengthSqr() == 0.0 ? movement : collideBoundingBox(environment, movement, box, entityColliders);
        boolean xCollision = movement.x() != movementStep.x();
        boolean yCollision = movement.y() != movementStep.y();
        boolean zCollision = movement.z() != movementStep.z();
        boolean onGroundAfterCollision = yCollision && movement.y() < 0.0;
        if (maxUpStep > 0.0F && (onGroundAfterCollision || onGround) && (xCollision || zCollision)) {
            Aabb groundedBox = onGroundAfterCollision ? box.move(0.0, movementStep.y(), 0.0) : box;
            Aabb stepUpBox = groundedBox.expandTowards(movement.x(), maxUpStep, movement.z());
            if (!onGroundAfterCollision) {
                stepUpBox = stepUpBox.expandTowards(0.0, -1.0E-5F, 0.0);
            }
            List<VoxelShape> colliders = collectColliders(environment, entityColliders, stepUpBox);
            float stepHeightToSkip = (float) movementStep.y();
            for (float height : candidateStepUpHeights(groundedBox, colliders, maxUpStep, stepHeightToSkip)) {
                Vec3d stepFromGround = collideWithShapes(new Vec3d(movement.x(), height, movement.z()), groundedBox, colliders);
                if (stepFromGround.horizontalDistanceSqr() > movementStep.horizontalDistanceSqr()) {
                    double distanceToGround = box.minY() - groundedBox.minY();
                    // subtract(0, d, 0) adds -0.0 to x and z, add(0, -d, 0) adds +0.0: only the sign of zero differs
                    return stepFromGround.subtract(0.0, distanceToGround, 0.0);
                }
            }
        }
        return movementStep;
    }

    public static Vec3d collideBoundingBox(Environment environment, Vec3d movement, Aabb box, List<VoxelShape> entityColliders) {
        List<VoxelShape> colliders = collectColliders(environment, entityColliders, box.expandTowards(movement.x(), movement.y(), movement.z()));
        return collideWithShapes(movement, box, colliders);
    }

    /**
     * Entity colliders, then the border (when close), then block collisions in vanilla iteration order.
     */
    static List<VoxelShape> collectColliders(Environment environment, List<VoxelShape> entityColliders, Aabb box) {
        List<VoxelShape> colliders = new ArrayList<>(entityColliders.size() + 8);
        colliders.addAll(entityColliders);
        VoxelShape border = environment.borderShape(box);
        if (border != null) {
            colliders.add(border);
        }
        BlockCollisions.collect(environment.blocks(), environment.registry(), environment.shapes(), box, (x, y, z, shape) -> colliders.add(shape));
        return colliders;
    }

    /**
     * {@code collectCandidateStepUpHeights}: float heights of every collider face in {@code [0, maxStepHeight]} above
     * the box bottom, except the height already reached; a collider's list is left at the first height above the
     * maximum. Distinct by float bits ({@code FloatArraySet}), ascending.
     */
    static float[] candidateStepUpHeights(Aabb box, List<VoxelShape> colliders, float maxStepHeight, float stepHeightToSkip) {
        float[] candidates = new float[4];
        int count = 0;
        for (VoxelShape collider : colliders) {
            int coordCount = collider.coordCount(Axis.Y);
            for (int i = 0; i < coordCount; i++) {
                float relative = (float) (collider.coord(Axis.Y, i) - box.minY());
                if (!(relative < 0.0F) && relative != stepHeightToSkip) {
                    if (relative > maxStepHeight) {
                        break;
                    }
                    if (!contains(candidates, count, relative)) {
                        if (count == candidates.length) {
                            candidates = Arrays.copyOf(candidates, count * 2);
                        }
                        candidates[count++] = relative;
                    }
                }
            }
        }
        float[] sorted = Arrays.copyOf(candidates, count);
        Arrays.sort(sorted);
        return sorted;
    }

    private static boolean contains(float[] values, int count, float value) {
        int bits = Float.floatToIntBits(value);
        for (int i = 0; i < count; i++) {
            if (Float.floatToIntBits(values[i]) == bits) {
                return true;
            }
        }
        return false;
    }

    /** Native Entity.collideWithShapes: resolve Y, then the horizontal axes in decreasing component order. */
    static Vec3d collideWithShapes(Vec3d movement, Aabb box, List<VoxelShape> shapes) {
        if (shapes.isEmpty()) {
            return movement;
        }
        Vec3d resolved = Vec3d.ZERO;
        Axis[] order = Math.abs(movement.x()) < Math.abs(movement.z()) ? YZX : YXZ;
        for (Axis axis : order) {
            double axisMovement = movement.get(axis);
            if (axisMovement != 0.0) {
                double collision = Shapes.collide(axis, box.move(resolved.x(), resolved.y(), resolved.z()), shapes, axisMovement);
                resolved = resolved.with(axis, collision);
            }
        }
        return resolved;
    }

    private static final Axis[] YXZ = { Axis.Y, Axis.X, Axis.Z };

    private static final Axis[] YZX = { Axis.Y, Axis.Z, Axis.X };
}
