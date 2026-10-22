package ru.minecomplex.mac.player;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import net.minecraft.core.component.DataComponents;
import ru.minecomplex.mac.inventory.item.ItemMechanics;
import ru.minecomplex.mac.replica.CompensatedInventory;
import ru.minecomplex.mac.inventory.item.ItemBehavior;
import net.minecraft.world.item.ItemStack;
import ru.minecomplex.mac.inventory.item.Stacks;
import ru.minecomplex.mac.player.state.PlayerState;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import ru.minecomplex.mac.listener.ClientPlayerActions;
import ru.minecomplex.mac.player.state.ClientPlayerData;

/**
 * What {@code use_item_on} and entity interactions may change in the client's inventory. The client runs the block's
 * {@code useItemOn}/{@code useWithoutItem}, the item's {@code useOn}, or {@code Player.interactOn} with the entity's
 * {@code interact}/{@code mobInteract} and the item's {@code interactLivingEntity}, decided by the client's world and
 * entities, which the model does not simulate. Audit of every path from these methods to a stack or inventory write
 * (bytecode call graph with virtual dispatch, 1.21.4 and 26.3; docs/research/architecture.md): the used hand's stack
 * loses at most one item ({@code consume(1)}, {@code shrink(1)}, {@code split(1)}, a transformer consuming 0 or 1),
 * except the paths below that replace it or add a stack elsewhere ({@code Inventory.add}, {@code Player.addItem}).
 *
 * <ul>
 *   <li>blocks: a filled flower pot gives its plant to the main hand's inventory ({@code useWithoutItem}); a beehive
 *   turns a glass bottle into honey (one: the hand, several: added); a lodestone makes a lodestone compass (several or
 *   infinite materials: added);</li>
 *   <li>items: a water potion turns into a glass bottle on mud-convertible blocks ({@code createFilledResult}: several
 *   or infinite: added); a powder snow bucket placed leaves an empty bucket; a brush may start using; a block item with
 *   {@code consumable} whose placing fails runs {@code Item.use} (eating may start);</li>
 *   <li>entities: an armor stand swaps the hand with its slot; cows and goats fill a bucket, mooshrooms a bowl, fish,
 *   axolotls, tadpoles and sulfur cubes go into a water bucket, axolotls and nautiluses eat a fish bucket
 *   ({@code createFilledResult}: several or infinite: added); a mob eating an item with {@code use_remainder} leaves the
 *   remainder; an allay hands its item to an empty main hand ({@code addItem}).</li>
 * </ul>
 */
public final class ClientInteractions {
    private final ItemBehavior items;
    /** 26.1+: one interact packet runs both {@code interactAt} and {@code interact} paths. */
    private final boolean singleInteract;

    public ClientInteractions(ItemBehavior items, boolean singleInteract) {
        this.items = items;
        this.singleInteract = singleInteract;
    }

    /** A block the client world holds, or {@code null} when the model does not know it. */
    private static boolean maybe(String block, String... names) {
        if (block == null) return true;
        for (String name : names) if (block.equals(name)) return true;
        return false;
    }

    /**
     * {@code use_item_on} (not a spectator's).
     *
     * @return whether the client may have started using the hand's item
     */
    public boolean useItemOn(PlayerState p, ClientPlayerData data, ServerboundUseItemOnPacket use, ClientPlayerActions.Target target) {
        boolean offHand = use.hand() == InteractionHand.OFF_HAND;
        var position = use.hitResult().getBlockPos();
        if (target.blockUseConsumes(use)) return false;
        CompensatedInventory inventory = data.inventory;
        int slot = inventory.handSlot(offHand);
        if (slot < 0) return false;
        ItemStack hand = inventory.item(slot);
        String block = target.block(position.getX(), position.getY(), position.getZ());
        boolean infinite = p.abilities.instabuild;
        boolean startsUsing = false;
        if (!offHand && (block == null || block.startsWith("minecraft:potted_"))) {
            // FlowerPotBlock.useWithoutItem: player.addItem(plant)
            inventory.markAllUnknown();
        }
        if (hand.isEmpty()) return false;
        boolean several = Stacks.count(hand) > 1;
        if (Stacks.is(hand, "minecraft:compass") && (several || infinite) && maybe(block, "minecraft:lodestone")
                || Stacks.is(hand, "minecraft:glass_bottle") && several && maybe(block, "minecraft:beehive", "minecraft:bee_nest")
                || Stacks.is(hand, "minecraft:potion") && (several || infinite)) {
            inventory.markAllUnknown();
        }
        if (Stacks.is(hand, "minecraft:potion") || Stacks.is(hand, "minecraft:glass_bottle") || Stacks.is(hand, "minecraft:powder_snow_bucket")) {
            inventory.setUnknown(slot, true);
        } else if (items.startsUsingOnBlock(Stacks.key(hand))) {
            startsUsing = !data.usingItem;
            inventory.mayLoseOne(slot);
        } else if (items.blockItemUseOn(Stacks.key(hand)) && hand.has(DataComponents.CONSUMABLE)) {
            // a failed placing eats: Consumable.startConsuming, or an instant consumption with its side effects
            startsUsing = !data.usingItem;
            inventory.setUnknown(slot, true);
        } else {
            inventory.mayLoseOne(slot);
        }
        return startsUsing;
    }

    /** {@code interact}/{@code interact_at} with an entity (not a spectator's). */
    public void interact(PlayerState p, ClientPlayerData data, ServerboundInteractPacket interact, ClientPlayerActions.Target target) {
        boolean offHand = interact.hand() == InteractionHand.OFF_HAND;
        boolean at = interact.location() != null;
        CompensatedInventory inventory = data.inventory;
        int slot = inventory.handSlot(offHand);
        if (slot < 0) return;
        ItemStack hand = inventory.item(slot);
        String type = target.entityType(interact.entityId());
        boolean infinite = p.abilities.instabuild;
        boolean atPath = singleInteract || at, interactPath = singleInteract || !at;
        if (atPath && maybe(type, "minecraft:armor_stand") && (hand.isEmpty() || Stacks.count(hand) <= 1)) {
            // ArmorStand.swapItem: the hand gets the stand's stack
            inventory.setUnknown(slot, true);
            return;
        }
        if (atPath && !interactPath) {
            // an armor stand taking one of several items
            if (maybe(type, "minecraft:armor_stand")) inventory.mayLoseOne(slot);
            return;
        }
        if (!offHand && hand.isEmpty() && maybe(type, "minecraft:allay")) {
            // Allay.mobInteract: player.addItem(the allay's item)
            inventory.markAllUnknown();
        }
        if (hand.isEmpty()) return;
        boolean container = Stacks.is(hand, "minecraft:bucket") || Stacks.is(hand, "minecraft:bowl") || Stacks.key(hand).endsWith("_bucket");
        boolean filled = container && maybe(type, "minecraft:cow", "minecraft:mooshroom", "minecraft:goat", "minecraft:cod",
                "minecraft:salmon", "minecraft:pufferfish", "minecraft:tropical_fish", "minecraft:axolotl", "minecraft:tadpole",
                "minecraft:sulfur_cube", "minecraft:nautilus", "minecraft:zombie_nautilus");
        if (filled) {
            inventory.setUnknown(slot, true);
            if (Stacks.count(hand) > 1 || infinite) inventory.markAllUnknown();
        } else if (hand.has(DataComponents.USE_REMAINDER)) {
            // Mob.usePlayerItem: an emptied stack becomes its remainder
            inventory.setUnknown(slot, true);
        } else {
            inventory.mayLoseOne(slot);
        }
    }
}
