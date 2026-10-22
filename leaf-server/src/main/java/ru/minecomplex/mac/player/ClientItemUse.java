package ru.minecomplex.mac.player;

import net.minecraft.world.level.GameType;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import ru.minecomplex.mac.replica.CompensatedInventory;
import ru.minecomplex.mac.inventory.EquipmentSlot;
import ru.minecomplex.mac.inventory.item.ItemMechanics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.component.UseCooldown;
import net.minecraft.world.item.component.UseRemainder;
import ru.minecomplex.mac.inventory.item.ItemBehavior;
import net.minecraft.world.item.ItemStack;
import ru.minecomplex.mac.inventory.item.Stacks;
import ru.minecomplex.mac.inventory.item.UseKind;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Trig;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.listener.ClientPlayerActions;
import ru.minecomplex.mac.player.state.ClientPlayerData;

/**
 * The LocalPlayer's item use (inventory stage I4): {@code MultiPlayerGameMode.useItem} → {@code ItemStack.use} →
 * {@code Item.use} per item ({@link UseKind}) → {@code LocalPlayer.startUsingItem}; {@code releaseUsingItem};
 * {@code LivingEntity.updatingUsingItem} at the start of every {@code LivingEntity.tick}; the server's using flags
 * ({@code LocalPlayer.onSyncedDataUpdated}); the server's completion (entity event 9, {@code completeUsingItem});
 * {@code ItemCooldowns}. Movement reads {@link ClientPlayerData#usingItem} and the used stack's {@code use_effects}.
 *
 * <p>A riptide trident's release launches the player ({@code TridentItem.releaseUsing}: the push along the look, the
 * auto spin attack's ticks, the lift off the ground) when the spin strength the server's enchantments give is known and
 * whether the player is in water or rain is: in water, or out of it in a level without rain. Not simulated, reported
 * instead: a riptide launch the model cannot decide ({@link Unmodeled#RIPTIDE}); a use decided by the
 * client's aim or the weather, the charge a released crossbow loads ({@link Unmodeled#ITEM_USE_OUTCOME},
 * {@link Unmodeled#ITEM_USE_TIMING}); a use of a stack the model only guesses ({@link CompensatedInventory#unknown}); an
 * equip of a stack of several, a use remainder added to the inventory (unknown slots,
 * {@link Unmodeled#INVENTORY_CLIENT_ACTION}).
 */
public final class ClientItemUse {

    private static final int USE_FOREVER = 72000;

    private final ItemBehavior items;

    public ClientItemUse() {
        this.items = ItemBehavior.get();
    }

    /**
     * {@code getItemInHand}.
     */
    static ItemStack hand(ClientPlayerData data, boolean offHand) {
        int slot = data.inventory.handSlot(offHand);
        return slot < 0 ? ItemStack.EMPTY : data.inventory.item(slot);
    }

    /**
     * {@code LocalPlayer.startUsingItem}: a non-empty hand, not already using; {@code useItemRemaining} is its duration.
     */
    void start(ClientPlayerData data, boolean offHand) {
        ItemStack stack = hand(data, offHand);
        if (stack.isEmpty() || data.usingItem)
            return;
        take(data, offHand);
        data.useRemaining = useDuration(stack);
        data.usingItem = true;
        data.usingOffHand = offHand;
    }

    /**
     * {@code LocalPlayer.stopUsingItem} ({@code useItemRemaining} 0).
     */
    static void stop(ClientPlayerData data) {
        data.useItem = ItemStack.EMPTY;
        data.useItemSlot = -1;
        data.inventory.pin(-1);
        data.useRemaining = 0;
        data.usingItem = false;
    }

    /**
     * {@code getTicksUsingItem()}: the used stack's duration minus the remaining ticks, 0 when not using.
     */
    int ticksUsing(ClientPlayerData data) {
        return data.usingItem ? useDuration(useItem(data)) - data.useRemaining : 0;
    }

    /**
     * {@code useItem = getItemInHand(hand)}, remembering which stack object it is.
     */
    private static void take(ClientPlayerData data, boolean offHand) {
        int slot = data.inventory.handSlot(offHand);
        data.useItem = slot < 0 ? ItemStack.EMPTY : data.inventory.item(slot);
        data.useItemSlot = slot;
        data.useItemVersion = slot < 0 ? 0 : data.inventory.version(slot);
        data.inventory.pin(slot);
    }

    /**
     * {@code LivingEntity.useItem} as it is now: while its slot still holds the very stack object taken, that slot's
     * current state (a drop or a use shrinks the same object); else the stack as taken.
     */
    public static ItemStack useItem(ClientPlayerData data) {
        ItemStack object = data.inventory.object(data.useItemSlot, data.useItemVersion);
        return object != null ? object : data.useItem;
    }

