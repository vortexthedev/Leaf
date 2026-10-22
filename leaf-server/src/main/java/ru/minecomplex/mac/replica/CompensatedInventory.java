package ru.minecomplex.mac.replica;

import net.minecraft.core.component.DataComponents;
import ru.minecomplex.mac.inventory.item.ItemMechanics;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import ru.minecomplex.mac.inventory.EquipmentSlot;
import ru.minecomplex.mac.inventory.item.Stacks;

/**
 * The LocalPlayer's {@code Inventory} and {@code InventoryMenu} as the client holds them: 41 inventory slots
 * (0–8 hotbar, 9–35 main, 36 feet, 37 legs, 38 chest, 39 head, 40 offhand), the four crafting slots and result of the
 * inventory menu, the selected hotbar slot, the carried (cursor) stack, the inventory menu's state id and the id of
 * the open container menu (0 when only the inventory menu is open) with its slot layout and state id. Forked with
 * the player hypothesis.
 *
 * <p>Counts can be bounded instead of exact: through 26.2 a drop may leave its slot unknown, and a block or entity
 * interaction may have taken one item from the hand ({@code ClientPlayerActions}); the client's count of slot {@code i}
 * then lies in
 * {@code [count(item(i)) - mayLose(i), count(item(i))]} (item and components are those of {@code item(i)}). A server
 * write of the slot makes it exact again.
 *
 * <p>A slot (or carried stack) can also be unknown: the client changed it in a way the model could not follow (a
 * hashed click result whose components no stack of the menu explains, an interaction with the client's world). Its
 * stack is then the model's best guess; a server write of it, or a full inventory menu content for every slot of the
 * inventory, makes it exact again.
 */
public final class CompensatedInventory {
    public static final int SIZE = 41;
    public static final int FEET = 36;
    public static final int LEGS = 37;
    public static final int CHEST = 38;
    public static final int HEAD = 39;
    public static final int OFFHAND = 40;
    /** {@code InventoryMenu}: result 0, crafting 1–4, armor 5–8 (head first), main 9–35, hotbar 36–44, offhand 45. */
    public static final int MENU_SIZE = 46;

    private final ItemStack[] items = new ItemStack[SIZE];
    private final ItemStack[] crafting = new ItemStack[5];
    private int selected;
    /** Every menu has its own carried stack: the inventory menu's survives an open container. */
    private ItemStack inventoryCarried = ItemStack.EMPTY;
    private ItemStack containerCarried = ItemStack.EMPTY;
    private int stateId;
    private int openContainerId;
    /** Slot layout of the open container menu; {@code null} when it is unknown (loom, mount menus). */
    private int[] openLayout;
    /** Registry key of the open container menu's type ({@code null}: none or not known). */
    private String openMenuKey;
    /**
     * Menus whose {@code slotsChanged} recomputes their own slots on the client (a result from the inputs: cartography,
     * grindstone, anvil, smithing, loom, merchant, stonecutter); the model does not compute them, so after a change of the
     * menu their own slots are unknown. (Crafting and enchanting compute only on the server.)
     */
    private static final java.util.Set<String> COMPUTING = java.util.Set.of("minecraft:cartography_table", "minecraft:grindstone",
            "minecraft:anvil", "minecraft:smithing", "minecraft:loom", "minecraft:merchant", "minecraft:stonecutter");
    private int openStateId;
    /**
     * After {@code mount_screen_open} the client either opened a mount menu with this id (horse or nautilus entity)
     * or kept its menu; −1 when no such uncertainty is pending.
     */
    private int pendingMountId = -1;
    private final int[] mayLose = new int[SIZE];
    /** Per inventory slot: bumped whenever the slot gets a new stack object (vanilla's identity of the stack). */
    private final int[] versions = new int[SIZE];
    /**
     * One stack object followed past its slot ({@code LivingEntity.useItem}): its slot and version, and its last state
     * once the slot got another object.
     */
    private int pinnedSlot = -1;
    private int pinnedVersion;
    private ItemStack pinnedFinal;
    private int pinnedMayLose;
    /** The open container's own slots (layout −1; {@code null} entries elsewhere), and which of them are unknown. */
    private ItemStack[] openItems = new ItemStack[0];
    private boolean[] openUnknown = new boolean[0];
    /**
     * A container's own slot written by the server: its container ({@code SimpleContainer.setItem}) cut the count to the
     * stack's maximum size ({@code limitSize}), which the model does not know; item and components are exact.
     */
    private boolean[] openCountUnknown = new boolean[0];
    private final boolean[] unknown = new boolean[SIZE];
    private final boolean[] craftingUnknown = new boolean[5];
    private boolean inventoryCarriedUnknown;
    private boolean containerCarriedUnknown;

