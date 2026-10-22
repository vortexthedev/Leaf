package ru.minecomplex.mac.fixture.outbound;

import java.util.Arrays;

public record EntityRemove(int[] entityIds) implements OutboundEvent {
    public EntityRemove { entityIds = entityIds.clone(); }
    @Override public int[] entityIds() { return entityIds.clone(); }
    @Override public boolean equals(Object other) { return other instanceof EntityRemove remove && Arrays.equals(entityIds, remove.entityIds); }
    @Override public int hashCode() { return Arrays.hashCode(entityIds); }
}
