package ru.minecomplex.mac.inventory.item;


import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * The client's item stacks are native 26.3 {@link ItemStack}s. The model never mutates a stack it holds (a stack is
 * shared by the wrapper it came in, by forked hypotheses and by every slot that holds it): a change of count or
 * components writes a copy ({@link #withCount}). Empty stacks are {@link ItemStack#EMPTY}.
 */
public final class Stacks {
    private Stacks() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    /** The item's registry key; {@code minecraft:air} for an empty stack. */
    public static String key(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** The count; 0 for an empty stack. */
    public static int count(ItemStack stack) {
        return stack == null || stack.isEmpty() ? 0 : stack.getCount();
    }

    public static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty();
    }

    /** {@code ItemStack.is(Item)}: an empty stack is air. */
    public static boolean is(ItemStack stack, String key) {
        return !isEmpty(stack) && key(stack).equals(key);
    }

    /** A stack (or {@code null}) as the model holds it: {@link ItemStack#EMPTY} for every empty stack. */
    public static ItemStack of(ItemStack stack) {
        return isEmpty(stack) ? ItemStack.EMPTY : stack;
    }

    /** An unpatched stack in the client's component registry. */
    public static ItemStack of(String key, int count) {
        if (count <= 0 || key.equals("minecraft:air")) return ItemStack.EMPTY;
        ru.minecomplex.mac.packet.NativeRuntime.ensure();
        var type = BuiltInRegistries.ITEM.get(Identifier.parse(key)).orElseThrow(() -> new IllegalArgumentException("Unknown item " + key)).value();
        return new ItemStack(type, count);
    }

    /** The same stack with another count ({@code split}/{@code shrink}); a count of 0 or less is empty. */
    public static ItemStack withCount(ItemStack stack, int count) {
        if (count <= 0 || isEmpty(stack)) return ItemStack.EMPTY;
        if (stack.getCount() == count) return stack;
        ItemStack copy = stack.copy();
        copy.setCount(count);
        return copy;
    }

    /** A copy of {@code stack} with component {@code type} set to {@code value}. */
    public static <T> ItemStack withComponent(ItemStack stack, DataComponentType<T> type, T value) {
        if (isEmpty(stack)) return ItemStack.EMPTY;
        ItemStack copy = stack.copy();
        copy.set(type, value);
        return copy;
    }

}