    public CompensatedInventory() {
        Arrays.fill(items, ItemStack.EMPTY);
        Arrays.fill(crafting, ItemStack.EMPTY);
    }

    public CompensatedInventory copy() {
        CompensatedInventory copy = new CompensatedInventory();
        System.arraycopy(items, 0, copy.items, 0, SIZE);
        System.arraycopy(crafting, 0, copy.crafting, 0, crafting.length);
        copy.selected = selected;
        copy.inventoryCarried = inventoryCarried;
        copy.containerCarried = containerCarried;
        copy.stateId = stateId;
        copy.openContainerId = openContainerId;
        copy.openLayout = openLayout;
        copy.openMenuKey = openMenuKey;
        copy.openStateId = openStateId;
        copy.pendingMountId = pendingMountId;
        System.arraycopy(mayLose, 0, copy.mayLose, 0, mayLose.length);
        System.arraycopy(versions, 0, copy.versions, 0, versions.length);
        copy.pinnedSlot = pinnedSlot;
        copy.pinnedVersion = pinnedVersion;
        copy.pinnedFinal = pinnedFinal;
        copy.pinnedMayLose = pinnedMayLose;
        copy.openItems = openItems.clone();
        copy.openUnknown = openUnknown.clone();
        copy.openCountUnknown = openCountUnknown.clone();
        System.arraycopy(unknown, 0, copy.unknown, 0, SIZE);
        System.arraycopy(craftingUnknown, 0, copy.craftingUnknown, 0, craftingUnknown.length);
        copy.inventoryCarriedUnknown = inventoryCarriedUnknown;
        copy.containerCarriedUnknown = containerCarriedUnknown;
        return copy;
    }

    public ItemStack item(int index) {
        return items[index];
    }

    /** {@code Inventory.setItem}: indices outside 0–40 are ignored. */
    public void setItem(int index, ItemStack stack) {
        if (index >= 0 && index < SIZE) {
            write(index, stack);
        }
    }

    /** A server write: the client holds exactly this stack, a new object. */
    private void write(int index, ItemStack stack) {
        replace(index, stack);
        unknown[index] = false;
    }

    /** A client-local {@code setItem} with a new stack object (an equip swap, a use turning the item into another). */
    public void replace(int index, ItemStack stack) {
        if (index == pinnedSlot && versions[index] == pinnedVersion) {
            pinnedFinal = items[index];
            pinnedMayLose = mayLose(index);
        }
        items[index] = stack;
        versions[index]++;
        if (index < mayLose.length) mayLose[index] = 0;
    }

    /** Follows the stack object slot {@code index} holds now ({@code -1}: none). */
    public void pin(int index) {
        pinnedSlot = index;
        pinnedVersion = index < 0 ? 0 : versions[index];
        pinnedFinal = null;
        pinnedMayLose = 0;
    }

    /**
     * The state of the stack object slot {@code index} held at {@code version}: the slot's while it still holds it,
     * else its last state if it is the pinned one, else {@code null}.
     */
    public ItemStack object(int index, int version) {
        if (index < 0) return null;
        if (versions[index] == version) return items[index];
        return index == pinnedSlot && version == pinnedVersion ? pinnedFinal : null;
    }

    /** How many items an unplaced drop may have taken from that stack object ({@link #mayLose} while in its slot). */
    public int objectMayLose(int index, int version) {
        if (index < 0) return 0;
        if (versions[index] == version) return mayLose(index);
        return index == pinnedSlot && version == pinnedVersion ? pinnedMayLose : 0;
    }

    /** A client-local change of the same stack object ({@code shrink}, a component set on it). */
    public void modify(int index, ItemStack stack) {
        items[index] = stack;
    }

    /** How many times slot {@code index} got a new stack object. */
    public int version(int index) {
        return versions[index];
    }

    /** {@code getItemInHand}: the selected hotbar slot or the offhand. */
    public int handSlot(boolean offHand) {
        return offHand ? OFFHAND : isHotbarSlot(selected) ? selected : -1;
    }

    /** How many items hotbar slot {@code index} may hold less than {@link #item} says (0 elsewhere). */
    public int mayLose(int index) {
        return index >= 0 && index < mayLose.length ? mayLose[index] : 0;
    }

