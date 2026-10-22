package ru.minecomplex.mac.player.state;

import net.minecraft.world.level.GameType;

/** {@code net.minecraft.world.entity.player.Abilities} as the client holds it (set by the abilities packet). */
public final class Abilities {
    public boolean invulnerable;
    public boolean flying;
    public boolean mayfly;
    public boolean instabuild;
    public float flyingSpeed = 0.05F;
    public float walkingSpeed = 0.1F;

    /** {@code GameType.updatePlayerAbilities}; {@code mayBuild} is not modelled (no movement reads it). */
    public void updateGameMode(GameType mode) {
        if (mode == GameType.CREATIVE) {
            mayfly = true;
            instabuild = true;
            invulnerable = true;
        } else if (mode == GameType.SPECTATOR) {
            mayfly = true;
            instabuild = false;
            invulnerable = true;
            flying = true;
        } else {
            mayfly = false;
            instabuild = false;
            invulnerable = false;
            flying = false;
        }
    }

    public Abilities copy() {
        Abilities copy = new Abilities();
        copy.invulnerable = invulnerable;
        copy.flying = flying;
        copy.mayfly = mayfly;
        copy.instabuild = instabuild;
        copy.flyingSpeed = flyingSpeed;
        copy.walkingSpeed = walkingSpeed;
        return copy;
    }
}
