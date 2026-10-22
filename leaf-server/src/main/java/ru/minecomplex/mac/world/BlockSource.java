package ru.minecomplex.mac.world;

/** Read access to block states in the client's id space. */
public interface BlockSource {
    /** State at a position; {@link CompensatedWorld#UNLOADED} where the client has no chunk (vanilla skips those). */
    int getBlock(int x, int y, int z);
}