    /** The lowest count the client may hold in slot {@code index}. */
    public int minCount(int index) {
        return Math.max(0, Stacks.count(items[index]) - mayLose(index));
    }

    /**
     * Minecraft.startUseItem sends use_item only for a non-empty stack. Narrow a known hand's count bound,
     * retaining its upper bound and any unknown item/components rather than assigning a guessed stack.
     */
    public void confirmHandNonEmpty(boolean offHand) {
        int index = handSlot(offHand);
        if (index < 0 || unknown[index] || items[index].isEmpty()) return;
        mayLose[index] = Math.min(mayLose[index], Stacks.count(items[index]) - 1);
    }

    /**
     * Sets hotbar slot {@code index} to the stack it holds with between {@code low} and {@code high} items (client-local
     * drops; {@code high} is at most the current count).
     */
    public void setHotbarBounds(int index, int low, int high) {
        if (high <= 0) {
            items[index] = ItemStack.EMPTY;
            mayLose[index] = 0;
        } else {
            items[index] = Stacks.withCount(items[index], high);
            mayLose[index] = high - Math.max(0, low);
        }
    }

    /** Marks inventory slot {@code index} unknown (or known again: a client change the model followed exactly). */
    public void setUnknown(int index, boolean value) {
        unknown[index] = value;
    }

    /** Every inventory slot may differ from the model (items added where the model cannot tell). */
    public void markAllUnknown() {
        Arrays.fill(unknown, true);
    }

    /** Whether inventory slot {@code index} may differ from {@link #item}. */
    public boolean unknown(int index) {
        return unknown[index];
    }

    /** Whether the open menu's carried stack may differ from {@link #carried}. */
    public boolean carriedUnknown() {
        return openContainerId != 0 ? containerCarriedUnknown : inventoryCarriedUnknown;
    }

    /** The inventory menu's carried stack may differ (the creative screen changes it without packets). */
    public void markInventoryCarriedUnknown() {
        inventoryCarriedUnknown = true;
    }

    /**
     * Whether a player inventory slot may differ from the client (the crafting grid, a container's own slots and the
     * carried stack only matter through the slots they fill, which a guess leaves unknown in turn).
     */
    public boolean uncertain() {
        for (boolean value : unknown) if (value) return true;
        return false;
    }

    /** A full inventory menu content: every inventory slot, the crafting grid and the inventory carried stack are exact. */
    public void inventoryContentKnown() {
        Arrays.fill(unknown, false);
        Arrays.fill(craftingUnknown, false);
        inventoryCarriedUnknown = false;
    }

    /** The open container menu's type key ({@code null} for the inventory menu or an unknown type). */
    public String openMenuKey() {
        return openMenuKey;
    }

    /** The menu slot of the open menu showing inventory slot {@code index}, or −1 when the menu has none. */
    public int openMenuSlotOf(int index) {
        if (openContainerId == 0) {
            for (int slot = 0; slot < MENU_SIZE; slot++) if (inventoryIndex(slot) == index) return slot;
            return -1;
        }
        for (int slot = 0; slot < openLayout.length; slot++) if (openLayout[slot] == index) return slot;
        return -1;
    }

    /** The number of slots of the open menu ({@code player.containerMenu.slots.size()}), −1 when its layout is unknown. */
    public int openMenuSize() {
        if (!openMenuKnown()) return -1;
        return openContainerId == 0 ? MENU_SIZE : openLayout.length;
    }

    /** The stack in slot {@code menuSlot} of the open menu. */
    public ItemStack openMenuStack(int menuSlot) {
        if (openContainerId == 0) return menuItem(menuSlot);
        int index = openLayout[menuSlot];
        return index >= 0 ? items[index] : openItems[menuSlot];
    }

    /** Whether slot {@code menuSlot} of the open menu may differ from {@link #openMenuStack}. */
    public boolean openMenuUnknown(int menuSlot) {
        int index = openContainerId == 0 ? inventoryIndex(menuSlot) : openLayout[menuSlot];
        if (index >= 0) return unknown(index);
        return openContainerId == 0 ? craftingUnknown[menuSlot] : openUnknown[menuSlot];
    }

    /** The inventory index behind slot {@code menuSlot} of the open menu, −1 for a slot of the container itself. */
    public int openMenuIndex(int menuSlot) {
        return openContainerId == 0 ? inventoryIndex(menuSlot) : openLayout[menuSlot];
    }

