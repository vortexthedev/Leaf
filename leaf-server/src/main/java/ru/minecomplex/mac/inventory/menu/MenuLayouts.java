package ru.minecomplex.mac.inventory.menu;

import java.util.Arrays;

/**
 * Client menu slot layouts by menu type registry id: the menus {@code MenuScreens.create} builds with
 * {@code type.create(id, inventory)}. A layout maps each menu slot to the player inventory index behind it, or −1 for
 * the menu's own containers. The 25 menu types, their registry order and their slots are the same in every supported
 * release (1.21.4 – 26.3); {@code tools/datagen/run_menu_layouts.py} checks this table against every release.
 */
public final class MenuLayouts {
    private MenuLayouts() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /** One menu type: registry key and slot layout. */
    public static record Layout(String key, int[] slots) {
    }

    /** By menu type registry id. Rows: key, own slots before the player inventory, own slots after it. */
    private static final Layout[] LAYOUTS = {
            withInventory("minecraft:generic_9x1", 9, 0),
            withInventory("minecraft:generic_9x2", 18, 0),
            withInventory("minecraft:generic_9x3", 27, 0),
            withInventory("minecraft:generic_9x4", 36, 0),
            withInventory("minecraft:generic_9x5", 45, 0),
            withInventory("minecraft:generic_9x6", 54, 0),
            withInventory("minecraft:generic_3x3", 9, 0),
            // CrafterMenu adds its result slot after the player inventory
            withInventory("minecraft:crafter_3x3", 9, 1),
            withInventory("minecraft:anvil", 3, 0),
            withInventory("minecraft:beacon", 1, 0),
            withInventory("minecraft:blast_furnace", 3, 0),
            withInventory("minecraft:brewing_stand", 5, 0),
            withInventory("minecraft:crafting", 10, 0),
            withInventory("minecraft:enchantment", 2, 0),
            withInventory("minecraft:furnace", 3, 0),
            withInventory("minecraft:grindstone", 3, 0),
            withInventory("minecraft:hopper", 5, 0),
            // LecternMenu has only the book slot
            ownOnly("minecraft:lectern", 1),
            withInventory("minecraft:loom", 4, 0),
            withInventory("minecraft:merchant", 3, 0),
            withInventory("minecraft:shulker_box", 27, 0),
            withInventory("minecraft:smithing", 4, 0),
            withInventory("minecraft:smoker", 3, 0),
            withInventory("minecraft:cartography_table", 3, 0),
            withInventory("minecraft:stonecutter", 2, 0),
    };

    /** The layout of a menu type id, or {@code null} for an id the client does not register. */
    public static Layout layout(int menuTypeId) {
        return menuTypeId >= 0 && menuTypeId < LAYOUTS.length ? LAYOUTS[menuTypeId] : null;
    }

    public static int size() {
        return LAYOUTS.length;
    }

    /**
     * {@code HorseInventoryMenu} for an {@code AbstractHorse} (horse, donkey, mule, skeleton and zombie horse, llama,
     * trader llama, camel, camel husk): saddle, body armor, {@code 3 * columns} chest slots when {@code columns > 0}, then
     * {@code addStandardInventorySlots} (main 9-35, hotbar 0-8). {@code NautilusInventoryMenu} (nautilus, zombie
     * nautilus): saddle, body armor, the standard slots; it has no chest slots.
     */
    public static int[] mountLayout(boolean horse, int columns) {
        return slots(2 + (horse && columns > 0 ? 3 * columns : 0), 0);
    }

    private static Layout withInventory(String key, int before, int after) {
        return new Layout(key, slots(before, after));
    }

    private static Layout ownOnly(String key, int own) {
        int[] slots = new int[own];
        Arrays.fill(slots, -1);
        return new Layout(key, slots);
    }

    /** {@code before} own slots, {@code addStandardInventorySlots} (main 9-35, hotbar 0-8), {@code after} own slots. */
    private static int[] slots(int before, int after) {
        int[] slots = new int[before + 36 + after];
        Arrays.fill(slots, -1);
        for (int i = 0; i < 27; i++) slots[before + i] = 9 + i;
        for (int i = 0; i < 9; i++) slots[before + 27 + i] = i;
        return slots;
    }
}
