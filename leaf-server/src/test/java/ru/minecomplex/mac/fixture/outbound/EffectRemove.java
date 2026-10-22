package ru.minecomplex.mac.fixture.outbound;

/** {@code remove_mob_effect}: the client calls {@code removeEffectNoUpdate(effect)} on the entity. */
public record EffectRemove(int entityId, String effect) implements OutboundEvent {
}