    /**
     * A client-local change of slot {@code menuSlot} of the open menu to a new stack object ({@code clicked}, the
     * creative screen), with whether the model only guesses it.
     */
    public void setOpenMenuClaimed(int menuSlot, ItemStack stack, boolean guessed) {
        int index = openMenuIndex(menuSlot);
        if (index >= 0) {
            replace(index, stack);
            unknown[index] = guessed;
        } else if (openContainerId == 0) {
            crafting[menuSlot] = stack;
            craftingUnknown[menuSlot] = guessed;
        } else {
            openItems[menuSlot] = stack;
            openUnknown[menuSlot] = guessed;
            openCountUnknown[menuSlot] = false;
        }
        if (openContainerId != 0) recomputed();
    }

    /** A client-local change of the open menu's carried stack, with whether the model only guesses it. */
    public void setCarriedClaimed(ItemStack stack, boolean guessed) {
        if (openContainerId != 0) {
            containerCarried = stack;
            containerCarriedUnknown = guessed;
        } else {
            inventoryCarried = stack;
            inventoryCarriedUnknown = guessed;
        }
    }

    /** Slot {@code index} may hold one item less than the model's bounds allow (an interaction took one). */
    public void mayLoseOne(int index) {
        if (items[index].isEmpty()) return;
        mayLose[index] = Math.min(Stacks.count(items[index]), mayLose[index] + 1);
    }

    /** Whether the main hand may be empty although {@link #equipment} has a stack there (an unknown drop slot). */
    public boolean mainHandMayBeEmpty() {
        return isHotbarSlot(selected) && !items[selected].isEmpty() && minCount(selected) == 0;
    }

    /** The inventory index behind an {@code InventoryMenu} slot, or −1 for the crafting result and grid. */
    public static int inventoryIndex(int menuSlot) {
        if (menuSlot >= 5 && menuSlot <= 8) return HEAD - (menuSlot - 5);
        if (menuSlot >= 9 && menuSlot <= 35) return menuSlot;
        if (menuSlot >= 36 && menuSlot <= 44) return menuSlot - 36;
        if (menuSlot == 45) return OFFHAND;
        return -1;
    }

    /** {@code inventoryMenu.getSlot(slot).set(stack)}; slots outside the menu throw in vanilla (the client errors). */
    public void setMenuItem(int menuSlot, ItemStack stack) {
        if (menuSlot < 0 || menuSlot >= MENU_SIZE) {
            throw new IndexOutOfBoundsException("Inventory menu slot " + menuSlot);
        }
        int index = inventoryIndex(menuSlot);
        if (index >= 0) {
            write(index, stack);
        } else {
            crafting[menuSlot] = stack;
            craftingUnknown[menuSlot] = false;
        }
    }

    public ItemStack menuItem(int menuSlot) {
        int index = inventoryIndex(menuSlot);
        return index >= 0 ? items[index] : crafting[menuSlot];
    }

    public int selected() {
        return selected;
    }

    /** {@code Inventory.setSelectedSlot} (the {@code selected} field up to 1.21.4). */
    public void setSelected(int slot) {
        selected = slot;
    }

    /** {@code Inventory.isHotbarSlot}. */
    public static boolean isHotbarSlot(int slot) {
        return slot >= 0 && slot < 9;
    }

    /** {@code player.containerMenu.getCarried()}: the open menu's carried stack. */
    public ItemStack carried() {
        return openContainerId != 0 ? containerCarried : inventoryCarried;
    }

    /** {@code containerMenu.setCarried}: the open menu's. */
    public void setCarried(ItemStack stack) {
        setCarriedClaimed(stack, false);
    }

    /** {@code inventoryMenu.setCarried} (content for container 0), whatever menu is open. */
    public void setInventoryCarried(ItemStack stack) {
        inventoryCarried = stack;
        inventoryCarriedUnknown = false;
    }

    public int stateId() {
        return stateId;
    }

    public void setStateId(int stateId) {
        this.stateId = stateId;
    }

    public int openContainerId() {
        return openContainerId;
    }

    /**
     * A new container menu becomes the open menu: empty carried stack, state id 0, and its slot layout ({@code null}
     * when unknown).
     */
    public void openContainer(int containerId, int[] layout) {
        openContainer(containerId, layout, null);
    }

    /** {@link #openContainer(int, int[])} with the menu type's registry key. */
    public void openContainer(int containerId, int[] layout, String key) {
        openContainerId = containerId;
        openLayout = layout;
        openMenuKey = key;
        openStateId = 0;
        containerCarried = ItemStack.EMPTY;
        containerCarriedUnknown = false;
        pendingMountId = -1;
        int size = layout == null ? 0 : layout.length;
        openItems = new ItemStack[size];
        openUnknown = new boolean[size];
        openCountUnknown = new boolean[size];
        for (int slot = 0; slot < size; slot++) {
            if (layout[slot] < 0) openItems[slot] = ItemStack.EMPTY;
        }
    }

