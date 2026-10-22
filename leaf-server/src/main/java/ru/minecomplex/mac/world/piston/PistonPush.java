package ru.minecomplex.mac.world.piston;

import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.Axis;
import ru.minecomplex.mac.mechanics.collision.BlockCollisions;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.world.BlockDirection;
import ru.minecomplex.mac.world.BlockPosition;
import ru.minecomplex.mac.world.BlockRegistry;

import java.util.List;

/**
 * PistonMovingBlockEntity entity queries and pushes. Query results and movement side effects belong to the same hypothesis.
 */
public final class PistonPush {

    private final BlockRegistry registry;
    private final BlockCollisions.ShapeContext context;

    public PistonPush(BlockRegistry registry, BlockCollisions.ShapeContext context) {
        this.registry = registry;
        this.context = context;
    }

    private static boolean intersects(Aabb a, Aabb b) {
        return a.intersects(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    private static Aabb union(Aabb a, Aabb b) {
        return new Aabb(Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()), Math.min(a.minZ(), b.minZ()), Math.max(a.maxX(), b.maxX()), Math.max(a.maxY(), b.maxY()), Math.max(a.maxZ(), b.maxZ()));
    }

    private static Aabb intersect(Aabb a, Aabb b) {
        return new Aabb(Math.max(a.minX(), b.minX()), Math.max(a.minY(), b.minY()), Math.max(a.minZ(), b.minZ()), Math.min(a.maxX(), b.maxX()), Math.min(a.maxY(), b.maxY()), Math.min(a.maxZ(), b.maxZ()));
    }

    public static Aabb movementArea(Aabb a, BlockDirection direction, double amount) {
        double delta = amount * (direction.x + direction.y + direction.z), min = Math.min(delta, 0.0), max = Math.max(delta, 0.0);
        return switch (direction) {
            case WEST -> new Aabb(a.minX() + min, a.minY(), a.minZ(), a.minX() + max, a.maxY(), a.maxZ());
            case EAST -> new Aabb(a.maxX() + min, a.minY(), a.minZ(), a.maxX() + max, a.maxY(), a.maxZ());
            case DOWN -> new Aabb(a.minX(), a.minY() + min, a.minZ(), a.maxX(), a.minY() + max, a.maxZ());
            case UP -> new Aabb(a.minX(), a.maxY() + min, a.minZ(), a.maxX(), a.maxY() + max, a.maxZ());
            case NORTH -> new Aabb(a.minX(), a.minY(), a.minZ() + min, a.maxX(), a.maxY(), a.minZ() + max);
            case SOUTH -> new Aabb(a.minX(), a.minY(), a.maxZ() + min, a.maxX(), a.maxY(), a.maxZ() + max);
        };
    }

    private static double movement(Aabb moving, BlockDirection direction, Aabb entity) {
        return switch (direction) {
            case EAST -> moving.maxX() - entity.minX();
            case WEST -> entity.maxX() - moving.minX();
            case UP -> moving.maxY() - entity.minY();
            case DOWN -> entity.maxY() - moving.minY();
            case SOUTH -> moving.maxZ() - entity.minZ();
            case NORTH -> entity.maxZ() - moving.minZ();
        };
    }

    private static void move(Entity entity, BlockDirection noClip, double amount, BlockDirection direction) {
        entity.move(noClip, new Vec3d(amount * direction.x, amount * direction.y, amount * direction.z));
    }

    private static Aabb translated(Aabb box, BlockPosition pos, MovingPiston piston) {
        double offset = piston.extendedProgress(piston.progress());
        return box.move(pos.x() + offset * piston.direction().x, pos.y() + offset * piston.direction().y, pos.z() + offset * piston.direction().z);
    }

    private int collisionState(MovingPiston piston) {
        int state = piston.movedState();
        if (!piston.extending() && piston.source() && registry.block(state).isInstance("PistonBaseBlock")) {
            var facts = PistonProperties.get();
            int head = facts.defaultState("minecraft:piston_head");
            head = facts.with(head, "short", Boolean.toString(piston.progress() > 0.25F));
            head = facts.with(head, "type", registry.block(state).name().equals("minecraft:sticky_piston") ? "sticky" : "normal");
            state = facts.with(head, "facing", facts.value(state, "facing"));
        }
        return state;
    }

    public void push(BlockPosition pos, MovingPiston piston, float next, Query query) {
        BlockDirection direction = piston.movementDirection();
        double deltaProgress = next - piston.progress();
        var shape = context.shape(collisionState(piston), pos.x(), pos.y(), pos.z());
        if (!shape.isEmpty()) {
            Aabb bounds = new Aabb(shape.min(Axis.X), shape.min(Axis.Y), shape.min(Axis.Z), shape.max(Axis.X), shape.max(Axis.Y), shape.max(Axis.Z));
            Aabb current = translated(bounds, pos, piston), area = union(movementArea(current, direction, deltaProgress), current);
            var entities = query.entities(area);
            var boxes = shape.toAabbs();
            boolean slime = registry.block(piston.movedState()).name().equals("minecraft:slime_block");
            for (Entity entity : entities)
                if (!entity.ignored()) {
                    if (slime && (entity.canSimulateMovement()))
                        entity.velocity(entity.velocity().with(direction.axis, (double) (direction.x + direction.y + direction.z)));
                    double delta = 0.0;
                    for (Aabb box : boxes) {
                        Aabb moving = movementArea(translated(box, pos, piston), direction, deltaProgress);
                        if (intersects(moving, entity.box())) {
                            delta = Math.max(delta, movement(moving, direction, entity.box()));
                            if (delta >= deltaProgress)
                                break;
                        }
                    }
                    if (delta <= 0.0)
                        continue;
                    move(entity, direction, Math.min(delta, deltaProgress) + 0.01, direction);
                    if (!piston.extending() && piston.source()) {
                        Aabb base = new Aabb(pos.x(), pos.y(), pos.z(), pos.x() + 1, pos.y() + 1, pos.z() + 1), box = entity.box();
                        if (intersects(box, base)) {
                            BlockDirection opposite = direction.opposite();
                            double distance = movement(base, opposite, box) + 0.01, clipped = movement(base, opposite, intersect(box, base)) + 0.01;
                            if (Math.abs(distance - clipped) < 0.01)
                                move(entity, direction, Math.min(distance, deltaProgress) + 0.01, opposite);
                        }
                    }
                }
        }
        if (registry.block(piston.movedState()).name().equals("minecraft:honey_block") && direction.axis != Axis.Y) {
            double top = context.shape(piston.movedState(), pos.x(), pos.y(), pos.z()).max(Axis.Y);
            Aabb sticky = translated(new Aabb(0.0, top, 0.0, 1.0, 1.5000010000000001, 1.0), pos, piston);
            for (Entity entity : query.entities(sticky)) {
                var p = entity.position();
                if (entity.normalReaction() && entity.onGround() && (entity.supportedBy(pos) || p.x() >= sticky.minX() && p.x() <= sticky.maxX() && p.z() >= sticky.minZ() && p.z() <= sticky.maxZ()))
                    move(entity, direction, deltaProgress, direction);
            }
        }
    }

    public interface Entity {

        Aabb box();

        Vec3d position();

        Vec3d velocity();

        void velocity(Vec3d value);

        boolean ignored();

        boolean normalReaction();

        boolean onGround();

        boolean supportedBy(BlockPosition pos);

        boolean serverPlayer();

        boolean canSimulateMovement();

        /**
         * Includes Entity.move(PISTON), NOCLIP scope and the release's immediate block effects.
         */
        void move(BlockDirection noClip, Vec3d delta);
    }

    @FunctionalInterface
    public interface Query {

        List<? extends Entity> entities(Aabb box);
    }
}
