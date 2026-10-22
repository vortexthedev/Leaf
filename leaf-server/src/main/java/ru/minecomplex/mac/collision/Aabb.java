package ru.minecomplex.mac.collision;

/**
 * {@code net.minecraft.world.phys.AABB}; immutable. Like vanilla's constructor, the canonical constructor stores
 * {@code Math.min}/{@code Math.max} of each pair (this also fixes the sign of equal zeros the vanilla way).
 */
public record Aabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    public Aabb {
        double x0 = minX;
        double y0 = minY;
        double z0 = minZ;
        minX = Math.min(x0, maxX);
        minY = Math.min(y0, maxY);
        minZ = Math.min(z0, maxZ);
        maxX = Math.max(x0, maxX);
        maxY = Math.max(y0, maxY);
        maxZ = Math.max(z0, maxZ);
    }

    public double min(Axis axis) {
        return axis.choose(minX, minY, minZ);
    }

    public double max(Axis axis) {
        return axis.choose(maxX, maxY, maxZ);
    }

    public Aabb inflate(double amount) { return inflate(amount, amount, amount); }

    public Aabb inflate(double x, double y, double z) {
        return new Aabb(minX - x, minY - y, minZ - z, maxX + x, maxY + y, maxZ + z);
    }

    public Aabb deflate(double amount) { return inflate(-amount); }

    /** {@code AABB.move(double, double, double)}. */
    public Aabb move(double dx, double dy, double dz) {
        return new Aabb(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /** {@code AABB.expandTowards}: grows the side the movement points to. */
    public Aabb expandTowards(double xa, double ya, double za) {
        double x0 = minX;
        double y0 = minY;
        double z0 = minZ;
        double x1 = maxX;
        double y1 = maxY;
        double z1 = maxZ;
        if (xa < 0.0) {
            x0 += xa;
        } else if (xa > 0.0) {
            x1 += xa;
        }
        if (ya < 0.0) {
            y0 += ya;
        } else if (ya > 0.0) {
            y1 += ya;
        }
        if (za < 0.0) {
            z0 += za;
        } else if (za > 0.0) {
            z1 += za;
        }
        return new Aabb(x0, y0, z0, x1, y1, z1);
    }

    /** {@code AABB.minmax}: the box holding both. */
    public Aabb minmax(Aabb other) {
        return new Aabb(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
    }

    /** {@code AABB.intersects(minX, minY, minZ, maxX, maxY, maxZ)}: strict overlap. */
    public boolean intersects(double x0, double y0, double z0, double x1, double y1, double z1) {
        return minX < x1 && maxX > x0 && minY < y1 && maxY > y0 && minZ < z1 && maxZ > z0;
    }
}
