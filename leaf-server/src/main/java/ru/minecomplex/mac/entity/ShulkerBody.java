package ru.minecomplex.mac.entity;

import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.util.math.Trig;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.world.BlockDirection;

/**
 * Another shulker entity on the client. It has no interpolation (1.21.4 {@code lerpTo} sets the position, later releases
 * have no interpolation handler): every position its handlers give goes through {@code Shulker.setPos}, which puts it at
 * its block's centre ({@code floor(x) + 0.5, floor(y + 0.5), floor(z) + 0.5}) unless it rides, and — once it has ticked —
 * clears its peek when the block changes ({@code entityData.set(DATA_PEEK_ID, 0)} on the client too). {@code snapTo}
 * (spawn, far teleports and syncs) sets the raw position first and then {@code reapplyPosition}s through {@code setPos}.
 *
 * <p>Its box is {@code getProgressAabb(getScale(), attachFace.getOpposite(), getPhysicalPeek(currentPeekAmount), position)},
 * stored whenever the position is set or reapplied, the attach face changes or the dimensions refresh; {@code getScale()}
 * is the current {@code scale} (at most 3). Its tick moves {@code currentPeekAmount} 0.05 towards the synched peek / 100
 * ({@code updatePeekAmount}) and, when it moved, reapplies the box and moves what the opening sweeps
 * ({@code onPeekAmountChange}).
 */
final class ShulkerBody {
    Vec3d position = Vec3d.ZERO;
    /** {@code getPositionCodec().getBase()}. */
    Vec3d codecBase = Vec3d.ZERO;
    /** {@code DATA_ATTACH_FACE_ID}: a {@code Direction} by its wire id (down by default). */
    BlockDirection attachFace = BlockDirection.DOWN;
    /** {@code DATA_PEEK_ID} as the client holds it (the server's, or 0 after a block change). */
    int rawPeek;
    /** {@code currentPeekAmount} and {@code currentPeekAmountO}. */
    float peek, peekO;
    /** {@code getBoundingBox()}. */
    Aabb box;
    /** The {@code scale} the stored box was made with. */
    float boxScale = 1.0F;
    /** {@code tickCount}. */
    int tickCount;

    ShulkerBody copy() {
        var copy = new ShulkerBody();
        copy.position = position;
        copy.codecBase = codecBase;
        copy.attachFace = attachFace;
        copy.rawPeek = rawPeek;
        copy.peek = peek;
        copy.peekO = peekO;
        copy.box = box;
        copy.boxScale = boxScale;
        copy.tickCount = tickCount;
        return copy;
    }

    /** {@code getPhysicalPeek}: {@code 0.5F - Mth.sin((0.5F + amount) * (float) Math.PI) * 0.5F}. */
    static float physicalPeek(float amount) {
        return 0.5F - Trig.sin((0.5F + amount) * (float) Math.PI) * 0.5F;
    }

    /** {@code Shulker.getProgressDeltaAabb(size, direction, from, to, position)}. */
    static Aabb progressDelta(float size, BlockDirection direction, float from, float to, Vec3d at) {
        double max = Math.max(from, to), min = Math.min(from, to);
        // AABB's constructor orders each axis
        double half = -size * 0.5, other = size * 0.5;
        Aabb box = new Aabb(Math.min(half, other), Math.min(0.0, size), Math.min(half, other),
                Math.max(half, other), Math.max(0.0, size), Math.max(half, other))
                .expandTowards(direction.x * max * size, direction.y * max * size, direction.z * max * size);
        double x = -direction.x * (1.0 + min) * size, y = -direction.y * (1.0 + min) * size, z = -direction.z * (1.0 + min) * size;
        // AABB.contract: positive values move the maximum face, negative values move the minimum face
        double minX = box.minX(), minY = box.minY(), minZ = box.minZ(), maxX = box.maxX(), maxY = box.maxY(), maxZ = box.maxZ();
        if (x < 0.0) minX -= x; else if (x > 0.0) maxX -= x;
        if (y < 0.0) minY -= y; else if (y > 0.0) maxY -= y;
        if (z < 0.0) minZ -= z; else if (z > 0.0) maxZ -= z;
        return new Aabb(Math.min(minX, maxX), Math.min(minY, maxY), Math.min(minZ, maxZ), Math.max(minX, maxX), Math.max(minY, maxY),
                Math.max(minZ, maxZ)).move(at.x(), at.y(), at.z());
    }

    /** {@code makeBoundingBox(position)}. */
    Aabb makeBox(Vec3d at, float scale) {
        return progressDelta(scale, attachFace.opposite(), -1.0F, physicalPeek(peek), at);
    }

    /** {@code setBoundingBox(makeBoundingBox())}. */
    void restoreBox(float scale) {
        boxScale = scale;
        box = makeBox(position, scale);
    }

    private static boolean sameBlock(Vec3d a, Vec3d b) {
        return VanillaMath.floor(a.x()) == VanillaMath.floor(b.x()) && VanillaMath.floor(a.y()) == VanillaMath.floor(b.y())
                && VanillaMath.floor(a.z()) == VanillaMath.floor(b.z());
    }

    /** {@code Shulker.setPos}. */
    void setPos(Vec3d target, boolean passenger, float scale) {
        Vec3d old = position;
        position = passenger ? target
                : new Vec3d(VanillaMath.floor(target.x()) + 0.5, VanillaMath.floor(target.y() + 0.5), VanillaMath.floor(target.z()) + 0.5);
        restoreBox(scale);
        if (tickCount != 0 && !sameBlock(position, old)) rawPeek = 0;
    }

    /** {@code snapTo}: {@code setPosRaw}, then {@code reapplyPosition} through {@code setPos}. */
    void snapTo(Vec3d target, boolean passenger, float scale) {
        position = target;
        setPos(target, passenger, scale);
    }
}
