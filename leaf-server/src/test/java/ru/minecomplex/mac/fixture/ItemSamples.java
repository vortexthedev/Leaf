package ru.minecomplex.mac.fixture;

import ru.minecomplex.mac.inventory.item.Stacks;
import ru.minecomplex.mac.inventory.item.ItemMechanics;
import static ru.minecomplex.mac.inventory.item.Stacks.*;

import java.util.HashMap;

import net.minecraft.core.component.DataComponentType;

import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.component.UseCooldown;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.item.component.UseRemainder;
import net.minecraft.network.HashedStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** Readers of the independent vanilla fixture component subset. */
public final class ItemSamples {
    private ItemSamples() {}
    public static ItemComponentSample components(ItemStack stack) {
        if (Stacks.isEmpty(stack)) return ItemComponentSample.NONE;
        int present = 0;
        for (int bit = ItemComponentSample.GLIDER; bit <= ItemComponentSample.USE_REMAINDER; bit <<= 1) {
            if (stack.get(componentType(bit)) != null) present |= bit;
        }
        Patch patch = patch(stack);
        return values(stack, new Patch(present | (patch.set() & ItemComponentSample.TOOL), patch.removed()));
    }

    public record Patch(int set, int removed) { public static final Patch NONE = new Patch(0, 0); }
    /** The modelled component bit of a component type key, 0 for any other component. */
    public static int componentBit(String key) {
        return switch (key) {
            case "minecraft:glider" -> ItemComponentSample.GLIDER;
            case "minecraft:unbreakable" -> ItemComponentSample.UNBREAKABLE;
            case "minecraft:equippable" -> ItemComponentSample.EQUIPPABLE;
            case "minecraft:max_damage" -> ItemComponentSample.MAX_DAMAGE;
            case "minecraft:damage" -> ItemComponentSample.DAMAGE;
            case "minecraft:use_effects" -> ItemComponentSample.USE_EFFECTS;
            case "minecraft:consumable" -> ItemComponentSample.CONSUMABLE;
            case "minecraft:food" -> ItemComponentSample.FOOD;
            case "minecraft:blocks_attacks" -> ItemComponentSample.BLOCKS_ATTACKS;
            case "minecraft:kinetic_weapon" -> ItemComponentSample.KINETIC_WEAPON;
            case "minecraft:charged_projectiles" -> ItemComponentSample.CHARGED_PROJECTILES;
            case "minecraft:instrument" -> ItemComponentSample.INSTRUMENT;
            case "minecraft:use_cooldown" -> ItemComponentSample.USE_COOLDOWN;
            case "minecraft:enchantments" -> ItemComponentSample.ENCHANTMENTS;
            case "minecraft:use_remainder" -> ItemComponentSample.USE_REMAINDER;
            // only whether the stack patches it: its creative rule decides client block breaking (no value read)
            case "minecraft:tool" -> ItemComponentSample.TOOL;
            default -> 0;
        };
    }

    /** {@link #componentBit} by bit: the component type. */
    public static DataComponentType<?> componentType(int bit) {
        return BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(Identifier.parse(switch (bit) {
            case ItemComponentSample.GLIDER -> "minecraft:glider";
            case ItemComponentSample.UNBREAKABLE -> "minecraft:unbreakable";
            case ItemComponentSample.EQUIPPABLE -> "minecraft:equippable";
            case ItemComponentSample.MAX_DAMAGE -> "minecraft:max_damage";
            case ItemComponentSample.DAMAGE -> "minecraft:damage";
            case ItemComponentSample.USE_EFFECTS -> "minecraft:use_effects";
            case ItemComponentSample.CONSUMABLE -> "minecraft:consumable";
            case ItemComponentSample.FOOD -> "minecraft:food";
            case ItemComponentSample.BLOCKS_ATTACKS -> "minecraft:blocks_attacks";
            case ItemComponentSample.KINETIC_WEAPON -> "minecraft:kinetic_weapon";
            case ItemComponentSample.CHARGED_PROJECTILES -> "minecraft:charged_projectiles";
            case ItemComponentSample.INSTRUMENT -> "minecraft:instrument";
            case ItemComponentSample.USE_COOLDOWN -> "minecraft:use_cooldown";
            case ItemComponentSample.ENCHANTMENTS -> "minecraft:enchantments";
            case ItemComponentSample.USE_REMAINDER -> "minecraft:use_remainder";
            case ItemComponentSample.TOOL -> "minecraft:tool";
            default -> throw new IllegalArgumentException("Not a component bit: " + bit);
        }));
    }

    /** Which modelled components the stack's patch sets and removes. */
    public static Patch patch(ItemStack stack) {
        if (isEmpty(stack) || stack.getComponentsPatch().isEmpty()) return Patch.NONE;
        int set = 0, removed = 0;
        var split=stack.getComponentsPatch().split();
        for (var type:split.added().keySet()) set |= componentBit(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString());
        for (var type:split.removed()) removed |= componentBit(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString());
        return new Patch(set, removed);
    }

