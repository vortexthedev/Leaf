package ru.minecomplex.mac.player.state;

import net.minecraft.world.level.GameType;

import java.util.HashMap;
import java.util.Map;
import ru.minecomplex.mac.replica.CompensatedInventory;
import net.minecraft.world.item.ItemStack;
import ru.minecomplex.mac.inventory.item.Stacks;
import ru.minecomplex.mac.listener.TickActions;
import ru.minecomplex.mac.player.ClientItemUse;

/**
 * Client state of the local player that clientbound packets replace and that {@code PlayerState} does not hold:
 * health and food ({@code set_health}), the game mode of {@code MultiPlayerGameMode} ({@code game_event}) and the
 * game mode of the player's own {@code PlayerInfo} ({@code player_info_update}), the inventory and the selected slot
 * the client last sent. Forked with the hypothesis.
 */
public final class ClientPlayerData {
    /** The LocalPlayer's entity id ({@code login}; a respawn keeps it), {@code Integer.MIN_VALUE} before login. */
    public int entityId = Integer.MIN_VALUE;
    /**
     * Stored health: {@code set_health} clamps it to {@code [0, max health]}, {@code set_entity_data} stores it as sent.
     * Movement reads only {@code health <= 0} (dead) and NaN; the exact value is copied by a respawn keeping entity data.
     */
    public float health = 20.0F;
    /** {@code FoodData}: a new player starts with 20 and saturation 5. */
    public int food = 20;
    public float saturation = 5.0F;
    /** {@code MultiPlayerGameMode.localPlayerMode}; {@code null} before login set it. */
    public GameType localMode;
    /** Whether the connection has a {@code PlayerInfo} for the own profile. */
    public boolean infoPresent;
    /** Game mode of the connection entry; a detached player cache can hold a different mode. */
    public GameType infoMode = GameType.SURVIVAL;
    /** AbstractClientPlayer.playerInfo: null until a successful getPlayerInfo lookup. */
    public boolean infoCached;
    /** Whether the cached object is still the connection entry (updates mutate that same object). */
    public boolean infoCacheAttached;
    GameType cachedInfoMode = GameType.SURVIVAL;
    /** Rendering may have cached an entry before any modelled read; its removal leaves two possible identities. */
    public boolean infoIdentityUncertain;

    /** The mode a lookup would return, without causing the lookup (packet handlers do not read it). */
    public GameType peekPlayerInfoMode() {
        return infoCached ? (infoCacheAttached ? infoMode : cachedInfoMode) : infoPresent ? infoMode : null;
    }

    /** AbstractClientPlayer.getPlayerInfo: failed lookups are retried, successful ones retain object identity. */
    public GameType playerInfoMode() {
        if (!infoCached && infoPresent) {
            infoCached = true;
            infoCacheAttached = true;
        }
        return peekPlayerInfoMode();
    }

    /** Removing a map entry never clears AbstractClientPlayer.playerInfo. */
    public void removePlayerInfo() {
        if (infoCached && infoCacheAttached) {
            cachedInfoMode = infoMode;
            infoCacheAttached = false;
        } else if (!infoCached && infoPresent) {
            infoIdentityUncertain = true;
        }
        infoPresent = false;
    }

    /** A new LocalPlayer has a null cache; respawn keeps the connection map, login replaces it. */
    public void resetPlayerInfoCache() {
        infoCached = false;
        infoCacheAttached = false;
        cachedInfoMode = GameType.SURVIVAL;
        infoIdentityUncertain = false;
    }

    /**
     * The load gate ({@code hasClientLoaded}): false after {@code login}/{@code respawn} until the client finishes
     * loading the level and sends {@code player_loaded}; LocalPlayer ticks do not move or send until then.
     */
    public boolean clientLoaded;
    /** The LocalPlayer's inventory and inventory menu (a new player has a new one). */
    public CompensatedInventory inventory;
    /**
     * {@code MultiPlayerGameMode.carriedIndex}: the selected slot last sent with {@code set_carried_item}. A login
     * creates a new game mode (0); a respawn keeps it.
     */
    public int carriedIndex;
    /** Client actions of the current client tick; cleared at its {@code client_tick_end}. */
    public TickActions tick;
    /** {@code LocalPlayer.startedUsingItem} ({@code isUsingItem()}) and {@code usingItemHand}. */
    public boolean usingItem;
    public boolean usingOffHand;
    /** {@code LivingEntity.useItem}: the stack taken from the used hand ({@code EMPTY} when none). */
    public ItemStack useItem = ItemStack.EMPTY;
    /** Inventory slot and its version when {@link #useItem} was taken (the stack identity {@code completeUsingItem} checks). */
    public int useItemSlot = -1;
    public int useItemVersion;
    /** {@code LivingEntity.useItem} as the client reads it now (the live stack while its slot still holds it). */
    public ItemStack usedStack() {
        return ClientItemUse.useItem(this);
    }

