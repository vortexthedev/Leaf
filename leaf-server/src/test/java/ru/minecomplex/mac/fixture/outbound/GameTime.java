package ru.minecomplex.mac.fixture.outbound;

/**
 * {@code set_time}: the level's game time ({@code ClientLevel.setTimeFromServer}). The client then advances it once per
 * tick, after the entity ticks ({@code Minecraft.tick}: {@code tickEntities} before {@code ClientLevel.tick}).
 */
public record GameTime(long gameTime) implements OutboundEvent {
}
