package ru.minecomplex.mac.world;

/** BlockPos integer operations used by the piston resolver. */
public record BlockPosition(int x, int y, int z) {
    /** Vec3i.hashCode: HashMap traversal orders piston deletion and neighbour notifications. */
    @Override public int hashCode() { return (y + z * 31) * 31 + x; }
    public BlockPosition relative(BlockDirection direction) { return relative(direction, 1); }
    public BlockPosition relative(BlockDirection direction, int distance) {
        return new BlockPosition(x + direction.x * distance, y + direction.y * distance, z + direction.z * distance);
    }
}
