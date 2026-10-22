package ru.minecomplex.mac.replica;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ru.minecomplex.mac.world.ChunkColumn;
import ru.minecomplex.mac.world.PalettedSection;

/** {@code ClientChunkCache} semantics ({@code ../Client/src/main/java/net/minecraft/.../ClientChunkCache.java}). */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class CompensatedWorldTest {

    private static ChunkColumn column(int x, int z, int state) {
        PalettedSection[] sections = new PalettedSection[24];
        for (int i = 0; i < sections.length; i++) {
            sections[i] = PalettedSection.single(state, 16);
        }
        return new ChunkColumn(x, z, sections);
    }

    @Test
    void storageRangeIsViewDistancePlusThreeAtLeastFive() {
        assertEquals(5, CompensatedWorld.storageRange(0));
        assertEquals(5, CompensatedWorld.storageRange(2));
        assertEquals(13, CompensatedWorld.storageRange(10));
    }

    @Test
    void chunksOutsideTheRangeAreIgnored() {
        CompensatedWorld world = new CompensatedWorld("minecraft:overworld", -64, 384, 2);
        world.load(column(5, 0, 1));
        world.load(column(6, 0, 1));
        assertTrue(world.isLoaded(5, 0));
        assertFalse(world.isLoaded(6, 0), "radius 2 → storage range 5");
    }

    @Test
    void movingTheCenterHidesAndRestoresChunks() {
        CompensatedWorld world = new CompensatedWorld("minecraft:overworld", -64, 384, 2);
        world.load(column(-5, 0, 1));
        world.setCenter(3, 0);
        assertFalse(world.isLoaded(-5, 0), "out of range: hidden");
        world.setCenter(0, 0);
        assertTrue(world.isLoaded(-5, 0), "still in its slot: visible again");
    }

    @Test
    void aChunkEvictsTheOneSharingItsSlot() {
        CompensatedWorld world = new CompensatedWorld("minecraft:overworld", -64, 384, 2);
        world.load(column(-5, 0, 1));
        world.setCenter(1, 0);
        world.load(column(6, 0, 2)); // floorMod(6, 11) == floorMod(-5, 11)
        world.setCenter(0, 0);
        assertFalse(world.isLoaded(-5, 0));
    }

    @Test
    void radiusChangeKeepsOnlyChunksInRange() {
        CompensatedWorld world = new CompensatedWorld("minecraft:overworld", -64, 384, 10);
        world.load(column(0, 0, 1));
        world.load(column(10, 0, 1));
        world.setRadius(2);
        assertTrue(world.isLoaded(0, 0));
        assertFalse(world.isLoaded(10, 0));
        world.setRadius(10);
        assertFalse(world.isLoaded(10, 0), "dropped by the resize, not just hidden");
    }

    @Test
    void blocksAndHeightLimits() {
        CompensatedWorld world = new CompensatedWorld("minecraft:overworld", -64, 384, 10);
        assertEquals(CompensatedWorld.UNLOADED, world.getBlock(0, 64, 0));
        world.load(column(0, 0, 1));
        assertEquals(1, world.getBlock(15, -64, 15));
        assertEquals(CompensatedWorld.AIR, world.getBlock(0, -65, 0));
        assertEquals(CompensatedWorld.AIR, world.getBlock(0, 320, 0));
        world.setBlock(3, -60, 4, 7);
        assertEquals(7, world.getBlock(3, -60, 4));
        world.setBlock(40, 64, 40, 7);
        assertEquals(CompensatedWorld.UNLOADED, world.getBlock(40, 64, 40), "updates of missing chunks are dropped");
        world.forget(0, 0);
        assertNull(world.column(0, 0));
    }

    @Test
    void forksAreCopyOnWriteAndDieWithTheirBase() {
        CompensatedWorld world = new CompensatedWorld("minecraft:overworld", -64, 384, 10);
        ChunkColumn column = column(0, 0, 1);
        world.claim(column);
        world.load(column);
        CompensatedWorld fork = world.fork();
        fork.setBlock(1, 1, 1, 9);
        fork.forget(0, 0);
        assertEquals(1, world.getBlock(1, 1, 1), "the base is untouched");
        assertNotNull(world.column(0, 0));
        assertNull(fork.column(0, 0));

        CompensatedWorld other = world.fork();
        world.setBlock(2, 2, 2, 5);
        assertThrows(IllegalStateException.class, () -> other.getBlock(2, 2, 2), "fork used after its base changed");
    }
}