    /**
     * {@code mount_screen_open}: the menu becomes a mount menu only when the entity is a horse (or nautilus) on the
     * client; until the next open or close the open menu is one of the two.
     */
    public void mountScreenOpen(int containerId) {
        pendingMountId = containerId;
    }

    /** {@code Player.closeContainer()}: back to the inventory menu. */
    public void closeContainer() {
        openContainerId = 0;
        openLayout = null;
        openMenuKey = null;
        openStateId = 0;
        containerCarried = ItemStack.EMPTY;
        containerCarriedUnknown = false;
        pendingMountId = -1;
        openItems = new ItemStack[0];
        openUnknown = new boolean[0];
        openCountUnknown = new boolean[0];
    }

    /** Whether the open menu and its slot layout are known (the inventory menu alone counts as known). */
    public boolean openMenuKnown() {
        return pendingMountId < 0 && (openContainerId == 0 || openLayout != null);
    }

    /** Whether a {@code mount_screen_open} left the open menu uncertain. */
    public boolean mountPending() {
        return pendingMountId >= 0;
    }

    /** Whether a container id ≠ 0 may name the client's open menu. */
    public boolean mayBeOpen(int containerId) {
        return containerId == openContainerId || containerId == pendingMountId;
    }

    public int openStateId() {
        return openStateId;
    }

    /**
     * {@code containerMenu.setItem(slot, stateId, item)} on the open container: the player inventory slot behind it
     * changes; slots outside the menu throw in vanilla. The layout must be known.
     */
    public void setOpenMenuItem(int menuSlot, ItemStack stack) {
        if (menuSlot < 0 || menuSlot >= openLayout.length) {
            throw new IndexOutOfBoundsException("Container menu slot " + menuSlot);
        }
        int index = openLayout[menuSlot];
        if (index >= 0) {
            write(index, stack);
        } else {
            openItems[menuSlot] = stack;
            openUnknown[menuSlot] = false;
            openCountUnknown[menuSlot] = Stacks.count(stack) > 1;
        }
        recomputed();
    }

    /** Whether the count of slot {@code menuSlot} of the open menu may be less than {@link #openMenuStack} says. */
    public boolean openMenuCountUnknown(int menuSlot) {
        return openContainerId != 0 && openLayout != null && openLayout[menuSlot] < 0 && openCountUnknown[menuSlot];
    }

    /** A change of a computing menu: its own slots are unknown ({@link #COMPUTING}). */
    private void recomputed() {
        if (openMenuKey == null || !COMPUTING.contains(openMenuKey)) return;
        for (int slot = 0; slot < openLayout.length; slot++) {
            if (openLayout[slot] < 0) openUnknown[slot] = true;
        }
    }

    public void setOpenStateId(int stateId) {
        openStateId = stateId;
    }

    /** {@code containerMenu.setCarried} for content of the open container. */
    public void setContainerCarried(ItemStack stack) {
        containerCarried = stack;
        containerCarriedUnknown = false;
    }

    /** {@code Player.getItemBySlot}: hands and humanoid armor; a player has nothing in body or saddle slots. */
    public ItemStack equipment(EquipmentSlot slot) {
        return switch (slot) {
            case MAINHAND -> isHotbarSlot(selected) ? items[selected] : ItemStack.EMPTY;
            case OFFHAND -> items[OFFHAND];
            case FEET -> items[FEET];
            case LEGS -> items[LEGS];
            case CHEST -> items[CHEST];
            case HEAD -> items[HEAD];
            case BODY, SADDLE -> ItemStack.EMPTY;
        };
    }

    /** The equipment part of {@code LivingEntity.canGlide()}: some slot's item can glide in that slot. */
    public boolean equipmentCanGlide() {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (ItemMechanics.canGlideUsing(equipment(slot), slot)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The item part of {@code PowderSnowBlock.canEntityWalkOnPowderSnow}: leather boots in the feet slot (the entity
     * type tag {@code powder_snow_walkable_mobs} is a separate, tag-dependent fact).
     */
    public boolean walksOnPowderSnow() {
        return Stacks.is(equipment(EquipmentSlot.FEET), "minecraft:leather_boots");
    }
}
