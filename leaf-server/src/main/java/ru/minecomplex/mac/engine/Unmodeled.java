package ru.minecomplex.mac.engine;

/**
 * Situations the movement engine met but does not simulate yet. A tick that reports any of them is not an exact
 * prediction; callers must treat its result as uncertain instead of trusting it. Each entry is a gap listed in
 * {@code docs/research/status.md}.
 */
public enum Unmodeled {
    /** Fluid registry/tags or the dimension's lava-current coefficient remain unresolved. */
    FLUID,
    /** Full-tick effect expiry/hidden chains are not represented by the amplifier-only effect model. */
    EFFECT_TIMING,
    /**
     * The model uses an item the client may not be using: a use the model cannot decide (a riptide trident out of water
     * and the weather, an eye of ender aimed at an end portal frame, a use whose cooldown an undecided use may have
     * started). Resolved by the server's using flags, a release, or when the model's use stops.
     */
    ITEM_USE_TIMING,
    /**
     * An item use whose outcome the model cannot decide: what the client aims at (a bucket, a glass bottle, a block placed
     * on water, a boat, a spawn egg, an eye of ender), a component not modelled (a knowledge book's recipes, an instrument
     * outside the vanilla registry) or a cooldown such a use may have started. The inventory
     * stays unknown until a full inventory menu content; a use cooldown it may have started until the server's cooldown
     * for that group or the tick it would end. (The client's block prediction is the block-prediction model's.)
     */
    ITEM_USE_OUTCOME,
    /**
     * The client attacked an entity: {@code Player.attack} may multiply its horizontal motion by 0.6 and stop its sprint
     * (a knockback hit on a target whose client-side hurt succeeds: another player, a vehicle, an item frame …; attack
     * strength, knockback and the target are not simulated). One tick.
     */
    ATTACK,
    /**
     * A riptide trident release may have launched the player client-side where the model cannot decide it (a stack
     * whose spin strength did not cross the wire, rain at the player not decided, a world it cannot move the player in);
     * or the auto spin attack's touch is not decided ({@code checkAutoSpinAttack}: a player possibly touched, a living
     * entity not followed exactly, a spin that may have ended). The velocity it leaves is a guess until a tick proves it.
     */
    RIPTIDE,
    /** Riding a vehicle ({@code travelRidden}, passenger movement). */
    RIDING,
    /** Vehicle queries require a client chunk/block/entity fact that is not available. */
    VEHICLE_WORLD,
    /** Vehicle's pushable-entity query has not been resolved in this client phase. */
    VEHICLE_ENTITIES,
    /** A malformed forced self mount can create a vanilla cycle; no finite root can be certified. */
    VEHICLE_PASSENGER_GRAPH,
    /** A context-dependent collision shape of a block class without a port. */
    CONTEXT_SHAPE,
    /** Client block-event/NBT application, neighbour mutations or block-entity pushes have not been replayed. */
    DYNAMIC_BLOCK_APPLICATION,
    /** A piston callback reaches an unported neighbouring block's state update. */
    PISTON_NEIGHBOURS,
    /** The moving piston's entity queries or local pushes have unresolved entity state. */
    PISTON_ENTITIES,
    /** Required piston block-entity NBT/fields would crash vanilla or remain unresolved. */
    PISTON_BLOCK_ENTITY,
    /** A handler-only world has not resolved intervening client block-entity ticks. */
    PISTON_TICK_TIMING,
    /** Shulker opening entity queries have not been resolved for this client phase. */
    SHULKER_ENTITIES,
    /** Shulker neighbour-shape callbacks reach an unported block. */
    SHULKER_NEIGHBOURS,
    /** A handler-only shulker snapshot has unresolved intervening animation ticks. */
    SHULKER_TICK_TIMING,
    /** A piston needs an unresolved interpolating world-border clock. */
    WORLD_BORDER_CLOCK,
    /** Earlier client-global fluid shape queries are unresolved and possible cached heights change this ray. */
    FLUID_SHAPE_CACHE,
    /** Suffocation depends on a shulker block entity whose closed state is unresolved. */
    BLOCK_ENTITY_SUFFOCATION,
    /**
     * A block the client changed in a prediction the model could not resolve (whether a survival start breaks at once, an
     * adventure rule, a game master block, a patched or unaudited item rule, an unported neighbour callback): uncertain
     * until the server acknowledges that prediction; or two blocks an acknowledgement set back both move the player.
     */
    BLOCK_PREDICTION,
    /**
     * {@code login}/{@code respawn} recreated the LocalPlayer at a position that depends on level spawn data and
     * {@code resetPos}'s collision search; resolved by the next absolute teleport.
     */
    RESPAWN_POSITION,
    /**
     * After a wake-up ({@code animate} → {@code stopSleepInBed} → {@code stopSleeping}) the client stood up at
     * {@code BedBlock.findStandUpPosition} in its own world (not modelled) until an absolute teleport (the server's own
     * stand-up teleport follows); also a sleeping position whose bed's sleep height is not known.
     */
    BED_STAND_UP,
    /**
     * A sleep or a wake-up the client may have handled a tick before the model applied it (no state the probe kept handled it
     * that early): it put the player on its bed or stood it up then, so this tick's start is not known; the velocity it
     * leaves is a guess until a tick proves it.
     */
    SLEEP_TIMING,
    /**
     * An own PlayerInfo entry was removed before a known successful lookup: a render may have cached it, or the
     * LocalPlayer may still have a null cache. Known cached entries retain exact identity across remove/re-add.
     */
    PLAYER_INFO_IDENTITY,
    /** Entity data gave the own player a pose name no supported release has (every vanilla pose is modelled). */
    ENTITY_POSE,
    /**
     * A mount screen for an entity the world model does not know: the client either opened the mount menu or kept its
     * menu, so slot updates may change player inventory slots through either. Resolved by a full inventory menu content
     * (or the next open or close).
     */
    INVENTORY_CONTAINER,
    /**
     * The selected hotbar stack may have been emptied by a drop whose slot the packets leave open, or by a block or entity interaction that took its last
     * item: {@code CompensatedInventory.mainHandMayBeEmpty()}. Cleared when the slot is written by the server or no longer
     * selected.
     */
    DROP_SLOT,
    /**
     * Some player inventory slot may differ from the model ({@code CompensatedInventory.unknown}): a hashed click result no
     * stack of the menu explains, a click on a menu the model does not hold, an interaction that may add a stack anywhere
     * or replace the hand's. Each is resolved by a server write of it, every slot by a full inventory menu content.
     */
    INVENTORY_CLIENT_ACTION,
    /**
     * An entity that can collide with the moving body has a box the model does not know: a shulker entity, a collidable
     * entity riding another one or one whose client transform is unknown ({@code EntityMotions}).
     */
    ENTITY_COLLISION,
    /**
     * An entity may push the local player: a boat or happy ghast touching it, or a living entity or minecart that may be
     * near (pushes and scoreboard collision rules are not modelled).
     */
    ENTITY_PUSH,
    /**
     * A potent sulfur geyser the model cannot follow: a block in its column whose collision depends on the
     * collision context or a moving piston, or a geyser that may lift a player state the model cannot hand it.
     */
    GEYSER,
    /** A {@code teleport_entity} addressed the own player: the client moves its LocalPlayer by it (not modelled). */
    ENTITY_MOTION
}
