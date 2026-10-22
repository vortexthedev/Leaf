package ru.minecomplex.mac.entity.effect;

/** Client movement-relevant MobEffectInstance state. Visual blending is intentionally outside this model. */
public final class EffectInstance {
    private int duration;
    private int amplifier;
    private boolean ambient, visible, showIcon;
    private EffectInstance hidden;

    public EffectInstance(int duration, int amplifier, boolean ambient, boolean visible, boolean showIcon) {
        this.duration = duration;
        this.amplifier = Math.clamp(amplifier, 0, 255);
        this.ambient = ambient;
        this.visible = visible;
        this.showIcon = showIcon;
    }

    public int duration() { return duration; }
    public int amplifier() { return amplifier; }
    public boolean ambient() { return ambient; }
    public boolean visible() { return visible; }
    public boolean showIcon() { return showIcon; }
    public EffectInstance hidden() { return hidden == null ? null : hidden.copy(); }

    private boolean shorterThan(EffectInstance other) {
        return duration != -1 && (duration < other.duration || other.duration == -1);
    }

    /** Local addEffect merge, distinct from a clientbound forceAddEffect replacement. */
    public boolean update(EffectInstance incoming) {
        boolean changed = false;
        if (incoming.amplifier > amplifier) {
            if (incoming.shorterThan(this)) {
                EffectInstance previous = hidden;
                hidden = detailsCopy();
                hidden.hidden = previous;
            }
            amplifier = incoming.amplifier;
            duration = incoming.duration;
            changed = true;
        } else if (shorterThan(incoming)) {
            if (incoming.amplifier == amplifier) {
                duration = incoming.duration;
                changed = true;
            } else if (hidden == null) {
                hidden = incoming.detailsCopy();
            } else {
                hidden.update(incoming);
            }
        }
        if (!incoming.ambient && ambient || changed) {
            ambient = incoming.ambient;
            changed = true;
        }
        if (incoming.visible != visible) { visible = incoming.visible; changed = true; }
        if (incoming.showIcon != showIcon) { showIcon = incoming.showIcon; changed = true; }
        return changed;
    }

    /** LivingEntity.baseTick: expiration does not remove an entry from the client's activeEffects. */
    public void tickClient() {
        if (duration == -1 || duration > 0) {
            tickDownDuration();
            if (duration == 0 && hidden != null) {
                duration = hidden.duration;
                amplifier = hidden.amplifier;
                ambient = hidden.ambient;
                visible = hidden.visible;
                showIcon = hidden.showIcon;
                hidden = hidden.hidden;
            }
        }
    }

    private void tickDownDuration() {
        if (hidden != null) hidden.tickDownDuration();
        if (duration != -1 && duration != 0) duration--;
    }

    private EffectInstance detailsCopy() {
        return new EffectInstance(duration, amplifier, ambient, visible, showIcon);
    }

    /** Hypothesis copies retain the complete hidden chain; vanilla's details copy does not. */
    public EffectInstance copy() {
        EffectInstance copy = detailsCopy();
        copy.hidden = hidden == null ? null : hidden.copy();
        return copy;
    }
}