    /** Which modelled components a hashed stack's patch sets and removes ({@code null}: empty). */
    public static Patch patch(HashedStack stack) {
        if (!(stack instanceof HashedStack.ActualItem actual)) return Patch.NONE;
        int set = 0, removed = 0;
        for (var type : actual.components().addedComponents().keySet()) set |= componentBit(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString());
        for (var type : actual.components().removedComponents()) removed |= componentBit(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString());
        return new Patch(set, removed);
    }

    /** The registry key of a hashed stack's item ({@code null}: empty). */
    public static String key(HashedStack stack) {
        return stack instanceof HashedStack.ActualItem actual && actual.count() > 0 ? BuiltInRegistries.ITEM.getKey(actual.item().value()).toString() : "minecraft:air";
    }

    public static int count(HashedStack stack) {
        return stack instanceof HashedStack.ActualItem actual ? Math.max(actual.count(), 0) : 0;
    }

    /** The values the patch sets for the modelled components (absent ones: {@link ItemComponentSample#NONE}'s). */
    public static ItemComponentSample values(ItemStack stack, Patch patch) {
        if (patch.set() == 0) return ItemComponentSample.NONE;
        String slot = null;
        boolean swappable = false, byPlayer = false, charged = false;
        Integer maxDamage = null, damage = null;
        Float seconds = null;
        ItemComponentSample.UseEffects effects = null;
        ItemComponentSample.Food food = null;
        ItemComponentSample.UseCooldown cooldown = null;
        ItemComponentSample.Enchantments enchantments = null;
        ItemComponentSample.Remainder remainder = null;
        Float instrument = null;
        for (int bit = 1; bit <= ItemComponentSample.TOOL; bit <<= 1) {
            if ((patch.set() & bit) == 0) continue;
            Object value = stack.get(componentType(bit));
            if (value == null) continue;
            switch (bit) {
                case ItemComponentSample.EQUIPPABLE -> {
                    var equippable = (Equippable) value;
                    slot = ItemMechanics.equipmentSlot(equippable.slot());
                    swappable = equippable.swappable();
                    byPlayer = ItemMechanics.equippableByPlayer(stack);
                }
                case ItemComponentSample.MAX_DAMAGE -> maxDamage = (Integer) value;
                case ItemComponentSample.DAMAGE -> damage = (Integer) value;
                case ItemComponentSample.USE_EFFECTS -> {
                    var use = (UseEffects) value;
                    effects = new ItemComponentSample.UseEffects(use.canSprint(), use.interactVibrations(), use.speedMultiplier());
                }
                case ItemComponentSample.CONSUMABLE -> seconds = ((Consumable) value).consumeSeconds();
                case ItemComponentSample.FOOD -> {
                    var properties = (FoodProperties) value;
                    food = new ItemComponentSample.Food(properties.nutrition(), properties.saturation(), properties.canAlwaysEat());
                }
                case ItemComponentSample.INSTRUMENT -> instrument = ItemMechanics.instrumentSeconds(stack);
                case ItemComponentSample.CHARGED_PROJECTILES -> charged = !((ChargedProjectiles) value).items().isEmpty();
                case ItemComponentSample.USE_COOLDOWN -> {
                    var use = (UseCooldown) value;
                    cooldown = new ItemComponentSample.UseCooldown(use.seconds(), use.cooldownGroup().map(Object::toString).orElse(null));
                }
                case ItemComponentSample.ENCHANTMENTS -> {
                    // the vanilla enchantments with the spin-attack, armor-change-prevention and crossbow-charge-time effects
                    boolean spin = false, binding = false;
                    int quickCharge = 0;
                    var levels = new HashMap<String, Integer>();
                    for (var level : ((ItemEnchantments) value).entrySet()) {
                        String key = level.getKey().unwrapKey().orElseThrow().identifier().toString();
                        spin |= key.equals("minecraft:riptide");
                        binding |= key.equals("minecraft:binding_curse");
                        if (key.equals("minecraft:quick_charge")) quickCharge = level.getValue();
                        levels.put(key, level.getValue());
                    }
                    enchantments = new ItemComponentSample.Enchantments(spin, binding, quickCharge, ItemMechanics.spinAttackStrength(stack));
                }
                case ItemComponentSample.USE_REMAINDER -> {
                    var target = ((UseRemainder) value).convertInto().create();
                    remainder = new ItemComponentSample.Remainder(Stacks.key(target), Stacks.count(target));
                }
                default -> { }
            }
        }
        int set = patch.set();
        boolean equippable = (set & ItemComponentSample.EQUIPPABLE) != 0;
        return new ItemComponentSample((set & ItemComponentSample.GLIDER) != 0, (set & ItemComponentSample.UNBREAKABLE) != 0,
                equippable ? slot : null, equippable && swappable, equippable && byPlayer, maxDamage, damage, effects, seconds,
                food, (set & ItemComponentSample.BLOCKS_ATTACKS) != 0, (set & ItemComponentSample.KINETIC_WEAPON) != 0, charged,
                instrument, cooldown, enchantments, remainder);
    }

}
