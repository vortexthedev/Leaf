package ru.minecomplex.mac.listener;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import ru.minecomplex.mac.replica.CompensatedInventory;
import ru.minecomplex.mac.fixture.ItemComponentSample;
import ru.minecomplex.mac.inventory.item.ItemBehavior;
import net.minecraft.world.item.ItemStack;
import ru.minecomplex.mac.inventory.item.Stacks;
import ru.minecomplex.mac.fixture.FixtureItems;
import ru.minecomplex.mac.inventory.menu.MenuLayouts;
import ru.minecomplex.mac.fixture.WireItem;
import ru.minecomplex.mac.fixture.ClaimedStack;
import ru.minecomplex.mac.fixture.inbound.ContainerClick;
import ru.minecomplex.mac.fixture.inbound.CreativeSlot;
import ru.minecomplex.mac.player.state.ClientPlayerData;

/**
 * Hashed click results the MAPS oracle rarely produces: several candidate stacks, none, a removed component, a menu the
 * model does not hold, the offhand outside a container menu, the creative screen.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ContainerClaimsTest {
    static { ru.minecomplex.mac.packet.NativeRuntime.ensure(); }
    private static final ItemBehavior ITEMS = ItemBehavior.get();
    private static final ContainerClaims CLAIMS = new ContainerClaims();

    private static ItemStack damaged(String item, int damage) {
        return FixtureItems.stack(new WireItem(item, 1, WireItem.DAMAGE, 0, new ItemComponentSample(false, false, null, false, false, null,
                damage, null, null, null, false, false, false, null, null, null, null)));
    }

    private static ContainerClick click(int containerId, int slot, int button, int type, Map<Integer, ClaimedStack> changed,
                                        ClaimedStack carried) {
        return ContainerClick.of(containerId, 0, slot, button, type, changed, carried);
    }

    @Test
    void hashedStacksTakeTheComponentsOfTheirOnlyCandidate() {
        ClientPlayerData data = new ClientPlayerData();
        data.inventory.setItem(9, damaged("minecraft:elytra", 7));
        // the elytra moves from menu slot 9 to the cursor: the claim shows the damage type, not its value
        CLAIMS.click(data, Wire.of(click(0, 9, 0, 0, Map.of(9, ClaimedStack.EMPTY),
                ClaimedStack.hashed("minecraft:elytra", 1, WireItem.DAMAGE, 0))));
        assertTrue(ItemStack.isSameItemSameComponents(damaged("minecraft:elytra", 7), data.inventory.carried()));
        assertFalse(data.inventory.carriedUnknown());
        assertTrue(data.inventory.item(9).isEmpty());
        assertFalse(data.inventory.uncertain());
    }

    @Test
    void severalCandidatesOrNoneLeaveTheSlotGuessed() {
        ClientPlayerData data = new ClientPlayerData();
        data.inventory.setItem(9, damaged("minecraft:elytra", 7));
        data.inventory.setItem(10, damaged("minecraft:elytra", 9));
        CLAIMS.click(data, Wire.of(click(0, 9, 0, 0, Map.of(9, ClaimedStack.EMPTY),
                ClaimedStack.hashed("minecraft:elytra", 1, WireItem.DAMAGE, 0))));
        assertTrue(data.inventory.carriedUnknown());
        // a stack out of a bundle: no candidate in the menu
        CLAIMS.click(data, Wire.of(click(0, 11, 1, 0, Map.of(11, ClaimedStack.hashed("minecraft:diamond_sword", 1, WireItem.DAMAGE, 0)),
                ClaimedStack.hashed("minecraft:elytra", 1, WireItem.DAMAGE, 0))));
        assertTrue(data.inventory.unknown(11));
        assertEquals("minecraft:diamond_sword", Stacks.key(data.inventory.item(11)));
        // a server write makes it exact again
        data.inventory.setItem(11, ItemStack.EMPTY);
        assertFalse(data.inventory.unknown(11));
    }

    @Test
    void aPatchWithoutModelledValuesIsThePrototype() {
        ClientPlayerData data = new ClientPlayerData();
        CLAIMS.click(data, Wire.of(click(0, 9, 0, 0, Map.of(9, ClaimedStack.hashed("minecraft:elytra", 1, 0, WireItem.GLIDER)),
                ClaimedStack.EMPTY)));
        assertFalse(FixtureItems.components(data.inventory.item(9)).glider(), "the removed glider");
        assertFalse(data.inventory.unknown(9));
    }

    @Test
    void aClickOnAnotherMenuLeavesTheInventoryUnknown() {
        ClientPlayerData data = new ClientPlayerData();
        CLAIMS.click(data, Wire.of(click(4, 0, 0, 0, Map.of(), ClaimedStack.EMPTY)));
        assertTrue(data.inventory.unknown(0));
        assertTrue(data.inventory.unknown(CompensatedInventory.OFFHAND));
    }

    @Test
    void theOffhandOfAContainerMenuIsNotReported() {
        MenuLayouts.Layout chest = MenuLayouts.layout(2);
        ClientPlayerData data = new ClientPlayerData();
        data.inventory.openContainer(5, chest.slots(), chest.key());
        data.inventory.setItem(CompensatedInventory.OFFHAND, damaged("minecraft:elytra", 3));
        // swapping chest slot 0 with the offhand (button 40): only the chest slot is in the packet
        CLAIMS.click(data, Wire.of(click(5, 0, CompensatedInventory.OFFHAND, 2, Map.of(0, ClaimedStack.hashed("minecraft:elytra", 1, WireItem.DAMAGE, 0)),
                ClaimedStack.EMPTY)));
        assertTrue(data.inventory.unknown(CompensatedInventory.OFFHAND));
        assertFalse(data.inventory.openMenuUnknown(0));
        assertTrue(ItemStack.isSameItemSameComponents(damaged("minecraft:elytra", 3), data.inventory.openMenuStack(0)));
        // a click that changed nothing cannot have swapped it
        ClientPlayerData quiet = new ClientPlayerData();
        quiet.inventory.openContainer(5, chest.slots(), chest.key());
        CLAIMS.click(quiet, Wire.of(click(5, 0, CompensatedInventory.OFFHAND, 2, Map.of(), ClaimedStack.EMPTY)));
        assertFalse(quiet.inventory.uncertain());
    }

    @Test
    void theCreativeScreenReportsExactStacksButNotItsCarriedOne() {
        ClientPlayerData data = new ClientPlayerData();
        CLAIMS.creative(data, Wire.of(CreativeSlot.of(36, WireItem.of("minecraft:stone", 64))));
        assertEquals(64, Stacks.count(data.inventory.item(0)));
        assertFalse(data.inventory.unknown(0));
        assertTrue(data.inventory.carriedUnknown());
        CLAIMS.creative(data, Wire.of(CreativeSlot.of(-1, WireItem.of("minecraft:stone", 1))));
        assertEquals(List.of(), java.util.stream.IntStream.range(0, CompensatedInventory.SIZE).filter(data.inventory::unknown).boxed().toList());
    }
    @Test
    void hashedClaimsKeepCompleteComponentsAndAmbiguity() {
        var claims = new ContainerClaims();
        var first = Stacks.withComponent(Stacks.of("minecraft:stone", 3),
                net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("first"));
        var second = Stacks.withComponent(first,
                net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("second"));
        var hash = net.minecraft.network.HashedStack.create(first,component -> 0);
        var known = claims.resolve(hash, List.of(new ContainerClaims.Resolved(first, false)));
        assertFalse(known.guessed());
        assertTrue(ItemStack.isSameItemSameComponents(first, known.stack()));
        assertTrue(claims.resolve(hash, List.of(new ContainerClaims.Resolved(first, false),
                new ContainerClaims.Resolved(second, false))).guessed());
        assertTrue(claims.resolve(hash, List.of()).guessed());
    }
}
