package ru.minecomplex.mac.replica;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.fixture.ItemComponentSample;
import net.minecraft.world.item.ItemStack;

import ru.minecomplex.mac.fixture.WireItem;

import ru.minecomplex.mac.fixture.FixtureItems;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ru.minecomplex.mac.inventory.EquipmentSlot;
import ru.minecomplex.mac.inventory.item.ItemBehavior;
import ru.minecomplex.mac.inventory.item.ItemMechanics;
import ru.minecomplex.mac.inventory.item.Stacks;
import ru.minecomplex.mac.inventory.menu.MenuLayouts;

/** Patch resolution, menu layout and the wire payload; vanilla equivalence is MAPS-tested (PlayerSyncOracleTest). */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class CompensatedInventoryTest {
    static { ru.minecomplex.mac.packet.NativeRuntime.ensure(); }
    private static final ItemBehavior DEFAULTS_777 = ItemBehavior.get();

    @Test
    void everyProtocolHasItemData() {
        ItemComponentSample elytra = FixtureItems.components(Stacks.of("minecraft:elytra", 1));
        assertTrue(elytra.glider(), "elytra glider");
        assertEquals("CHEST", elytra.equippableSlot());
        assertEquals(432, elytra.maxDamage());
        assertThrows(IllegalArgumentException.class, () -> Stacks.of("minecraft:no_such_item", 1));
    }

    @Test
    void patchesOverrideOrRemovePrototypeComponents() {
        WireItem plain = WireItem.of("minecraft:elytra", 1);
        assertTrue(ru.minecomplex.mac.inventory.item.ItemMechanics.canGlideUsing(FixtureItems.stack(plain), EquipmentSlot.CHEST));
        WireItem noGlider = WireItem.of("minecraft:elytra", 1, 0, WireItem.GLIDER, null, 0, 0, null, 0.0F);
        assertFalse(ru.minecomplex.mac.inventory.item.ItemMechanics.canGlideUsing(FixtureItems.stack(noGlider), EquipmentSlot.CHEST));
        WireItem stoneWings = WireItem.of("minecraft:stone", 1, WireItem.GLIDER | WireItem.EQUIPPABLE, 0, "FEET", 0, 0, null, 0.0F);
        assertTrue(ru.minecomplex.mac.inventory.item.ItemMechanics.canGlideUsing(FixtureItems.stack(stoneWings), EquipmentSlot.FEET));
        assertFalse(ru.minecomplex.mac.inventory.item.ItemMechanics.canGlideUsing(FixtureItems.stack(stoneWings), EquipmentSlot.CHEST));
        WireItem noDamage = WireItem.of("minecraft:elytra", 1, 0, WireItem.DAMAGE, null, 0, 0, null, 0.0F);
        assertNull(FixtureItems.components(FixtureItems.stack(noDamage)).damage());
        assertThrows(IllegalArgumentException.class,
                () -> WireItem.of("minecraft:stone", 1, WireItem.DAMAGE, WireItem.DAMAGE, null, 0, 0, null, 0.0F));
    }

    @Test
    void elytraBreaksAtTheLastDurabilityPoint() {
        WireItem worn = WireItem.of("minecraft:elytra", 1, WireItem.DAMAGE, 0, null, 0, 430, null, 0.0F);
        assertFalse(ru.minecomplex.mac.inventory.item.ItemMechanics.nextDamageWillBreak(FixtureItems.stack(worn)));
        WireItem broken = WireItem.of("minecraft:elytra", 1, WireItem.DAMAGE, 0, null, 0, 431, null, 0.0F);
        assertTrue(ru.minecomplex.mac.inventory.item.ItemMechanics.nextDamageWillBreak(FixtureItems.stack(broken)));
        WireItem unbreakable = WireItem.of("minecraft:elytra", 1, WireItem.DAMAGE | WireItem.UNBREAKABLE, 0, null, 0, 431, null, 0.0F);
        assertTrue(ru.minecomplex.mac.inventory.item.ItemMechanics.canGlideUsing(FixtureItems.stack(unbreakable), EquipmentSlot.CHEST));
    }

    @Test
    void inventoryMenuMapsArmorHotbarAndOffhand() {
        assertEquals(CompensatedInventory.HEAD, CompensatedInventory.inventoryIndex(5));
        assertEquals(CompensatedInventory.FEET, CompensatedInventory.inventoryIndex(8));
        assertEquals(9, CompensatedInventory.inventoryIndex(9));
        assertEquals(0, CompensatedInventory.inventoryIndex(36));
        assertEquals(8, CompensatedInventory.inventoryIndex(44));
        assertEquals(CompensatedInventory.OFFHAND, CompensatedInventory.inventoryIndex(45));
        assertEquals(-1, CompensatedInventory.inventoryIndex(0));
        CompensatedInventory inventory = new CompensatedInventory();
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.setMenuItem(46, ItemStack.EMPTY));
        inventory.setMenuItem(6, FixtureItems.stack(WireItem.of("minecraft:elytra", 1)));
        assertTrue(inventory.equipmentCanGlide());
        inventory.setMenuItem(8, FixtureItems.stack(WireItem.of("minecraft:leather_boots", 1)));
        assertTrue(inventory.walksOnPowderSnow());
    }

    @Test
    void eachMenuHasItsOwnCarriedStack() {
        CompensatedInventory inventory = new CompensatedInventory();
        ItemStack stone = FixtureItems.stack(WireItem.of("minecraft:stone", 3));
        inventory.setCarried(stone);
        inventory.openContainer(2, null);
        assertTrue(inventory.carried().isEmpty());
        inventory.closeContainer();
        assertEquals(stone, inventory.carried());
    }

    @Test
    void menuLayoutsCoverTheRegisteredMenuTypes() {
        assertEquals(25, MenuLayouts.size());
        assertEquals("minecraft:generic_9x1", MenuLayouts.layout(0).key());
        assertEquals("minecraft:crafting", MenuLayouts.layout(12).key());
        assertEquals("minecraft:stonecutter", MenuLayouts.layout(24).key());
        assertNull(MenuLayouts.layout(25));
        assertNull(MenuLayouts.layout(-1));
        // the crafter's result slot follows the player inventory; the lectern has only its book slot
        int[] crafter = MenuLayouts.layout(7).slots();
        assertEquals(46, crafter.length);
        assertEquals(9, crafter[9]);
        assertEquals(8, crafter[44]);
        assertEquals(-1, crafter[45]);
        assertArrayEquals(new int[]{-1}, MenuLayouts.layout(17).slots());
    }

    @Test
    void mountMenusFollowTheirEntityAndColumns() {
        int[] horse = MenuLayouts.mountLayout(true, 3);
        assertEquals(2 + 9 + 36, horse.length);
        assertEquals(-1, horse[10]);
        assertEquals(9, horse[11]);
        assertEquals(0, horse[38]);
        assertArrayEquals(MenuLayouts.mountLayout(true, 0), MenuLayouts.mountLayout(false, 3));
        assertEquals(2 + 36, MenuLayouts.mountLayout(false, 5).length);
    }

    @Test
    void openMenuWritesPlayerSlotsThroughItsLayout() {
        MenuLayouts.Layout chest = MenuLayouts.layout(2);
        assertEquals("minecraft:generic_9x3", chest.key());
        assertEquals(63, chest.slots().length);
        CompensatedInventory inventory = new CompensatedInventory();
        inventory.openContainer(5, chest.slots());
        ItemStack boots = FixtureItems.stack(WireItem.of("minecraft:leather_boots", 1));
        inventory.setOpenMenuItem(0, boots);
        assertTrue(inventory.item(9).isEmpty());
        inventory.setOpenMenuItem(27, boots);
        assertEquals(boots, inventory.item(9));
        inventory.setOpenMenuItem(54, boots);
        assertEquals(boots, inventory.item(0));
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.setOpenMenuItem(63, boots));
        assertTrue(inventory.openMenuKnown());
        assertEquals("minecraft:loom", MenuLayouts.layout(18).key());
        inventory.mountScreenOpen(6);
        assertFalse(inventory.openMenuKnown());
        assertTrue(inventory.mayBeOpen(5) && inventory.mayBeOpen(6) && !inventory.mayBeOpen(7));
        inventory.closeContainer();
        assertTrue(inventory.openMenuKnown());
        assertFalse(inventory.mayBeOpen(6));
    }

}
