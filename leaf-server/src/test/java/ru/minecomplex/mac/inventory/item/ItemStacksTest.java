package ru.minecomplex.mac.inventory.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import ru.minecomplex.mac.packet.NativeRuntime;
import net.minecraft.core.component.DataComponents;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import ru.minecomplex.mac.inventory.EquipmentSlot;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ItemStacksTest {
    @BeforeAll static void setup() { NativeRuntime.ensure(); }

    @Test void changingCountOrComponentsLeavesSharedSourceUntouched() {
        var original = Stacks.of("minecraft:elytra", 3);
        var counted = Stacks.withCount(original, 2);
        var damaged = Stacks.withComponent(original, DataComponents.DAMAGE, 431);
        assertEquals(3, original.getCount());
        assertEquals(0, original.getDamageValue());
        assertEquals(2, counted.getCount());
        assertTrue(ItemMechanics.nextDamageWillBreak(damaged));
        assertFalse(ItemMechanics.nextDamageWillBreak(original));
        assertSame(ItemStack.EMPTY, Stacks.withCount(original, 0));
        assertTrue(ItemMechanics.canGlideUsing(original, EquipmentSlot.CHEST));
        var removed = original.copy(); removed.remove(DataComponents.GLIDER);
        assertFalse(ItemMechanics.canGlideUsing(removed, EquipmentSlot.CHEST));
        assertTrue(original.has(DataComponents.GLIDER));
    }

    @Test void riptideReadsActualPeEnchantmentLevels() {
        var stack = Stacks.of("minecraft:trident", 1);
        assertEquals(0.0F, ItemMechanics.spinAttackStrength(stack));
        for (int level : new int[]{1, 2, 3, 255}) {
            var enchanted = Stacks.withComponent(stack, DataComponents.ENCHANTMENTS,
                    ru.minecomplex.mac.fixture.FixtureItems.enchantments(Enchantments.RIPTIDE,level));
            assertEquals(Float.floatToIntBits(1.5F + 0.75F * (level - 1)),
                    Float.floatToIntBits(ItemMechanics.spinAttackStrength(enchanted)));
        }
    }
    @Test void instrumentCompatibilityDoesNotRewriteInlineDurations() {
        var stack = Stacks.of("minecraft:goat_horn", 1);
        assertEquals(7.0F, ItemMechanics.instrumentSeconds(stack));
        for (float seconds : new float[]{2.5F, 7.0F, 140.0F}) {
            var inline = new net.minecraft.world.item.Instrument(net.minecraft.core.Holder.direct(net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.withDefaultNamespace("item.goat_horn.sound.0"))),seconds,256.0F,0,net.minecraft.network.chat.Component.empty());
            var patched=Stacks.withComponent(stack,DataComponents.INSTRUMENT,new net.minecraft.world.item.component.InstrumentComponent(net.minecraft.core.Holder.direct(inline)));
            assertEquals(Float.floatToIntBits(seconds), Float.floatToIntBits(ItemMechanics.instrumentSeconds(patched)));
        }
    }

    @Test void nativeWireRetainsRemovedPrototypeComponents() {
        var item=Stacks.of("minecraft:elytra",1).copy();
        item.remove(DataComponents.GLIDER);
        assertFalse(item.has(DataComponents.GLIDER));
        var codec=new ru.minecomplex.mac.packet.NativePacketCodec(NativeRuntime.registries());
        var wire=codec.encode(1,ru.minecomplex.mac.replay.Recording.Stage.CLIENTBOUND,
                new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(0,1,6,item));
        var decoded=(net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket)codec.decode(wire);
        assertFalse(decoded.getItem().has(DataComponents.GLIDER), java.util.Arrays.toString(wire.payload()));
    }
}
