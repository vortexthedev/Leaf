package ru.minecomplex.mac.listener;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import net.minecraft.world.level.GameType;

import static net.minecraft.world.inventory.ContainerInput.*;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import ru.minecomplex.mac.replica.CompensatedInventory;
import net.minecraft.world.item.ItemStack;
import ru.minecomplex.mac.inventory.item.Stacks;
import ru.minecomplex.mac.player.state.ClientPlayerData;

/**
 * Inventory changes the client reports with their result (inventory stage I3, clicks). {@code container_click}: the
 * client ran {@code AbstractContainerMenu.clicked} on its open menu, then sent every slot whose stack no longer
 * {@code ItemStack.matches} the one before and the carried stack (the server, too, takes them as the client's view:
 * {@code setRemoteSlotUnsafe}). {@code set_creative_mode_slot}: the creative screen changed an inventory menu slot.
 *
 * <p>Through 1.21.4 the click carries exact stacks. From 1.21.5 it carries {@code HashedStack}s: item, count and the
 * component types the patch sets or removes, values only as hashes. Sources are resolved against the menu's pre-click
 * native stacks and their complete patch shape. Different possible component values or unknown sources remain uncertain.
 * Hash verification is not implemented: a unique shape-compatible source remains client-reported inventory evidence.
 */
final class ContainerClaims {
    /** A stack and whether the model only guesses it. */
    record Resolved(ItemStack stack, boolean guessed) {
    }

