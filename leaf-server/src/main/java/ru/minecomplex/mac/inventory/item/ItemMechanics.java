package ru.minecomplex.mac.inventory.item;


import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.InstrumentComponent;
import net.minecraft.world.item.ItemStack;
import ru.minecomplex.mac.inventory.EquipmentSlot;

/** Vanilla predicates over native 26.3 stacks. No component storage or prototype/patch resolution. */
public final class ItemMechanics {
    private ItemMechanics() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    public static boolean nextDamageWillBreak(ItemStack stack) {
        return !stack.isEmpty() && stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1;
    }

    public static boolean canGlideUsing(ItemStack stack, EquipmentSlot slot) {
        return !stack.isEmpty() && stack.has(DataComponents.GLIDER)
                && slot.name().equals(equippableSlot(stack)) && !nextDamageWillBreak(stack);
    }

    public static String equippableSlot(ItemStack stack) {
        var equippable = stack.getOrDefault(DataComponents.EQUIPPABLE, null);
        return stack.isEmpty() || equippable == null ? null : equipmentSlot(equippable.slot());
    }

    public static boolean equippableByPlayer(ItemStack stack) {
        var allowed = stack.getOrDefault(DataComponents.EQUIPPABLE, null).allowedEntities();
        return allowed.isEmpty() || allowed.get().unwrapKey().isEmpty()
                && allowed.get().contains(net.minecraft.world.entity.EntityTypes.PLAYER.builtInRegistryHolder());
    }

    public static boolean charged(ItemStack stack) {
        var projectiles = stack.getOrDefault(DataComponents.CHARGED_PROJECTILES, null);
        return projectiles != null && !projectiles.items().isEmpty();
    }

    public static int enchantmentLevel(ItemStack stack, String key) {
        var enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, null);
        if (enchantments == null) return 0;
        var id = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                net.minecraft.resources.Identifier.parse(key));
        for (var holder : enchantments.keySet()) if (holder.is(id)) return enchantments.getLevel(holder);
        return 0;
    }

    /** Vanilla riptide effect: add(linear(1.5, 0.75)); float operation order is significant. */
    public static float spinAttackStrength(ItemStack stack) {
        int level = enchantmentLevel(stack, "minecraft:riptide");
        return level == 0 ? 0.0F : 0.0F + (1.5F + 0.75F * (level - 1));
    }

    /** An unresolved holder remains unknown; inline and resolved registry values use the actual duration. */
    public static Float instrumentSeconds(ItemStack stack) {
        InstrumentComponent component = stack.getOrDefault(DataComponents.INSTRUMENT, null);
        return component == null ? null : component.instrument().value().useDuration();
    }

    public static String equipmentSlot(net.minecraft.world.entity.EquipmentSlot slot) {
        return slot.name();
    }
}
