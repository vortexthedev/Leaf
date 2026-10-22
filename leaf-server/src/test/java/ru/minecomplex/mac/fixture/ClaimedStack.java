package ru.minecomplex.mac.fixture;

/** A fixture claim after a click. Exact values describe known source stacks; native wire claims are hashed.
 * Hashed claims carry item, count and component set/remove masks, without component values. */
public record ClaimedStack(WireItem stack, boolean hashed) {
    public static final ClaimedStack EMPTY = new ClaimedStack(WireItem.EMPTY, false);

    public static ClaimedStack exact(WireItem stack) {
        return new ClaimedStack(stack, false);
    }

    public static ClaimedStack hashed(String item, int count, int set, int removed) {
        return new ClaimedStack(new WireItem(item, count, set, removed, ru.minecomplex.mac.fixture.ItemComponentSample.NONE), true);
    }
}
