package ru.minecomplex.mac.world;

/** A block change of a column whose dynamic blocks are replayed ({@code setBlock}, server-verified or predicted). */
public record BlockChange(int x, int y, int z, int state) implements BlockInput {
}
