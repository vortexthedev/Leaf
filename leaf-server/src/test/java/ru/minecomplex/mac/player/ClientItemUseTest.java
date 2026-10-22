package ru.minecomplex.mac.player;

import org.junit.jupiter.api.Test;

import net.minecraft.world.level.GameType;
import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import ru.minecomplex.mac.replica.CompensatedInventory;
import ru.minecomplex.mac.fixture.ItemComponentSample;
import ru.minecomplex.mac.inventory.item.ItemBehavior;
import net.minecraft.world.item.ItemStack;
import ru.minecomplex.mac.inventory.item.Stacks;
import ru.minecomplex.mac.fixture.FixtureItems;
import ru.minecomplex.mac.fixture.WireItem;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.fixture.inbound.Attack;
import ru.minecomplex.mac.fixture.inbound.InteractEntity;
import ru.minecomplex.mac.fixture.inbound.PlayerAction;
import ru.minecomplex.mac.fixture.inbound.UseItem;
import ru.minecomplex.mac.fixture.inbound.UseItemOn;
import ru.minecomplex.mac.fixture.outbound.ContainerContent;
import ru.minecomplex.mac.fixture.outbound.ItemCooldown;
import ru.minecomplex.mac.listener.ClientPlayerActions;
import ru.minecomplex.mac.listener.ClientPlayerHandlers;
import ru.minecomplex.mac.player.state.ClientPlayerData;