    /**
     * {@code container_click}: the claimed slots and carried stack replace the model's. A click on a menu the model
     * does not hold (another id, an unknown layout) leaves every inventory slot unknown.
     *
     * @return whether a claimed change replaced the slot of the stack being used
     */
    boolean click(ClientPlayerData data, ServerboundContainerClickPacket click) {
        CompensatedInventory inventory = data.inventory;
        int size = inventory.openMenuSize();
        if (click.containerId() != inventory.openContainerId() || size < 0) {
            // MultiPlayerGameMode sends clicks only for its open menu: the model's menu is not the client's
            inventory.markAllUnknown();
            inventory.setCarriedClaimed(inventory.carried(), true);
            return data.usingItem;
        }
        List<Resolved> before = new ArrayList<>(size + 1);
        for (int slot = 0; slot < size; slot++) {
            before.add(new Resolved(inventory.openMenuStack(slot), inventory.openMenuUnknown(slot)));
        }
        before.add(new Resolved(inventory.carried(), inventory.carriedUnknown()));
        // the offhand a swap (button 40) can move, also where it is no slot of the menu
        if (inventory.openMenuSlotOf(CompensatedInventory.OFFHAND) < 0) {
            before.add(new Resolved(inventory.item(CompensatedInventory.OFFHAND), inventory.unknown(CompensatedInventory.OFFHAND)));
        }
        boolean usedSlotChanged = false;
        var clickType = click.containerInput();
        // the claims as stacks: resolved against the menu as it was before the click
        Map<Integer, Resolved> changed = new TreeMap<>();
        var hashedSlots = click.changedSlots();
        hashedSlots.forEach((slot, stack) -> changed.put(slot, resolve(stack, before)));
        if (clickType == SWAP && data.useItemSlot >= 0 && inventory.version(data.useItemSlot) == data.useItemVersion
                && click.slotNum() >= 0 && click.slotNum() < size
                && (click.buttonNum() == data.useItemSlot || inventory.openMenuIndex(click.slotNum()) == data.useItemSlot)) {
            // a swap with an equal stack moves the objects but changes no reported content
            usedSlotChanged = true;
        }
        for (Map.Entry<Integer, Resolved> entry : changed.entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= size) {
                // ServerboundContainerClickPacket names only slots of the open menu
                inventory.markAllUnknown();
                continue;
            }
            Resolved resolved = entry.getValue();
            int index = inventory.openMenuIndex(slot);
            if (index >= 0 && index == data.useItemSlot && inventory.version(index) == data.useItemVersion) usedSlotChanged = true;
            inventory.setOpenMenuClaimed(slot, resolved.stack(), resolved.guessed());
        }
        Resolved carried = resolve(click.carriedItem(), before);
        inventory.setCarriedClaimed(carried.stack(), carried.guessed());
        if (inventory.openMenuSlotOf(CompensatedInventory.OFFHAND) < 0 && offhandMayChange(click, changed, before, inventory)) {
            // the offhand is no slot of this menu: its change is not reported
            inventory.setUnknown(CompensatedInventory.OFFHAND, true);
            if (data.useItemSlot == CompensatedInventory.OFFHAND) usedSlotChanged = true;
        }
        return usedSlotChanged && data.usingItem;
    }

    /**
     * Whether a click on a menu without the offhand slot may have changed the offhand: a swap with it (button 40), or
     * {@code Inventory.add} (which may fill the offhand) of a swap whose target overflows the slot (the hotbar stack is
     * split: its claimed count dropped) or of a crafting result's remainders ({@code ResultSlot.onTake}).
     */
    private static boolean offhandMayChange(ServerboundContainerClickPacket click, Map<Integer, Resolved> changed, List<Resolved> before, CompensatedInventory inventory) {
        if (changed.isEmpty()) return false;
        var clickType = click.containerInput();
        if (clickType == SWAP) {
            if (click.buttonNum() == CompensatedInventory.OFFHAND) return true;
            int hotbarSlot = inventory.openMenuSlotOf(click.buttonNum());
            if (hotbarSlot < 0) return false;
            Resolved after = changed.get(hotbarSlot);
            ItemStack source = before.get(hotbarSlot).stack();
            return after != null && !source.isEmpty() && Stacks.key(after.stack()).equals(Stacks.key(source))
                    && Stacks.count(after.stack()) < Stacks.count(source);
        }
        return "minecraft:crafting".equals(inventory.openMenuKey()) && click.slotNum() == 0
                && (clickType == PICKUP || clickType == QUICK_MOVE);
    }

    /**
     * {@code set_creative_mode_slot}: inventory menu slot {@code slot} holds the stack (the creative screen changes the
     * inventory menu's carried stack without packets: it stays unknown).
     *
     * @return whether the slot of the stack being used was replaced
     */
    boolean creative(ClientPlayerData data, ServerboundSetCreativeModeSlotPacket creative) {
        CompensatedInventory inventory = data.inventory;
        inventory.markInventoryCarriedUnknown();
        int slot = creative.slotNum();
        if (slot < 0 || slot >= CompensatedInventory.MENU_SIZE) return false;
        int index = CompensatedInventory.inventoryIndex(slot);
        boolean usedSlot = index >= 0 && index == data.useItemSlot && inventory.version(index) == data.useItemVersion;
        ItemStack stack = Stacks.of(creative.itemStack());
        if (index >= 0) {
            inventory.replace(index, stack);
            inventory.setUnknown(index, false);
        } else if (inventory.openContainerId() == 0) {
            inventory.setOpenMenuClaimed(slot, stack, false);
        } else {
            inventory.setMenuItem(slot, stack);
        }
        return usedSlot && data.usingItem;
    }

    /**
     * The stack a hashed claim ({@code null}: empty) describes, from the menu's stacks before the click ({@code before},
     * carried last).
     */
    Resolved resolve(HashedStack claim, List<Resolved> before) {
        if (!(claim instanceof HashedStack.ActualItem actual) || actual.count() <= 0 || actual.item().value() == net.minecraft.world.item.Items.AIR) {
            return new Resolved(ItemStack.EMPTY, false);
        }
        var patch = actual.components();
        ItemStack prototype = new ItemStack(actual.item(), actual.count());
        for (var type : patch.removedComponents()) prototype.remove(type);
        if (patch.addedComponents().isEmpty()) return new Resolved(prototype, false);
        ItemStack found = null;
        boolean unique = true, guessed = false;
        for (Resolved candidate : before) {
            ItemStack stack = candidate.stack();
            if (stack.isEmpty() || stack.getItem() != actual.item().value() || !consistent(stack, actual)) continue;
            guessed |= candidate.guessed();
            if (found == null) found = stack;
            else if (!ItemStack.isSameItemSameComponents(found, stack)) unique = false;
        }
        // The hashes carry no values. A missing/ambiguous source must remain uncertain.
        if (found == null) return new Resolved(prototype, true);
        return new Resolved(Stacks.withCount(found, actual.count()), guessed || !unique);
    }

    /** A source's patch must name the claimed additions/removals; values cannot be recovered from hashes. */
    private static boolean consistent(ItemStack stack, HashedStack.ActualItem actual) {
        var patches = stack.getComponentsPatch().split();
        return patches.added().keySet().equals(actual.components().addedComponents().keySet())
                && patches.removed().equals(actual.components().removedComponents());
    }
}
