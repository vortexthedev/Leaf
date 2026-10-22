package ru.minecomplex.mac.entity;

import ru.minecomplex.mac.replica.CompensatedEntities;

/**
 * The local player as an entity of its level: an identity and its passenger relations. Its own state (position,
 * attributes, effects, inventory) is the client player model's ({@code PlayerModel}); packets naming its id are
 * handled there, not here.
 */
public final class PacketEntitySelf extends PacketEntity {
    public PacketEntitySelf(CompensatedEntities owner, int id) {
        super(owner, id, "minecraft:player", true, null);
    }

    private PacketEntitySelf(CompensatedEntities owner, int id, Object generation) {
        super(owner, id, "minecraft:player", true, null, generation);
    }

    @Override
    public PacketEntitySelf copy(CompensatedEntities owner) {
        return copyInto(new PacketEntitySelf(owner, id(), generation()));
    }
}
