package ru.minecomplex.mac.world;

import ru.minecomplex.mac.collision.Axis;

/** Vanilla Direction.values order and legacy 3D data ids. */
public enum BlockDirection {
    DOWN(0, -1, 0, Axis.Y), UP(0, 1, 0, Axis.Y), NORTH(0, 0, -1, Axis.Z), SOUTH(0, 0, 1, Axis.Z),
    WEST(-1, 0, 0, Axis.X), EAST(1, 0, 0, Axis.X);
    public final int x, y, z;
    public final Axis axis;
    BlockDirection(int x, int y, int z, Axis axis) { this.x = x; this.y = y; this.z = z; this.axis = axis; }
    public BlockDirection opposite() { return values()[ordinal() ^ 1]; }
    public static BlockDirection legacy(int value) { return values()[Math.abs(value % 6)]; }
}