    /**
     * An unplaced drop (through 26.2) may have emptied the used stack: then vanilla keeps using an empty stack, releases
     * and completes nothing, and compares the hand with an empty item. The use state is reported
     * ({@link Unmodeled#ITEM_USE_TIMING}, kept by {@code useMayContinue}) while this holds.
     */
    static boolean useItemMayBeEmpty(ClientPlayerData data) {
        ItemStack used = useItem(data);
        return data.usingItem && !used.isEmpty() && data.inventory.objectMayLose(data.useItemSlot, data.useItemVersion) >= Stacks.count(used);
    }

    /**
     * {@link #useItemMayBeEmpty} makes the use state uncertain.
     */
    public static void ambiguity(ClientPlayerData data, Set<Unmodeled> gaps) {
        if (useItemMayBeEmpty(data)) {
            data.useMayContinue = true;
            data.useUncertain = true;
        }
    }

    /**
     * {@link Unmodeled#ITEM_USE_TIMING} follows {@code useUncertain}.
     */
    public static void useGaps(ClientPlayerData data, Set<Unmodeled> gaps, Set<Unmodeled> resolves) {
        if (data.useUncertain) {
            gaps.add(Unmodeled.ITEM_USE_TIMING);
            resolves.remove(Unmodeled.ITEM_USE_TIMING);
        } else {
            resolves.add(Unmodeled.ITEM_USE_TIMING);
            gaps.remove(Unmodeled.ITEM_USE_TIMING);
        }
    }

    /**
     * The start of {@code LivingEntity.tick} ({@code updatingUsingItem}) with its uncertainty: when the model stops
     * and the client cannot continue, both have stopped.
     */
    static void livingTick(ClientPlayerData data, Set<Unmodeled> gaps) {
        tick(data);
        if (!data.usingItem && !data.useMayContinue)
            data.useUncertain = false;
        ambiguity(data, gaps);
        useGaps(data, gaps, EnumSet.noneOf(Unmodeled.class));
    }

    /**
     * {@code ItemStack.isSameItem}: the same item, ignoring count and components.
     */
    static boolean sameItem(ItemStack a, ItemStack b) {
        return a.isEmpty() ? b.isEmpty() : !b.isEmpty() && Stacks.key(a).equals(Stacks.key(b));
    }

    /**
     * {@code LivingEntity.updatingUsingItem} at the start of {@code LivingEntity.tick}: the same item in the used hand
     * keeps the use going ({@code updateUsingItem}: {@code onUseTick}, which changes nothing the model reads on the
     * client, and one tick less remaining; only the server completes it), another one stops it.
     */
    static void tick(ClientPlayerData data) {
        if (!data.usingItem)
            return;
        if (sameItem(hand(data, data.usingOffHand), useItem(data))) {
            take(data, data.usingOffHand);
            data.useRemaining--;
        } else {
            stop(data);
        }
    }

    /**
     * {@code ItemCooldowns.tick} (in {@code Player.tick}): the clock advances, expired cooldowns go; a cooldown an
     * undecided use may have started is certain once it would have ended.
     */
    static void tickCooldowns(ClientPlayerData data) {
        data.cooldownClock++;
        data.cooldowns.values().removeIf(times -> times[1] <= data.cooldownClock);
        data.uncertainCooldowns.values().removeIf(end -> end <= data.cooldownClock);
    }

    /**
     * {@code ItemCooldowns.getCooldownGroup}: the {@code use_cooldown} group, else the item itself.
     */
    String cooldownGroup(ItemStack stack) {
        UseCooldown cooldown = stack.getOrDefault(DataComponents.USE_COOLDOWN, null);
        return cooldown != null && cooldown.cooldownGroup().isPresent() ? cooldown.cooldownGroup().get().toString() : Stacks.key(stack);
    }

    boolean onCooldown(ClientPlayerData data, ItemStack stack) {
        int[] times = data.cooldowns.get(cooldownGroup(stack));
        return times != null && times[1] > data.cooldownClock;
    }

    /**
     * {@code ItemCooldowns.addCooldown(group, ticks)} (0 removes it, as the packet handler does): certain from now.
     */
    public static void addCooldown(ClientPlayerData data, String group, int ticks) {
        if (ticks == 0)
            data.cooldowns.remove(group);
        else
            data.cooldowns.put(group, new int[] { data.cooldownClock, data.cooldownClock + ticks });
        data.uncertainCooldowns.remove(group);
    }

