package ru.minecomplex.mac.fixture;

import java.util.List;

import ru.minecomplex.mac.inventory.item.Stacks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.component.InstrumentComponent;
import net.minecraft.world.item.component.UseCooldown;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.item.component.UseRemainder;
import net.minecraft.network.HashedStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.Instrument;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.core.Holder;
import ru.minecomplex.mac.packet.NativeRuntime;
import net.minecraft.util.Unit;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;

/**
 * Test stacks: a {@link WireItem} (item, count, the modelled components a patch sets or removes and their values) is
 * written as the native 26.3 stack a server would send, with real component values; and read back from one.
 * Components the model does not read get vanilla-like stand-ins (sounds, animations), which the model never reads.
 */
public final class FixtureItems {
    public static net.minecraft.world.item.enchantment.ItemEnchantments enchantments(net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key,int level) {
        NativeRuntime.ensure();
        var values=new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        values.set(NativeRuntime.registries().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(key),level);
        return values.toImmutable();
    }
    private FixtureItems() {
    }

    /** The stack a server sends for {@code item} ({@code null} or empty: {@link ItemStack#EMPTY}). */
    public static ItemStack stack(WireItem item) {
        if (item == null || item.isEmpty()) return ItemStack.EMPTY;
        ItemStack stack = Stacks.of(item.item(), item.count()).copy();
        ItemComponentSample c = item.values();
        for (int bit = ItemComponentSample.GLIDER; bit <= ItemComponentSample.TOOL; bit <<= 1) {
            if ((item.removed() & bit) != 0) stack.remove(ru.minecomplex.mac.fixture.ItemSamples.componentType(bit));
            if ((item.set() & bit) == 0) continue;
            switch (bit) {
                case ItemComponentSample.GLIDER -> stack.set(DataComponents.GLIDER, Unit.INSTANCE);
                case ItemComponentSample.UNBREAKABLE -> stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
                case ItemComponentSample.EQUIPPABLE -> {
                    var builder = Equippable.builder(slot(c.equippableSlot())).setSwappable(c.swappable());
                    if (!c.equippableByPlayer()) builder.setAllowedEntities(net.minecraft.world.entity.EntityTypes.ZOMBIE);
                    stack.set(DataComponents.EQUIPPABLE, builder.build());
                }
                case ItemComponentSample.MAX_DAMAGE -> stack.set(DataComponents.MAX_DAMAGE, c.maxDamage());
                case ItemComponentSample.DAMAGE -> stack.set(DataComponents.DAMAGE, c.damage());
                case ItemComponentSample.USE_EFFECTS -> stack.set(DataComponents.USE_EFFECTS, new UseEffects(
                        c.useEffects().canSprint(), c.useEffects().interactVibrations(), c.useEffects().speedMultiplier()));
                case ItemComponentSample.CONSUMABLE -> stack.set(DataComponents.CONSUMABLE,
                        new Consumable(c.consumeSeconds(), net.minecraft.world.item.ItemUseAnimation.EAT,
                                SoundEvents.GENERIC_EAT, true, List.of()));
                case ItemComponentSample.FOOD -> stack.set(DataComponents.FOOD, new FoodProperties(c.food().nutrition(),
                        c.food().saturation(), c.food().canAlwaysEat()));
                case ItemComponentSample.BLOCKS_ATTACKS -> stack.set(DataComponents.BLOCKS_ATTACKS,
                        new ItemStack(Items.SHIELD).get(DataComponents.BLOCKS_ATTACKS));
                case ItemComponentSample.KINETIC_WEAPON -> stack.set(DataComponents.KINETIC_WEAPON,
                        Stacks.of("minecraft:wooden_spear", 1).get(DataComponents.KINETIC_WEAPON));
                case ItemComponentSample.CHARGED_PROJECTILES -> stack.set(DataComponents.CHARGED_PROJECTILES,
                        new ChargedProjectiles(c.charged() ? List.of(ItemStackTemplate.fromNonEmptyStack(Stacks.of("minecraft:arrow", 1))) : List.of()));
                case ItemComponentSample.INSTRUMENT -> stack.set(DataComponents.INSTRUMENT,
                        new InstrumentComponent(Holder.direct(new Instrument(Holder.direct(net.minecraft.sounds.SoundEvent.createVariableRangeEvent(Identifier.withDefaultNamespace("item.goat_horn.sound.0"))),
                                c.instrumentSeconds(), 256.0F, 0, net.minecraft.network.chat.Component.empty()))));
                case ItemComponentSample.USE_COOLDOWN -> stack.set(DataComponents.USE_COOLDOWN, new UseCooldown(
                        c.useCooldown().seconds(), java.util.Optional.ofNullable(c.useCooldown().group()).map(Identifier::parse)));
                case ItemComponentSample.ENCHANTMENTS -> stack.set(DataComponents.ENCHANTMENTS, enchantments(c.enchantments()));
                case ItemComponentSample.USE_REMAINDER -> stack.set(DataComponents.USE_REMAINDER,
                        new UseRemainder(ItemStackTemplate.fromNonEmptyStack(Stacks.of(c.useRemainder().item(), c.useRemainder().count()))));
                case ItemComponentSample.TOOL -> stack.set(DataComponents.TOOL, new Tool(List.of(), 1.0F, 1, true));
                default -> throw new IllegalArgumentException("Component bit " + bit);
            }
        }
        return stack;
    }

