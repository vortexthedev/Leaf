package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import ru.minecomplex.mac.replica.CompensatedEntities;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

/**
 * Another player ({@code RemotePlayer}). It keeps the {@code PlayerInfo} it was created with: its name, and its game
 * mode as long as the connection keeps that info ({@code AbstractClientPlayer.getPlayerInfo} caches the object it found).
 */
public final class PacketEntityPlayer extends PacketEntityLiving {
    /** The info it was created with ({@code null}: none was known). */
    CompensatedEntities.PlayerInfo info;
    /** Its cached {@code PlayerInfo} is not known ({@code CompensatedEntities#lookUpPlayerInfo}). */
    boolean ambiguousInfo;

    public PacketEntityPlayer(CompensatedEntities owner, int id, String type, ClientboundAddEntityPacket spawn) {
        super(owner, id, type, true, spawn);
    }

    private PacketEntityPlayer(CompensatedEntities owner, PacketEntityPlayer source) {
        super(owner, source);
    }

    /** A copy whose info is {@code info} (the copied connection's object). */
    public PacketEntityPlayer copy(CompensatedEntities owner, CompensatedEntities.PlayerInfo info) {
        var copy = copyLiving(new PacketEntityPlayer(owner, this));
        copy.info = info;
        copy.ambiguousInfo = ambiguousInfo;
        return copy;
    }

    @Override
    public PacketEntityPlayer copy(CompensatedEntities owner) {
        throw new UnsupportedOperationException("A player entity is copied with its connection's PlayerInfo");
    }

    public CompensatedEntities.PlayerInfo info() {
        return info;
    }

    /** The {@code PlayerInfo} it is created with ({@code null}: none known). */
    public void info(CompensatedEntities.PlayerInfo value) {
        info = value;
    }

    public boolean ambiguousInfo() {
        return ambiguousInfo;
    }

    public void ambiguousInfo(boolean value) {
        ambiguousInfo = value;
    }
}