    /**
     * A use, release or completion of a stack the model only guesses: the use state either way, and its inventory and
     * cooldown effects ({@link Unmodeled#ITEM_USE_OUTCOME}) until a full inventory content.
     */
    private static void guessedUse(ClientPlayerData data, Set<Unmodeled> gaps) {
        data.useOutcomeInventory = true;
        data.useMayContinue = true;
        data.useUncertain = true;
    }

    /**
     * The effects the model applied for a stack whose use the client may not have had: the inventory, the food it ate
     * and the use cooldown it started become uncertain ({@link Unmodeled#ITEM_USE_OUTCOME}).
     */
    private void uncertainEffects(ClientPlayerData data, ItemStack stack) {
        data.useOutcomeInventory = true;
        if (stack.getOrDefault(DataComponents.FOOD, null) != null)
            data.useOutcomeFood = true;
        UseCooldown cooldown = stack.getOrDefault(DataComponents.USE_COOLDOWN, null);
        if (cooldown != null) {
            String group = cooldownGroup(stack);
            int[] times = data.cooldowns.remove(group);
            maybeCooldown(data, group, times != null ? times[1] : data.cooldownClock + (int) (cooldown.seconds() * 20.0F));
        }
    }

    /**
     * A cooldown an undecided use may have started: the group stays uncertain until {@code end} or a certain one.
     */
    private static void maybeCooldown(ClientPlayerData data, String group, int end) {
        data.uncertainCooldowns.merge(group, end, Math::max);
    }

    /**
     * {@code MultiPlayerGameMode.useItem(player, hand)} after its {@code use_item} was sent: skipped on cooldown, else
     * {@code ItemStack.use}; a transformed held stack replaces the hand.
     *
     * @return what stays unresolved
     */
    public Set<Unmodeled> use(PlayerState p, ClientPlayerData data, boolean offHand) {
        return use(p, data, offHand, ClientPlayerActions.Target.UNKNOWN);
    }

