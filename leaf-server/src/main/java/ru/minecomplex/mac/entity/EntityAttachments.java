package ru.minecomplex.mac.entity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import ru.minecomplex.mac.util.math.Trig;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * Vanilla {@code EntityAttachments}: the attachment points of one dimensions value, every kind present (possibly
 * empty). Lookups rotate the point by the entity's yaw with the release's {@code Mth} tables ({@link Trig}).
 */
public final class EntityAttachments {
    private final Map<EntityAttachment, List<Vec3d>> points;

    public EntityAttachments(Map<EntityAttachment, List<Vec3d>> points) {
        var copy = new EnumMap<EntityAttachment, List<Vec3d>>(EntityAttachment.class);
        for (EntityAttachment kind : EntityAttachment.values()) copy.put(kind, List.copyOf(points.getOrDefault(kind, List.of())));
        this.points = copy;
    }

    public List<Vec3d> points(EntityAttachment kind) {
        return points.get(kind);
    }

    /** {@code scale(x, y, z)}: every point {@code multiply(x, y, z)} (floats widened). */
    public EntityAttachments scale(float x, float y, float z) {
        var scaled = new EnumMap<EntityAttachment, List<Vec3d>>(EntityAttachment.class);
        for (var entry : points.entrySet()) {
            var list = new ArrayList<Vec3d>(entry.getValue().size());
            for (Vec3d point : entry.getValue()) list.add(new Vec3d(point.x() * x, point.y() * y, point.z() * z));
            scaled.put(entry.getKey(), list);
        }
        return new EntityAttachments(scaled);
    }

    /** {@code getNullable}: the point at {@code index} rotated by {@code yRot}, or {@code null} when there is none. */
    public Vec3d getNullable(EntityAttachment kind, int index, float yRot) {
        List<Vec3d> list = points.get(kind);
        return index >= 0 && index < list.size() ? transform(list.get(index), yRot) : null;
    }

    /** {@code get}: as {@link #getNullable}, an absent point is an error. */
    public Vec3d get(EntityAttachment kind, int index, float yRot) {
        Vec3d point = getNullable(kind, index, yRot);
        if (point == null) throw new IllegalStateException("Had no attachment point of type: " + kind + " for index: " + index);
        return point;
    }

    /** {@code getClamped}: the index clamped to the list. */
    public Vec3d getClamped(EntityAttachment kind, int index, float yRot) {
        List<Vec3d> list = points.get(kind);
        if (list.isEmpty()) throw new IllegalStateException("Had no attachment points of type: " + kind);
        return transform(list.get(Math.max(0, Math.min(index, list.size() - 1))), yRot);
    }

    /** {@code transformPoint}: {@code point.yRot(-yRot * (float) (Math.PI / 180.0))}. */
    private static Vec3d transform(Vec3d point, float yRot) {
        float angle = -yRot * (float) (Math.PI / 180.0);
        float cos = Trig.cos(angle);
        float sin = Trig.sin(angle);
        return new Vec3d(point.x() * cos + point.z() * sin, point.y(), point.z() * cos - point.x() * sin);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EntityAttachments that && points.equals(that.points);
    }

    @Override
    public int hashCode() {
        return points.hashCode();
    }

    @Override
    public String toString() {
        return points.toString();
    }
}