/**
 * Item uses the MAPS oracle cannot generate (the headless client has no level to aim into, no enchantment registry):
 * undecided outcomes and the uncertain cooldowns they leave.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ClientItemUseTest {

    static {
        ru.minecomplex.mac.packet.NativeRuntime.ensure();
    }

    private static final ClientPlayerActions P774 = new ClientPlayerActions();

    private static final ClientPlayerHandlers HANDLERS = new ClientPlayerHandlers();

    private static final ItemBehavior ITEMS = ItemBehavior.get();

    private static final UseItem USE_MAIN = new UseItem(false, 1, 0.0F, 0.0F);

    private static final PlayerAction RELEASE = PlayerAction.of(PlayerAction.Action.RELEASE_USE_ITEM);

    private static ItemStack stack(String item, int count) {
        return FixtureItems.stack(WireItem.of(item, count));
    }

    private static ItemStack withComponents(ItemStack stack, ItemComponentSample.UseCooldown cooldown, ItemComponentSample.Enchantments enchantments) {
        ItemComponentSample c = FixtureItems.components(stack);
        return FixtureItems.resolved(Stacks.key(stack), Stacks.count(stack), new ItemComponentSample(c.glider(), c.unbreakable(), c.equippableSlot(), c.swappable(), c.equippableByPlayer(), c.maxDamage(), c.damage(), c.useEffects(), c.consumeSeconds(), c.food(), c.blocksAttacks(), c.kineticWeapon(), c.charged(), c.instrumentSeconds(), cooldown, enchantments, c.useRemainder()));
    }

    private static ClientPlayerData holding(ItemStack mainHand) {
        ClientPlayerData data = new ClientPlayerData();
        data.inventory.setItem(0, mainHand);
        return data;
    }

    private static Set<Unmodeled> use(PlayerState p, ClientPlayerData data) {
        return P774.apply(p, data, Wire.of(USE_MAIN)).gaps();
    }

    @Test
    void useItemProvesTheTridentSurvivedThePrecedingBlockInteraction() {
        PlayerState p = new PlayerState();
        p.wasTouchingWater = true;
        ClientPlayerData data = holding(withComponents(stack("minecraft:trident", 1), null, new ItemComponentSample.Enchantments(true, false, 0, 3.0F)));
        ClientPlayerActions.Target leaves = new ClientPlayerActions.Target() {

            public String block(int x, int y, int z) {
                return "minecraft:oak_leaves";
            }

            public String entityType(int entityId) {
                return null;
            }
        };
        assertTrue(P774.apply(p, data, Wire.of(new UseItemOn(false, 1, 4, 20, 1)), leaves).gaps().contains(Unmodeled.DROP_SLOT));
        assertEquals(0, data.inventory.minCount(0));
        Set<Unmodeled> gaps = use(p, data);
        assertFalse(gaps.contains(Unmodeled.DROP_SLOT));
        assertFalse(gaps.contains(Unmodeled.ITEM_USE_TIMING));
        assertEquals(1, data.inventory.minCount(0));
        for (int tick = 0; tick < 12; tick++) ClientItemUse.tick(data);
        assertFalse(P774.apply(p, data, Wire.of(RELEASE)).gaps().contains(Unmodeled.RIPTIDE));
        assertEquals(20, p.autoSpinAttackTicks);
    }

    @Test
    void nonEmptyUseEvidenceRetainsCountAndComponentUncertainty() {
        ClientPlayerData data = holding(stack("minecraft:shield", 5));
        data.inventory.setHotbarBounds(0, 0, 5);
        use(new PlayerState(), data);
        assertEquals(1, data.inventory.minCount(0));
        assertEquals(5, Stacks.count(data.inventory.item(0)));
        ClientPlayerData unknown = holding(stack("minecraft:shield", 1));
        unknown.inventory.setHotbarBounds(0, 0, 1);
        unknown.inventory.setUnknown(0, true);
        assertTrue(use(new PlayerState(), unknown).contains(Unmodeled.ITEM_USE_TIMING));
        assertEquals(0, unknown.inventory.minCount(0));
        assertTrue(unknown.inventory.unknown(0));
    }

    @Test
    void throwablesTakeOneItemExceptWithInfiniteMaterials() {
        PlayerState p = new PlayerState();
        ClientPlayerData data = holding(stack("minecraft:snowball", 5));
        assertEquals(Set.of(), use(p, data));
        assertEquals(4, Stacks.count(data.inventory.item(0)));
        p.abilities.instabuild = true;
        use(p, data);
        assertEquals(4, Stacks.count(data.inventory.item(0)));
        assertFalse(data.usingItem);
    }

    @Test
    void anAimedTransformLeavesTheInventoryUnknownUntilAFullContent() {
        PlayerState p = new PlayerState();
        LocalPlayerState local = new LocalPlayerState();
        ClientPlayerData data = holding(stack("minecraft:bucket", 1));
        assertEquals(Set.of(Unmodeled.ITEM_USE_OUTCOME), use(p, data));
        assertEquals("minecraft:bucket", Stacks.key(data.inventory.item(0)), "the hit result decides; the model keeps its stack");
        // a partial content does not resolve it, the inventory menu's whole content does
        List<WireItem> menu = new ArrayList<>(Collections.nCopies(CompensatedInventory.MENU_SIZE, WireItem.EMPTY));
        ClientPlayerHandlers.Result result = HANDLERS.apply(p, local, data, Wire.of(ContainerContent.of(0, 1, menu, WireItem.EMPTY)));
        assertTrue(result.resolves().contains(Unmodeled.ITEM_USE_OUTCOME));
        assertFalse(data.useOutcomeOpen());
    }

    @Test
    void anAimedUseMayHaveStartedItsCooldown() {
        PlayerState p = new PlayerState();
        LocalPlayerState local = new LocalPlayerState();
        ItemStack boat = withComponents(stack("minecraft:oak_boat", 1), new ItemComponentSample.UseCooldown(1.0F, "mac:group"), null);
        ClientPlayerData data = holding(boat);
        assertEquals(Set.of(Unmodeled.ITEM_USE_OUTCOME), use(p, data));
        assertEquals(Set.of("mac:group"), data.uncertainCooldowns.keySet());
        assertTrue(data.cooldowns.isEmpty(), "not certain either way");
        assertFalse(data.useOutcomeInventory, "a boat or spawn egg does not change the stack on the client");
        // a use of the same group may not have happened at all: a started use is uncertain too
        ItemStack shield = withComponents(stack("minecraft:shield", 1), new ItemComponentSample.UseCooldown(1.0F, "mac:group"), null);
        data.inventory.setItem(0, shield);
        assertEquals(Set.of(Unmodeled.ITEM_USE_OUTCOME, Unmodeled.ITEM_USE_TIMING), use(p, data));
        assertTrue(data.usingItem);
        P774.apply(p, data, Wire.of(RELEASE));
        // the server's cooldown for that group makes it certain
        ClientPlayerHandlers.Result result = HANDLERS.apply(p, local, data, Wire.of(new ItemCooldown("mac:group", 20)));
        assertTrue(data.uncertainCooldowns.isEmpty());
        assertEquals(1, data.cooldowns.size());
        assertTrue(data.useOutcomeInventory, "the shield's use is still undecided");
        assertFalse(result.resolves().contains(Unmodeled.ITEM_USE_OUTCOME));
    }

    @Test
    void anUncertainCooldownEndsWhenItWouldHaveEnded() {
        PlayerState p = new PlayerState();
        ItemStack egg = withComponents(stack("minecraft:pig_spawn_egg", 1), new ItemComponentSample.UseCooldown(0.25F, null), null);
        ClientPlayerData data = holding(egg);
        use(p, data);
        assertEquals(5, (int) data.uncertainCooldowns.get(Stacks.key(egg)));
        for (int tick = 0; tick < 4; tick++) ClientItemUse.tickCooldowns(data);
        assertTrue(data.useOutcomeOpen());
        ClientItemUse.tickCooldowns(data);
        assertFalse(data.useOutcomeOpen());
    }

    @Test
    void aKnowledgeBookIsConsumedWhetherOrNotItHasRecipes() {
        PlayerState p = new PlayerState();
        ClientPlayerData data = holding(stack("minecraft:knowledge_book", 2));
        assertEquals(Set.of(), use(p, data), "without a remainder or a cooldown the outcome changes nothing");
        assertEquals(1, Stacks.count(data.inventory.item(0)));
        data.inventory.setItem(0, withComponents(stack("minecraft:knowledge_book", 1), new ItemComponentSample.UseCooldown(1.0F, null), null));
        assertEquals(Set.of(Unmodeled.ITEM_USE_OUTCOME), use(p, data));
        assertTrue(data.inventory.item(0).isEmpty());
    }

    @Test
    void aFireworkSucceedsOnlyWhileFallFlying() {
        PlayerState p = new PlayerState();
        ItemStack rocket = withComponents(stack("minecraft:firework_rocket", 3), new ItemComponentSample.UseCooldown(1.0F, null), null);
        ClientPlayerData data = holding(rocket);
        use(p, data);
        assertTrue(data.cooldowns.isEmpty(), "PASS: no side effects");
        p.fallFlying = true;
        use(p, data);
        assertEquals(3, Stacks.count(data.inventory.item(0)), "only the server consumes it");
        assertEquals(1, data.cooldowns.size());
    }

    /**
     * A riptide trident's use and release where the oracle (MART) cannot go: a level in rain the target does not decide, a spin strength the server's registry did not tell, a passenger. The known launch pushes along
     * the look and lifts a player on the ground through the world.
     */
    @Test
    void aRiptideLaunchIsDecidedOnlyWhereItsFactsAreKnown() {
        boolean[] raining = { false };
        List<double[]> lifts = new ArrayList<>();
        ClientPlayerActions.Target world = new ClientPlayerActions.Target() {

            @Override
            public String block(int x, int y, int z) {
                return null;
            }

            @Override
            public String entityType(int entityId) {
                return null;
            }

            @Override
            public ru.minecomplex.mac.util.Tri raining() {
                return raining[0] ? ru.minecomplex.mac.util.Tri.EITHER : ru.minecomplex.mac.util.Tri.FALSE;
            }

            @Override
            public void moveSelf(PlayerState player, ru.minecomplex.mac.util.math.Vec3d delta, Set<Unmodeled> gaps) {
                lifts.add(new double[] { delta.x(), delta.y(), delta.z() });
            }
        };
        for (float strength : new float[] { 3.0F }) {
            for (int place = 0; place < 4; place++) {
                ClientPlayerActions actions = new ClientPlayerActions();
                ItemBehavior items = ItemBehavior.get();
                raining[0] = place == 2;
                lifts.clear();
                PlayerState p = new PlayerState();
                p.wasTouchingWater = place == 0 || place == 3;
                p.onGround = place == 0;
                p.passenger = place == 3;
                p.yRot = 90.0F;
                // a spin before that may have ended: a launch starts a known one
                p.autoSpinMayHaveEnded = true;
                ClientPlayerData data = new ClientPlayerData();
                data.inventory.setItem(0, withComponents(FixtureItems.stack(WireItem.of("minecraft:trident", 1)), null, new ItemComponentSample.Enchantments(true, false, 0, strength)));
                Set<Unmodeled> useGaps = actions.apply(p, data, Wire.of(USE_MAIN), world).gaps();
                String context = "strength " + strength + " place " + place;
                boolean knownDry = place == 1 && !Float.isNaN(strength);
                // out of water and rain a riptide's use fails: known only for a known strength in a dry level
                assertEquals(!knownDry, data.usingItem, context);
                if (!data.usingItem)
                    continue;
                for (int tick = 0; tick < 12; tick++) ClientItemUse.tick(data);
                Set<Unmodeled> gaps = actions.apply(p, data, Wire.of(RELEASE), world).gaps();
                boolean passengerFails = place == 3;
                boolean launches = !Float.isNaN(strength) && place != 1 && place != 2 && !passengerFails;
                boolean unknown = !passengerFails && place != 1 && (Float.isNaN(strength) || place == 2);
                assertEquals(unknown, gaps.contains(Unmodeled.RIPTIDE), context + " " + useGaps + gaps);
                assertEquals(launches ? 20 : 0, p.autoSpinAttackTicks, context);
                if (launches)
                    assertFalse(p.autoSpinMayHaveEnded, context);
                if (launches) {
                    // yaw 90 looks along -x: the push is the strength along it
                    assertEquals(-3.0, p.deltaMovement.x(), 1.0E-6, context);
                    assertEquals(place == 0 ? 1 : 0, lifts.size(), context);
                    if (place == 0)
                        assertEquals(1.1999999284744263, lifts.getFirst()[1], 0.0, context);
                }
            }
        }
    }

    @Test
    void interactionsReportWhatTheClientWorldDecides() {
        ClientPlayerActions.Target world = new ClientPlayerActions.Target() {

            @Override
            public String block(int x, int y, int z) {
                return y == 64 ? "minecraft:potted_dandelion" : "minecraft:stone";
            }

            @Override
            public String entityType(int entityId) {
                return entityId == 5 ? "minecraft:cow" : "minecraft:allay";
            }
        };
        PlayerState p = new PlayerState();
        ClientPlayerData data = holding(stack("minecraft:stone", 3));
        // placing takes at most one item from the hand
        assertEquals(Set.of(), P774.apply(p, data, Wire.of(new UseItemOn(false, 2, 0, 70, 0)), world).gaps());
        assertEquals(1, data.inventory.mayLose(0));
        assertFalse(data.inventory.uncertain());
        // a filled flower pot may add its plant anywhere; an unknown block might be one
        assertEquals(Set.of(Unmodeled.INVENTORY_CLIENT_ACTION), P774.apply(p, data, Wire.of(new UseItemOn(false, 3, 0, 64, 0)), world).gaps());
        data = holding(stack("minecraft:stone", 3));
        assertEquals(Set.of(Unmodeled.INVENTORY_CLIENT_ACTION), P774.apply(p, data, Wire.of(new UseItemOn(false, 3, 0, 70, 0))).gaps());
        // a cow: feeding takes at most one; a bucket fills (several: the milk bucket goes elsewhere)
        data = holding(stack("minecraft:wheat", 3));
        assertEquals(Set.of(), P774.apply(p, data, Wire.of(new InteractEntity(5, false, false)), world).gaps());
        assertEquals(1, data.inventory.mayLose(0));
        data = holding(stack("minecraft:bucket", 1));
        assertEquals(Set.of(Unmodeled.INVENTORY_CLIENT_ACTION), P774.apply(p, data, Wire.of(new InteractEntity(5, false, false)), world).gaps());
        assertTrue(data.inventory.unknown(0));
        assertFalse(data.inventory.unknown(1));
        // an allay hands its item to an empty main hand
        data = holding(ItemStack.EMPTY);
        assertEquals(Set.of(Unmodeled.INVENTORY_CLIENT_ACTION), P774.apply(p, data, Wire.of(new InteractEntity(6, false, false)), world).gaps());
        assertTrue(data.inventory.unknown(20));
        // an attack without sprinting cannot slow the attacker (ClientAttackTest)
        assertEquals(Set.of(), P774.apply(p, holding(ItemStack.EMPTY), Wire.of(new Attack(5))).gaps());
        // a brush starts using from useOn when the block does not take the click
        data = holding(stack("minecraft:brush", 1));
        // (the block may have taken the only brush: the main hand may be empty)
        assertEquals(Set.of(Unmodeled.ITEM_USE_TIMING, Unmodeled.DROP_SLOT), P774.apply(p, data, Wire.of(new UseItemOn(false, 3, 0, 70, 0)), world).gaps());
        assertTrue(data.useMayContinue);
        assertFalse(data.usingItem);
        data = holding(stack("minecraft:stone", 3));
        data.localMode = GameType.SPECTATOR;
        assertEquals(Set.of(), P774.apply(p, data, Wire.of(new Attack(5))).gaps());
        assertEquals(Set.of(), P774.apply(p, data, Wire.of(new UseItemOn(false, 4, 0, 64, 0))).gaps());
    }

    @Test
    void aUseRemainderPreservesItsCompletePeStack() {
        var target = Stacks.withComponent(Stacks.of("minecraft:glass_bottle", 1), net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Custom remainder"));
        var food = Stacks.of("minecraft:apple", 1);
        var consumable = food.getOrDefault(net.minecraft.core.component.DataComponents.CONSUMABLE, null);
        food = Stacks.withComponent(food, net.minecraft.core.component.DataComponents.CONSUMABLE, new net.minecraft.world.item.component.Consumable(0.0F, consumable.animation(), consumable.sound(), true, java.util.List.of()));
        food = Stacks.withComponent(food, net.minecraft.core.component.DataComponents.USE_REMAINDER, new net.minecraft.world.item.component.UseRemainder(net.minecraft.world.item.ItemStackTemplate.fromStack(target)));
        var data = holding(food);
        data.food = 1;
        use(new PlayerState(), data);
        var result = data.inventory.item(0);
        assertTrue(ItemStack.isSameItemSameComponents(target, result));
        assertEquals(1, result.getCount());
        assertEquals(1, target.getCount());
    }

    @Test
    void equipmentEqualityIgnoresCountAndIncludesAllComponents() {
        var held = Stacks.of("minecraft:diamond_boots", 2);
        var data = holding(held);
        data.inventory.setItem(CompensatedInventory.FEET, Stacks.withCount(held, 1));
        use(new PlayerState(), data);
        assertEquals(2, data.inventory.item(0).getCount());
        assertEquals(1, data.inventory.item(CompensatedInventory.FEET).getCount());
        assertFalse(data.inventory.unknown(0));
        var name = net.minecraft.core.component.DataComponents.CUSTOM_NAME;
        var oldBoots = Stacks.withComponent(Stacks.withCount(held, 1), name, net.minecraft.network.chat.Component.literal("old"));
        var newBoots = Stacks.withComponent(Stacks.withCount(held, 1), name, net.minecraft.network.chat.Component.literal("new"));
        data = holding(newBoots);
        data.inventory.setItem(CompensatedInventory.FEET, oldBoots);
        use(new PlayerState(), data);
        assertTrue(ItemStack.isSameItemSameComponents(newBoots, data.inventory.item(CompensatedInventory.FEET)));
        assertTrue(ItemStack.isSameItemSameComponents(oldBoots, data.inventory.item(0)));
    }
}