    /**
     * A stack whose components resolve to {@code c} over the prototype of {@code defaults} (oracle stacks are written as
     * the client's resolved components): its patch sets every modelled component {@code c} has and removes every one the
     * prototype has and {@code c} lacks.
     */
    public static ItemStack resolved(String key, int count, ItemComponentSample c) {
        if (count <= 0 || key.equals("minecraft:air")) return ItemStack.EMPTY;
        int present = ru.minecomplex.mac.fixture.ItemSampleCodec.present(c);
        int prototype = ru.minecomplex.mac.fixture.ItemSampleCodec.present(FixtureItems.components(Stacks.of(key, 1)));
        int set = present, removed = prototype & ~present;
        if (c.toolPatch() == ItemComponentSample.TOOL_SET) set |= ItemComponentSample.TOOL;
        if (c.toolPatch() == ItemComponentSample.TOOL_REMOVED) removed |= ItemComponentSample.TOOL;
        return stack(new WireItem(key, count, set, removed, c));
    }

    public static ItemComponentSample components(ItemStack stack) { return ItemSamples.components(stack); }

    /** A stack as the model reads it. */
    public static WireItem of(ItemStack stack) {
        if (Stacks.isEmpty(stack)) return WireItem.EMPTY;
        ru.minecomplex.mac.fixture.ItemSamples.Patch patch = ru.minecomplex.mac.fixture.ItemSamples.patch(stack);
        return new WireItem(Stacks.key(stack), Stacks.count(stack), patch.set(), patch.removed(), ru.minecomplex.mac.fixture.ItemSamples.values(stack, patch));
    }

    /** A hashed click stack ({@code null}: empty). */
    public static ClaimedStack claimed(HashedStack stack) {
        if (!(stack instanceof HashedStack.ActualItem actual)) return ClaimedStack.EMPTY;
        ru.minecomplex.mac.fixture.ItemSamples.Patch patch = ru.minecomplex.mac.fixture.ItemSamples.patch(stack);
        return ClaimedStack.hashed(ItemSamples.key(stack), actual.count(), patch.set(), patch.removed());
    }

    /** The enchantments with these effects: riptide at the level of the spin strength, binding curse, quick charge. */
    private static ItemEnchantments enchantments(ItemComponentSample.Enchantments enchantments) {
        var levels = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        var registry = ru.minecomplex.mac.packet.NativeRuntime.registries().lookupOrThrow(Registries.ENCHANTMENT);
        if (enchantments.spinAttack()) {
            float strength = enchantments.spinStrength();
            levels.set(registry.getOrThrow(Enchantments.RIPTIDE), Float.isNaN(strength) ? 1 : Math.round((strength - 1.5F) / 0.75F) + 1);
        }
        if (enchantments.preventArmorChange()) levels.set(registry.getOrThrow(Enchantments.BINDING_CURSE), 1);
        if (enchantments.quickCharge() > 0) levels.set(registry.getOrThrow(Enchantments.QUICK_CHARGE), enchantments.quickCharge());
        return levels.toImmutable();
    }

    private static EquipmentSlot slot(String vanilla) {
        return EquipmentSlot.valueOf(vanilla);
    }
}
