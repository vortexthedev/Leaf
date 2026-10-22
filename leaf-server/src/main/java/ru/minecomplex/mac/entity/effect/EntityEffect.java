package ru.minecomplex.mac.entity.effect;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;

/** An effect another entity has as the server last sent it ({@code update_mob_effect}); the blend flag is not read. */
public record EntityEffect(String effect, int amplifier, int duration, boolean ambient, boolean visible, boolean showIcon) {
    public static EntityEffect of(ClientboundUpdateMobEffectPacket packet) {
        return new EntityEffect(packet.getEffect().unwrapKey().orElseThrow().identifier().toString(), packet.getEffectAmplifier(),
                packet.getEffectDurationTicks(), packet.isEffectAmbient(), packet.isEffectVisible(), packet.effectShowsIcon());
    }
}
