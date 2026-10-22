package ru.minecomplex.mac.mechanics.fluid;

import java.util.HashMap;
import java.util.Map;
import ru.minecomplex.mac.world.BlockRegistry;

/** Client-global FlowingFluid shape cache, forked with the client hypothesis, including prior pick/bucket rays. */
public final class FluidShapeCache {
    public record Key(String fluid, int amount, boolean falling) {}
    private final boolean knownEmpty;
    private final Map<Key, Float> heights = new HashMap<>();

    private FluidShapeCache(boolean knownEmpty) { this.knownEmpty = knownEmpty; }
    /** Only for a client whose preceding shape queries are known to be absent. */
    public static FluidShapeCache empty() { return new FluidShapeCache(true); }
    public static FluidShapeCache unresolved() { return new FluidShapeCache(false); }
    public static Key key(BlockRegistry registry, int state) {
        return new Key(registry.fluid(state), registry.fluidAmount(state), registry.has(state, BlockRegistry.FLAG_FLUID_FALLING));
    }
    public Float height(Key key, float initialHeight) {
        Float value = heights.get(key);
        if (value == null && knownEmpty) {
            heights.put(key, initialHeight);
            return initialHeight;
        }
        return value;
    }
    /** Resolve a cache entry from the same client's earlier shape-query history. */
    public void resolve(Key key, float height) { heights.put(key, height); }
    public Float cachedHeight(Key key) { return heights.get(key); }
    public FluidShapeCache copy() {
        var copy = new FluidShapeCache(knownEmpty);
        copy.heights.putAll(heights);
        return copy;
    }
}