    /**
     * {@link #use(PlayerState, ClientPlayerData, boolean)} in the client's world ({@code target}: whether it rains).
     */
    public Set<Unmodeled> use(PlayerState p, ClientPlayerData data, boolean offHand, ClientPlayerActions.Target target) {
        EnumSet<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class);
        ItemStack stack = hand(data, offHand);
        if (stack.isEmpty() || onCooldown(data, stack))
            return gaps;
        boolean infinite = p.abilities.instabuild;
        boolean instantUse = useDuration(stack) <= 0;
        int slot = data.inventory.handSlot(offHand);
        boolean wasUsing = data.usingItem;
        // the model only guesses this stack (its components): whatever its use does is uncertain
        boolean guessed = slot >= 0 && data.inventory.unknown(slot);
        // whether the use succeeded is known (an aimed use is not): the side effects of an instant one follow it
        boolean known = true;
        boolean success;
        switch(items.use(Stacks.key(stack))) {
            case BASE ->
                success = base(p, data, offHand, stack, gaps);
            case SPYGLASS, BUNDLE ->
                {
                    start(data, offHand);
                    success = true;
                }
            case BOW ->
                {
                    success = infinite || projectile(data, false);
                    if (success)
                        start(data, offHand);
                }
            case CROSSBOW ->
                {
                    if (ItemMechanics.charged(stack)) {
                        // performShooting: only the server shoots and clears the charge
                        success = true;
                    } else {
                        success = projectile(data, true) || infinite;
                        if (success)
                            start(data, offHand);
                    }
                }
            case TRIDENT ->
                {
                    // a spin attack's trident fails out of water and rain
                    Tri fails = and(spins(stack), not(waterOrRain(p, target)));
                    if (ItemMechanics.nextDamageWillBreak(stack) || fails == Tri.TRUE) {
                        success = false;
                    } else {
                        if (fails == Tri.EITHER && !data.usingItem)
                            data.useUncertain = true;
                        start(data, offHand);
                        success = true;
                    }
                }
            case INSTRUMENT ->
                {
                    if (ItemMechanics.instrumentSeconds(stack) == null) {
                        success = false;
                    } else {
                        start(data, offHand);
                        // an instrument outside the vanilla registry: its use duration (the cooldown) is not known
                        if (ItemMechanics.instrumentSeconds(stack) < 0)
                            maybeCooldown(data, cooldownGroup(stack), Integer.MAX_VALUE);
                        else
                            addCooldown(data, cooldownGroup(stack), (int) Math.floor(ItemMechanics.instrumentSeconds(stack) * 20.0F));
                        success = true;
                    }
                }
            case ENDER_EYE ->
                {
                    // PASS when aimed at an end portal frame (the client's hit result is not known)
                    if (!data.usingItem)
                        data.useUncertain = true;
                    start(data, offHand);
                    success = true;
                    known = false;
                }
            case THROWABLE ->
                {
                    // the throw is the server's; the client consumes one item too
                    if (!infinite)
                        data.inventory.modify(slot, Stacks.withCount(stack, Stacks.count(stack) - 1));
                    success = true;
                }
            case SUCCEED ->
                success = true;
            // the rocket is the server's (it consumes it)
            case FIREWORK ->
                success = p.fallFlying;
            case KNOWLEDGE_BOOK ->
                {
                    // one item consumed; FAIL without recipes (the recipes component is not modelled)
                    if (!infinite)
                        data.inventory.modify(slot, Stacks.withCount(stack, Stacks.count(stack) - 1));
                    success = false;
                    known = false;
                }
            case AIMED ->
                {
                    // the client's hit result (blocks, fluids, entities near the view) decides; the stack stays
                    success = false;
                    known = false;
                }
            case AIMED_TRANSFORM ->
                {
                    // a filled bottle or bucket, an emptied bucket, a placed block: the hit result decides
                    data.useOutcomeInventory = true;
                    success = false;
                    known = false;
                }
            default ->
                success = false;
        }
        if (guessed) {
            data.inventory.setUnknown(slot, true);
            guessedUse(data, gaps);
            known = false;
        }
        if (data.uncertainCooldowns.containsKey(cooldownGroup(stack))) {
            // an undecided use may have started this group's cooldown: this use may not have happened at all
            data.useOutcomeInventory = true;
            if (data.usingItem && !wasUsing)
                data.useUncertain = true;
            known = false;
        }
        if (instantUse) {
            // ItemStack.use: applyAfterUseComponentSideEffects on the held result of a use without duration
            if (known && success) {
                afterUse(p, data, slot, stack, gaps);
            } else if (!known) {
                if (stack.getOrDefault(DataComponents.USE_REMAINDER, null) != null && !infinite)
                    data.useOutcomeInventory = true;
                if (stack.getOrDefault(DataComponents.USE_COOLDOWN, null) != null) {
                    maybeCooldown(data, cooldownGroup(stack), data.cooldownClock + (int) (stack.getOrDefault(DataComponents.USE_COOLDOWN, null).seconds() * 20.0F));
                }
            }
        }
        if (data.useOutcomeOpen())
            gaps.add(Unmodeled.ITEM_USE_OUTCOME);
        return gaps;
    }

    /**
     * {@code Item.use}: consumable, else a swappable equippable, else blocks_attacks, else kinetic_weapon.
     */
    private boolean base(PlayerState p, ClientPlayerData data, boolean offHand, ItemStack stack, Set<Unmodeled> gaps) {
        if (stack.has(DataComponents.CONSUMABLE)) {
            // Consumable.startConsuming: canConsume (food needs hunger unless always edible or invulnerable)
            boolean canConsume = stack.getOrDefault(DataComponents.FOOD, null) == null || p.abilities.invulnerable || stack.getOrDefault(DataComponents.FOOD, null).canAlwaysEat() || data.food < 20;
            if (!canConsume)
                return false;
            if ((int) (stack.getOrDefault(DataComponents.CONSUMABLE, null).consumeSeconds() * 20.0F) > 0) {
                start(data, offHand);
            } else {
                consume(p, data, data.inventory.handSlot(offHand), stack);
            }
            return true;
        }
        if (ItemMechanics.equippableSlot(stack) != null && stack.getOrDefault(DataComponents.EQUIPPABLE, null).swappable()) {
            return equip(p, data, offHand, stack, gaps);
        }
        if (stack.has(DataComponents.BLOCKS_ATTACKS) || stack.has(DataComponents.KINETIC_WEAPON)) {
            start(data, offHand);
            return true;
        }
        return false;
    }

    /**
     * {@code Equippable.swapWithEquipmentSlot}: the held stack goes into its slot, the worn one into the hand.
     */
    private boolean equip(PlayerState p, ClientPlayerData data, boolean offHand, ItemStack held, Set<Unmodeled> gaps) {
        EquipmentSlot slot = EquipmentSlot.byName(ItemMechanics.equippableSlot(held));
        if (!ItemMechanics.equippableByPlayer(held))
            return false;
        int target = equipmentIndex(slot, data.inventory);
        int hand = data.inventory.handSlot(offHand);
        // the hand's own slot holds the very stack: isSameItemSameComponents
        if (target == hand) {
            if (ItemMechanics.enchantmentLevel(held, "minecraft:binding_curse") > 0) {
                // the binding predicate reads isCreative before the same-stack rejection
                data.playerInfoMode();
            }
            return false;
        }
        if (target < 0) {
            // BODY/SADDLE: Player.setItemSlot ignores them; isSameItemSameComponents(held, EMPTY) is false
            if (Stacks.count(held) <= 1)
                data.playerInfoMode();
            return true;
        }
        ItemStack worn = data.inventory.item(target);
        // Equippable short-circuits isCreative unless binding blocks the swap, or a single item is being copied.
        if (ItemMechanics.enchantmentLevel(worn, "minecraft:binding_curse") > 0 && data.playerInfoMode() != GameType.CREATIVE) {
            return false;
        }
        if (!worn.isEmpty() && ItemStack.isSameItemSameComponents(held, worn)) {
            return false;
        }
        if (Stacks.count(held) <= 1) {
            boolean creative = data.playerInfoMode() == GameType.CREATIVE;
            // the hand gets the worn stack; with nothing worn it keeps the held one only in creative (copy vs copyAndClear)
            data.inventory.replace(target, held);
            data.inventory.replace(hand, !worn.isEmpty() ? worn : creative ? held : ItemStack.EMPTY);
        } else {
            // the worn stack is added to the inventory (or dropped): not simulated
            // consumeAndReturn(1): shrinks unless the player has infinite materials (the instabuild ability)
            data.inventory.replace(target, Stacks.withCount(held, 1));
            data.inventory.modify(hand, Stacks.withCount(held, p.abilities.instabuild ? Stacks.count(held) : Stacks.count(held) - 1));
            // Inventory.add of the worn stack (max_stack_size and full component equality decide where)
            if (!worn.isEmpty())
                data.inventory.markAllUnknown();
        }
        return true;
    }

    private static int equipmentIndex(EquipmentSlot slot, CompensatedInventory inventory) {
        return switch(slot) {
            case FEET ->
                CompensatedInventory.FEET;
            case LEGS ->
                CompensatedInventory.LEGS;
            case CHEST ->
                CompensatedInventory.CHEST;
            case HEAD ->
                CompensatedInventory.HEAD;
            case OFFHAND ->
                CompensatedInventory.OFFHAND;
            case MAINHAND ->
                inventory.handSlot(false);
            case BODY, SADDLE ->
                -1;
        };
    }

    /**
     * {@code Consumable.onConsume} on the client: food eaten ({@code FoodData.eat}), one item used unless the player
     * has infinite materials ({@code ItemStack.consume}, shrinking the same stack).
     */
    private void consume(PlayerState p, ClientPlayerData data, int slot, ItemStack stack) {
        if (stack.getOrDefault(DataComponents.FOOD, null) != null)
            eat(data, stack.getOrDefault(DataComponents.FOOD, null));
        if (!p.abilities.instabuild)
            data.inventory.modify(slot, Stacks.withCount(stack, Stacks.count(stack) - 1));
    }

    /**
     * {@code ItemStack.applyAfterUseComponentSideEffects(user, before)} applied to what the hand slot now holds:
     * {@code UseRemainder.convertIntoRemainder} when the use took items (not with infinite materials) turns an emptied
     * stack into the remainder, else adds the remainder to the inventory or drops it (not simulated); the
     * {@code use_cooldown} starts.
     */
    private void afterUse(PlayerState p, ClientPlayerData data, int slot, ItemStack before, Set<Unmodeled> gaps) {
        UseRemainder remainder = before.getOrDefault(DataComponents.USE_REMAINDER, null);
        ItemStack result = data.inventory.item(slot);
        if (remainder != null && !p.abilities.instabuild && Stacks.count(result) < Stacks.count(before)) {
            if (result.isEmpty()) {
                // a copy of the remainder stack: its prototype (vanilla remainders carry no patch)
                data.inventory.replace(slot, remainder.convertInto().create());
            } else {
                // handleExtraItemsCreatedOnUse: added to the inventory or dropped
                data.inventory.markAllUnknown();
            }
        }
        if (before.getOrDefault(DataComponents.USE_COOLDOWN, null) != null)
            addCooldown(data, cooldownGroup(before), (int) (before.getOrDefault(DataComponents.USE_COOLDOWN, null).seconds() * 20.0F));
    }

    /**
     * {@code FoodData.eat(FoodProperties)}: {@code add(nutrition, saturation)} clamped to [0, 20] and [0, food].
     */
    static void eat(ClientPlayerData data, FoodProperties food) {
        data.food = Math.min(Math.max(food.nutrition() + data.food, 0), 20);
        float saturation = food.saturation() + data.saturation;
        data.saturation = saturation < 0.0F ? 0.0F : Math.min(saturation, (float) data.food);
    }

    /**
     * {@code ProjectileWeaponItem}: a held projectile (offhand first, then main hand) of the weapon's held kinds — a crossbow
     * also takes firework rockets — else any arrow in the inventory.
     */
    private boolean projectile(ClientPlayerData data, boolean crossbow) {
        return projectileStack(data, crossbow) != null;
    }

    /**
     * The stack {@link #projectile} finds, {@code null} when none.
     */
    private ItemStack projectileStack(ClientPlayerData data, boolean crossbow) {
        for (boolean offHand : new boolean[] { true, false }) {
            ItemStack held = hand(data, offHand);
            if (!held.isEmpty() && (items.arrow(Stacks.key(held)) || crossbow && Stacks.is(held, "minecraft:firework_rocket")))
                return held;
        }
        for (int slot = 0; slot < CompensatedInventory.SIZE; slot++) {
            ItemStack stack = data.inventory.item(slot);
            if (!stack.isEmpty() && items.arrow(Stacks.key(stack)))
                return stack;
        }
        return null;
    }

    /**
     * {@code getUseDuration}: the classes' constants, else consumable ticks, else 72000 for blocking or kinetic items.
     */
    int useDuration(ItemStack stack) {
        return switch(items.use(Stacks.key(stack))) {
            case BOW, TRIDENT ->
                USE_FOREVER;
            case SPYGLASS ->
                1200;
            case BUNDLE ->
                200;
            case INSTRUMENT ->
                ItemMechanics.instrumentSeconds(stack) == null || ItemMechanics.instrumentSeconds(stack) < 0 ? USE_FOREVER : (int) (ItemMechanics.instrumentSeconds(stack) * 20.0F);
            case CROSSBOW ->
                USE_FOREVER;
            default ->
                stack.has(DataComponents.CONSUMABLE) ? (int) (stack.getOrDefault(DataComponents.CONSUMABLE, null).consumeSeconds() * 20.0F) : stack.has(DataComponents.BLOCKS_ATTACKS) || stack.has(DataComponents.KINETIC_WEAPON) ? USE_FOREVER : 0;
        };
    }

    /**
     * {@code CrossbowItem.getChargeDuration}: {@code floor(modifyCrossbowChargingTime(1.25) * 20)}; the vanilla
     * {@code quick_charge} adds {@code LevelBasedValue.perLevel(-0.25)} ({@code base + perLevelAboveFirst * (level - 1)}).
     */
    int chargeDuration(ItemStack stack) {
        int level = ItemMechanics.enchantmentLevel(stack, "minecraft:quick_charge");
        float time = 1.25F;
        if (level > 0)
            time = time + (-0.25F + -0.25F * (level - 1));
        return (int) Math.floor(Math.max(0.0F, time) * 20.0F);
    }

    /**
     * {@code CrossbowItem.getPowerForTime}: ticks held over the charge duration (float), at most 1.
     */
    private float crossbowPower(int ticks, ItemStack stack) {
        float power = (float) ticks / chargeDuration(stack);
        return power > 1.0F ? 1.0F : power;
    }

    /**
     * {@code BowItem.getPowerForTime}.
     */
    private static float bowPower(int ticks) {
        float power = ticks / 20.0F;
        power = (power * power + power * 2.0F) / 3.0F;
        return power > 1.0F ? 1.0F : power;
    }

    /**
     * {@code LivingEntity.releaseUsingItem}: from 1.21.5 only for the same item in the used hand, which becomes the
     * used stack, before for the stack taken; its {@code ItemStack.releaseUsing} applies the use's side effects when the
     * item's {@code releaseUsing} returns true; the use stops. A riptide trident launches the player client-side.
     */
    public Set<Unmodeled> release(PlayerState p, ClientPlayerData data) {
        return release(p, data, ClientPlayerActions.Target.UNKNOWN);
    }

    /**
     * {@link #release(PlayerState, ClientPlayerData)} in the client's world ({@code target}: rain, the riptide's lift).
     */
    public Set<Unmodeled> release(PlayerState p, ClientPlayerData data, ClientPlayerActions.Target target) {
        EnumSet<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class);
        ItemStack held = hand(data, data.usingOffHand);
        ItemStack used = useItem(data);
        boolean uncertain = data.useUncertain;
        boolean releases = !used.isEmpty() && (sameItem(held, used));
        if (releases) {
            take(data, data.usingOffHand);
            int slot = data.useItemSlot >= 0 && data.inventory.version(data.useItemSlot) == data.useItemVersion ? data.useItemSlot : -1;
            ItemStack stack = useItem(data);
            if (slot >= 0 && data.inventory.unknown(slot))
                guessedUse(data, gaps);
            Boolean released = releaseUsing(p, data, stack, slot, gaps, target);
            if (uncertain && released != Boolean.FALSE) {
                // the client may not have been using it
                released = null;
                if (items.use(Stacks.key(stack)) == UseKind.CROSSBOW)
                    data.useOutcomeInventory = true;
            }
            UseCooldown cooldown = stack.getOrDefault(DataComponents.USE_COOLDOWN, null);
            // applyAfterUseComponentSideEffects: no release takes items on the client, so only the use cooldown
            if (cooldown != null && released == null) {
                maybeCooldown(data, cooldownGroup(stack), data.cooldownClock + (int) (cooldown.seconds() * 20.0F));
            } else if (cooldown != null && released) {
                addCooldown(data, cooldownGroup(stack), (int) (cooldown.seconds() * 20.0F));
            }
        }
        stop(data);
        // after a release neither uses an item
        data.useMayContinue = false;
        data.useUncertain = false;
        if (data.useOutcomeOpen())
            gaps.add(Unmodeled.ITEM_USE_OUTCOME);
        return gaps;
    }

    /**
     * {@code Item.releaseUsing} on the client for the used {@code stack} (in inventory {@code slot}, or -1 for a stack
     * no longer there): true, false, or null when the model cannot decide.
     */
    private Boolean releaseUsing(PlayerState p, ClientPlayerData data, ItemStack stack, int slot, Set<Unmodeled> gaps, ClientPlayerActions.Target target) {
        int ticks = ticksUsing(data);
        boolean infinite = p.abilities.instabuild;
        return switch(items.use(Stacks.key(stack))) {
            // a projectile to draw (the client consumes none) and at least a tenth of the power
            case BOW ->
                (infinite || projectile(data, false)) && (double) bowPower(ticks) >= 0.1;
            case CROSSBOW ->
                {
                    boolean full = crossbowPower(ticks, stack) >= 1.0F;
                    yield full && ItemMechanics.charged(stack);
                }
            case SPYGLASS ->
                true;
            case TRIDENT ->
                {
                    if (ticks < 10)
                        yield false;
                    Tri spins = spins(stack);
                    Tri away = p.passenger ? Tri.TRUE : not(waterOrRain(p, target));
                    // without a spin attack strength the client throws nothing (the server does)
                    Tri launches = and(spins, not(away));
                    if (launches == Tri.FALSE || ItemMechanics.nextDamageWillBreak(stack))
                        yield false;
                    // a use the client may not have had: whether it launched is not known
                    if (launches == Tri.EITHER || data.useUncertain) {
                        p.riptideUnknownCause = Float.isNaN(ItemMechanics.spinAttackStrength(stack)) ? "неизвестна сила рывка трезубца" : launches == Tri.EITHER ? "неизвестен дождь в точке отпускания трезубца" : "неизвестно, начал ли клиент зарядку трезубца";
                        gaps.add(Unmodeled.RIPTIDE);
                        yield null;
                    }
                    launch(p, ItemMechanics.spinAttackStrength(stack), target, gaps);
                    yield true;
                }
            default ->
                false;
        };
    }

    /**
     * {@code TridentItem.releaseUsing}'s spin attack: {@code push} along the look scaled to {@code strength},
     * {@code startAutoSpinAttack(20, …)} (the client keeps only the ticks; the pose follows the server's flag), and on the
     * ground {@code move(SELF, (0, 1.1999999F, 0))}.
     */
    private void launch(PlayerState p, float strength, ClientPlayerActions.Target target, Set<Unmodeled> gaps) {
        float yRot = p.yRot, xRot = p.xRot;
        float x = -Trig.sin(yRot * (float) (Math.PI / 180.0)) * Trig.cos(xRot * (float) (Math.PI / 180.0));
        float y = -Trig.sin(xRot * (float) (Math.PI / 180.0));
        float z = Trig.cos(yRot * (float) (Math.PI / 180.0)) * Trig.cos(xRot * (float) (Math.PI / 180.0));
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        x *= strength / length;
        y *= strength / length;
        z *= strength / length;
        p.deltaMovement = p.deltaMovement.add(x, y, z);
        p.autoSpinAttackTicks = 20;
        p.autoSpinAttackDamage = 8.0F;
        p.autoSpinMayHaveEnded = false;
        p.riptideUnknownCause = "";
        if (p.onGround)
            target.moveSelf(p, new Vec3d(0.0, 1.1999999F, 0.0), gaps);
    }

    /**
     * {@code getTridentSpinAttackStrength(stack) > 0}: {@code EITHER} where the server's enchantments did not tell it.
     */
    private static Tri spins(ItemStack stack) {
        float strength = ItemMechanics.spinAttackStrength(stack);
        return Float.isNaN(strength) ? Tri.EITHER : strength > 0.0F ? Tri.TRUE : Tri.FALSE;
    }

    /**
     * {@code isInWaterOrRain}: {@code isInWater} ({@code wasTouchingWater}), or rain at the player's block or at the top
     * of its box ({@link ClientPlayerActions.Target#inRain}).
     */
    private static Tri waterOrRain(PlayerState p, ClientPlayerActions.Target target) {
        if (p.wasTouchingWater)
            return Tri.TRUE;
        return target.inRain(p);
    }

    private static Tri not(Tri value) {
        return value == Tri.TRUE ? Tri.FALSE : value == Tri.FALSE ? Tri.TRUE : Tri.EITHER;
    }

    private static Tri and(Tri a, Tri b) {
        if (a == Tri.FALSE || b == Tri.FALSE)
            return Tri.FALSE;
        return a == Tri.TRUE && b == Tri.TRUE ? Tri.TRUE : Tri.EITHER;
    }

    /**
     * {@code LocalPlayer.onSyncedDataUpdated(DATA_LIVING_ENTITY_FLAGS)} after {@code LivingEntity}'s: bit 0 starts the
     * use of the hand bit 1 names when not using, a clear bit 0 stops it.
     */
    public void flags(ClientPlayerData data, byte flags) {
        boolean using = (flags & 1) != 0;
        boolean offHand = (flags & 2) != 0;
        data.useMayContinue = false;
        data.useUncertain = false;
        // LivingEntity.onSyncedDataUpdated keeps useItem consistent with isUsingItem() first (useItemRemaining restarts)
        if (data.usingItem && useItem(data).isEmpty()) {
            take(data, data.usingOffHand);
            if (!data.useItem.isEmpty())
                data.useRemaining = useDuration(data.useItem);
        } else if (!data.usingItem && !useItem(data).isEmpty()) {
            data.useItem = ItemStack.EMPTY;
            data.useItemSlot = -1;
            data.inventory.pin(-1);
        }
        if (using && !data.usingItem)
            start(data, offHand);
        else if (!using && data.usingItem)
            stop(data);
    }

    /**
     * {@code Player.handleEntityEvent(9)} → {@code completeUsingItem}: the hand still holds the very stack taken →
     * {@code finishUsingItem} (a consumable is consumed); another stack → {@code releaseUsingItem}.
     */
    public Set<Unmodeled> complete(PlayerState p, ClientPlayerData data) {
        EnumSet<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class);
        if (!data.usingItem) {
            if (data.useUncertain) {
                // the client may be using: its completion would change the inventory, the food and a cooldown
                data.useOutcomeInventory = true;
                data.useOutcomeFood = true;
                gaps.add(Unmodeled.ITEM_USE_OUTCOME);
            }
            return gaps;
        }
        // with the use uncertain (the client may not be using it) the completion's effects are uncertain too
        boolean uncertain = data.useUncertain;
        int slot = data.inventory.handSlot(data.usingOffHand);
        boolean same = slot >= 0 && slot == data.useItemSlot && data.inventory.version(slot) == data.useItemVersion;
        if (!same) {
            gaps.addAll(release(p, data));
            return gaps;
        }
        if (!useItem(data).isEmpty()) {
            if (data.inventory.mayLose(slot) > 0) {
                // an unplaced drop may have emptied the very stack: then nothing completes and the client keeps using it
                data.useMayContinue = true;
                data.useUncertain = true;
                uncertain = true;
            }
            if (data.inventory.unknown(slot)) {
                guessedUse(data, gaps);
                uncertain = true;
            }
            // ItemStack.finishUsingItem: Item.finishUsingItem (a consumable is consumed; SpyglassItem overrides it and
            // only stops), then the side effects
            ItemStack stack = data.inventory.item(slot);
            if (stack.has(DataComponents.CONSUMABLE) && items.use(Stacks.key(stack)) != UseKind.SPYGLASS)
                consume(p, data, slot, stack);
            afterUse(p, data, slot, stack, gaps);
            if (uncertain)
                uncertainEffects(data, stack);
            stop(data);
        }
        return gaps;
    }
}
