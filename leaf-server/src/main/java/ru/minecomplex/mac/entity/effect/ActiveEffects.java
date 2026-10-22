package ru.minecomplex.mac.entity.effect;

import java.util.EnumMap;
import java.util.Map;

/** Client activeEffects, retaining expired entries and independently forked duration/hidden-chain state. */
public final class ActiveEffects {
    private final Map<MobEffect, Integer> amplifiers = new EnumMap<>(MobEffect.class);
    private final Map<MobEffect, EffectInstance> instances = new EnumMap<>(MobEffect.class);

    public boolean has(MobEffect effect) {
        return amplifiers.containsKey(effect);
    }

    public boolean isEmpty() { return amplifiers.isEmpty(); }

    /** Amplifier of an active effect; the effect must be active. */
    public int amplifier(MobEffect effect) {
        Integer amplifier = amplifiers.get(effect);
        if (amplifier == null) {
            throw new IllegalStateException(effect + " is not active");
        }
        return amplifier;
    }

    /** Amplifier-only snapshot: duration and hidden history are unresolved, rather than assumed infinite. */
    public void set(MobEffect effect, int amplifier) {
        amplifiers.put(effect, amplifier);
        instances.remove(effect);
    }

    /** Resolved clientbound replacement. Packet handlers do not merge a previous hidden chain. */
    public void replace(MobEffect effect, EffectInstance instance) {
        EffectInstance owned = instance.copy();
        instances.put(effect, owned);
        amplifiers.put(effect, owned.amplifier());
    }

    /** Local effect application, with vanilla hidden-effect merging. */
    public boolean add(MobEffect effect, EffectInstance instance) {
        EffectInstance current = instances.get(effect);
        if (current == null) {
            if (has(effect)) throw new IllegalStateException("Unresolved effect duration: " + effect);
            replace(effect, instance);
            return true;
        }
        boolean changed = current.update(instance);
        amplifiers.put(effect, current.amplifier());
        return changed;
    }

    public EffectInstance instance(MobEffect effect) {
        EffectInstance instance = instances.get(effect);
        return instance == null ? null : instance.copy();
    }

    public boolean timingResolved() { return instances.size() == amplifiers.size(); }

    public void tickClient() {
        instances.forEach((effect, instance) -> {
            instance.tickClient();
            amplifiers.put(effect, instance.amplifier());
        });
    }

    public void remove(MobEffect effect) {
        amplifiers.remove(effect);
        instances.remove(effect);
    }

    public ActiveEffects copy() {
        ActiveEffects copy = new ActiveEffects();
        copy.amplifiers.putAll(amplifiers);
        instances.forEach((effect, instance) -> copy.instances.put(effect, instance.copy()));
        return copy;
    }
}