    /** {@code LivingEntity.useItemRemaining} ({@code ClientItemUse.ticksUsing} gives {@code getTicksUsingItem()}). */
    public int useRemaining;
    /** {@code ItemCooldowns}: cooldown group → {start, end} on its clock, which {@code Player.tick} advances. */
    public final Map<String, int[]> cooldowns = new HashMap<>();
    public int cooldownClock;
    /**
     * {@link ru.minecomplex.mac.engine.Unmodeled#ITEM_USE_OUTCOME}: an undecided use may have changed the inventory
     * (until a full inventory content), and the cooldown groups it may have started with the clock tick each would end.
     */
    public boolean useOutcomeInventory;
    /**
     * {@code Player.attackStrengthTicker} between these bounds ({@link ClientAttack}): {@code attackTicksMin} counts from
     * the last event that may have reset it, {@code attackTicksMax} from the last one that surely did (a new player
     * starts at 0).
     */
    public int attackTicksMin, attackTicksMax;
    /** The item of {@code Player.lastItemInMainHand} ({@code ""}: empty), {@code null}: not known. */
    public String lastMainHandItem = "";
    /**
     * The client may still be using an item although the model is not (a completion of a stack an unplaced drop may
     * have emptied: vanilla keeps using the empty stack): {@code ITEM_USE_TIMING} until the server's flags or a release.
     */
    public boolean useMayContinue;
    /** {@link ru.minecomplex.mac.engine.Unmodeled#ITEM_USE_TIMING}: the client's use state may differ from the model's. */
    public boolean useUncertain;
    /** An undecided use (or completion) may have changed the food level: until the server's health packet. */
    public boolean useOutcomeFood;
    public final Map<String, Integer> uncertainCooldowns = new HashMap<>();

    public boolean useOutcomeOpen() {
        return useOutcomeInventory || useOutcomeFood || !uncertainCooldowns.isEmpty();
    }

    public ClientPlayerData() {
        inventory = new CompensatedInventory();
        tick = new TickActions();
    }

    /** For {@link #copy()}: the inventory and tick actions are copied there, not created here. */
    private ClientPlayerData(ClientPlayerData source) {
        inventory = source.inventory.copy();
        tick = source.tick.copy();
    }

    public ClientPlayerData copy() {
        ClientPlayerData copy = new ClientPlayerData(this);
        copy.entityId = entityId;
        copy.health = health;
        copy.food = food;
        copy.saturation = saturation;
        copy.localMode = localMode;
        copy.infoPresent = infoPresent;
        copy.infoMode = infoMode;
        copy.infoCached = infoCached;
        copy.infoCacheAttached = infoCacheAttached;
        copy.cachedInfoMode = cachedInfoMode;
        copy.infoIdentityUncertain = infoIdentityUncertain;
        copy.clientLoaded = clientLoaded;
        copy.carriedIndex = carriedIndex;
        copy.usingItem = usingItem;
        copy.usingOffHand = usingOffHand;
        copy.useItem = useItem;
        copy.useItemSlot = useItemSlot;
        copy.useItemVersion = useItemVersion;
        copy.useRemaining = useRemaining;
        cooldowns.forEach((group, times) -> copy.cooldowns.put(group, times.clone()));
        copy.cooldownClock = cooldownClock;
        copy.useOutcomeInventory = useOutcomeInventory;
        copy.attackTicksMin = attackTicksMin;
        copy.attackTicksMax = attackTicksMax;
        copy.lastMainHandItem = lastMainHandItem;
        copy.useMayContinue = useMayContinue;
        copy.useUncertain = useUncertain;
        copy.useOutcomeFood = useOutcomeFood;
        copy.uncertainCooldowns.putAll(uncertainCooldowns);
        return copy;
    }
}
