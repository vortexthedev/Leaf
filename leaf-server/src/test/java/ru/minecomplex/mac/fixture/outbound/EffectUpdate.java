package ru.minecomplex.mac.fixture.outbound;

/**
 * {@code update_mob_effect}. The client builds {@code new MobEffectInstance(effect, duration, amplifier, ambient,
 * visible, showIcon, null)} and applies it with {@code forceAddEffect} ({@code ClientPacketListener.handleUpdateMobEffect}).
 *
 * @param effect   registry key of the effect in the client's registry, e.g. {@code minecraft:jump_boost}
 * @param amplifier amplifier as decoded from the wire (the client clamps it to 0..255)
 * @param blend    {@code shouldBlend}; affects only the visual blend state
 */
public record EffectUpdate(int entityId, String effect, int amplifier, int duration, boolean ambient, boolean visible,
                           boolean showIcon, boolean blend) implements OutboundEvent {
}
