package ru.minecomplex.mac.listener;

import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;
import net.minecraft.world.level.GameType;
import ru.minecomplex.mac.inventory.item.Stacks;
import net.minecraft.world.InteractionHand;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import ru.minecomplex.mac.replica.CompensatedInventory;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.player.ClientAttack;
import ru.minecomplex.mac.player.ClientInteractions;
import ru.minecomplex.mac.player.ClientItemUse;
import ru.minecomplex.mac.player.state.ClientPlayerData;

/**
 * Client-local inventory changes the client announces with serverbound packets (inventory stage I3): the selected
 * hotbar slot ({@code set_carried_item}), item drops and the offhand swap ({@code player_action}) and closing the
 * container menu ({@code container_close}).
 *
 * <p>Vanilla order inside a client tick ({@code Minecraft.tick}): {@code gameMode.tick()} sends {@code set_carried_item}
 * when the selected slot differs from the last sent one ({@code carriedIndex}); {@code handleKeybinds} applies hotbar
 * keys, sends offhand swaps, then drops ({@code MultiPlayerGameMode.dropItem} sends the selected slot first), then
 * attacks and uses. The player tick sends its input and movement, followed by {@code client_tick_end}. A drop uses
 * the already announced selected slot; no historical drop/swing inference is performed.
 *
 * <p>Pass every serverbound packet of the client in wire order, except the replies the player model predicted
 * ({@link PlayerModel.Reply}), after the changes the client had applied; call {@link #tickEnded} at every
 * {@code client_tick_end}.
 */
public final class ClientPlayerActions {

    /**
     * Packets a vanilla client does not send in this state or order.
     */
    public enum Violation {

        /**
         * {@code set_carried_item} outside the hotbar: the selected slot is always 0–8.
         */
        HELD_SLOT_OUT_OF_RANGE,
        /**
         * {@code set_carried_item} with the slot sent last: only a change is sent.
         */
        HELD_SLOT_UNCHANGED,
        /**
         * A drop or offhand swap from a spectator: {@code handleKeybinds} does not send them.
         */
        ACTION_IN_SPECTATOR,
        /**
         * An offhand swap after a drop in one tick: swap keys are handled before drop keys.
         */
        SWAP_AFTER_DROP,
        /**
         * A selected slot, drop or swap after the tick's {@code player_input}: keybinds run before the player tick.
         */
        KEYBIND_AFTER_PLAYER_TICK,
        /**
         * Drops and swings that no hotbar stack produces.
         */

        /**
         * {@code container_close} for a menu the client cannot have open.
         */
        CLOSE_OF_UNKNOWN_CONTAINER,
        /**
         * {@code use_item} in spectator mode: {@code MultiPlayerGameMode.useItem} returns before sending it.
         */
        USE_IN_SPECTATOR
    }

    public record Result(Set<Violation> violations, Set<Unmodeled> gaps, Set<Unmodeled> resolves) {
    }

    /**
     * The target of an interaction as the client world model holds it: the block's registry name at a position, an
     * entity's type key; {@code null} when not known.
     */
    public interface Target {

        Target UNKNOWN = new Target() {

            @Override
            public String block(int x, int y, int z) {
                return null;
            }

            @Override
            public String entityType(int entityId) {
                return null;
            }
        };

        String block(int x, int y, int z);

        String entityType(int entityId);

        /**
         * A resolved block interaction consumed the action before ItemStack.useOn.
         */
        default boolean blockUseConsumes(ServerboundUseItemOnPacket use) {
            return false;
        }

        /**
         * Whether {@code entityId} is the client's {@code removedPlayerVehicleId}: the entity last removed while carrying the
         * local player, with no entity added under that id and no mount of the player since ({@code EITHER}: not known).
         */
        default ru.minecomplex.mac.util.Tri formerVehicle(int entityId) {
            return ru.minecomplex.mac.util.Tri.EITHER;
        }

        /**
         * {@code AbstractBedBlock.getSleepHeight} of the block there (through 26.2 only whether it is a {@code BedBlock}):
         * {@code null} when it is no bed, {@code NaN} when not known.
         */
        default Double sleepHeight(int x, int y, int z) {
            return Double.NaN;
        }

        /**
         * {@code LivingEntity.stopSleeping} of the local player at the bed (x, y, z) with yaw {@code yRot} (unknown by default).
         */
        default ru.minecomplex.mac.world.BedStandUp.Outcome stopSleeping(int x, int y, int z, float yRot, Set<Unmodeled> gaps) {
            return ru.minecomplex.mac.world.BedStandUp.Outcome.UNKNOWN;
        }

        /**
         * {@code level.isRaining()}: {@code FALSE} when the client's level surely is not raining.
         */
        default ru.minecomplex.mac.util.Tri raining() {
            return ru.minecomplex.mac.util.Tri.EITHER;
        }

        /**
         * {@code Entity.isInRain}: {@code level.isRainingAt} at the player's block or at the top of its box above it
         * ({@code BlockPos.containing(x, boundingBox.maxY, z)}); without a world only a dry level decides it.
         */
        default ru.minecomplex.mac.util.Tri inRain(ru.minecomplex.mac.player.state.PlayerState player) {
            return raining() == ru.minecomplex.mac.util.Tri.FALSE ? ru.minecomplex.mac.util.Tri.FALSE : ru.minecomplex.mac.util.Tri.EITHER;
        }

        /**
         * {@code player.move(MoverType.SELF, delta)} in the client's world (a riptide's lift off the ground); adds to
         * {@code gaps} what the move could not resolve.
         */
        default void moveSelf(ru.minecomplex.mac.player.state.PlayerState player, ru.minecomplex.mac.util.math.Vec3d delta, java.util.Set<ru.minecomplex.mac.engine.Unmodeled> gaps) {
            gaps.add(ru.minecomplex.mac.engine.Unmodeled.RIPTIDE);
        }
    }

    private final ClientItemUse itemUse;

    private final ContainerClaims claims;

    private final ClientInteractions interactions;

    private final ClientAttack attack;

    public ClientPlayerActions() {
        this.itemUse = new ClientItemUse();
        this.attack = new ClientAttack();
        this.claims = new ContainerClaims();
        this.interactions = new ClientInteractions(ru.minecomplex.mac.inventory.item.ItemBehavior.get(), true);
    }

    /**
     * Applies one serverbound packet of the client, interactions with an unknown target.
     */
    public Result apply(PlayerState p, ClientPlayerData data, Object event) {
        return apply(p, data, event, Target.UNKNOWN);
    }

    /**
     * Applies one serverbound packet of the client; {@code target} resolves the blocks and entities it interacts with.
     */
    public Result apply(PlayerState p, ClientPlayerData data, Object event, Target target) {
        EnumSet<Violation> violations = EnumSet.noneOf(Violation.class);
        EnumSet<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class);
        EnumSet<Unmodeled> resolves = EnumSet.noneOf(Unmodeled.class);
        TickActions tick = data.tick;
        switch(event) {
            case ServerboundSetCarriedItemPacket held ->
                heldSlot(data, held.getSlot(), violations);
            case ServerboundPlayerActionPacket action ->
                {
                    switch(action.getAction()) {
                        case DROP_ITEM ->
                            drop(p, data, false, violations);
                        case DROP_ALL_ITEMS ->
                            drop(p, data, true, violations);
                        case SWAP_ITEM_WITH_OFFHAND ->
                            swap(p, data, violations);
                        case RELEASE_USE_ITEM ->
                            {
                                // MultiPlayerGameMode.releaseUsingItem: the packet, then LivingEntity.releaseUsingItem
                                gaps.addAll(itemUse.release(p, data, target));
                            }
                        // stopDestroyBlock resets the attack strength (other aborts do not)
                        case ABORT_DESTROY_BLOCK ->
                            ClientAttack.mayReset(data);
                        case STAB ->
                            ClientAttack.stab(data);
                        default ->
                            {
                            }
                    }
                }
            case ServerboundContainerClosePacket closed ->
                close(data, closed.getContainerId(), violations);
            case ServerboundUseItemPacket use ->
                {
                    if (data.localMode == GameType.SPECTATOR) {
                        violations.add(Violation.USE_IN_SPECTATOR);
                    } else {
                        // Minecraft.startUseItem's fallback follows the block/entity interaction only if its stack
                        // is non-empty. The earlier interaction's possible consumption cannot still empty this hand.
                        boolean offHand = use.hand() == InteractionHand.OFF_HAND;
                        data.inventory.confirmHandNonEmpty(offHand);
                        gaps.addAll(itemUse.use(p, data, offHand, target));
                    }
                }
            case ServerboundUseItemOnPacket use ->
                {
                    // performUseItemOn returns before anything for a spectator
                    if (data.localMode != GameType.SPECTATOR && interactions.useItemOn(p, data, use, target)) {
                        data.useMayContinue = true;
                        data.useUncertain = true;
                    }
                }
            case ServerboundInteractPacket interact ->
                {
                    if (data.localMode != GameType.SPECTATOR) {
                        // MultiPlayerGameMode.interact: Player.interactOn unless a spectator
                        interactions.interact(p, data, interact, target);
                    }
                }
            case ServerboundAttackPacket hit ->
                gaps.addAll(attack.attack(p, data, target.entityType(hit.entityId())));
            // a missed attack (Minecraft.startAttack) resets the attack strength before its swing
            case ServerboundPunchPacket ignored ->
                ClientAttack.mayReset(data);
            case ServerboundPlayerInputPacket ignored ->
                tick.playerTicked = true;
            case ServerboundContainerClickPacket click ->
                {
                    if (claims.click(data, click))
                        usedStackReplaced(data, gaps);
                }
            case ServerboundSetCreativeModeSlotPacket creative ->
                {
                    if (claims.creative(data, creative))
                        usedStackReplaced(data, gaps);
                }
            default ->
                {
                }
        }
        // a use may have changed the equipment (an equip swap)
        p.wearingLeatherBoots = data.inventory.walksOnPowderSnow();
        mainHand(data, gaps, resolves);
        inventory(p, data, gaps, resolves);
        ClientItemUse.ambiguity(data, gaps);
        ClientItemUse.useGaps(data, gaps, resolves);
        return new Result(violations, gaps, resolves);
    }

    /**
     * A click or the creative screen replaced the slot of the stack being used: whether the used stack object stayed
     * (shrunk, grown) or moved is not known, so neither is the use.
     */
    private static void usedStackReplaced(ClientPlayerData data, Set<Unmodeled> gaps) {
        data.useMayContinue = true;
        data.useUncertain = true;
    }

    /**
     * {@link Unmodeled#INVENTORY_CLIENT_ACTION} follows whether a player inventory slot is unknown. In creative mode the
     * creative inventory screen may change the carried stack without any packet: it is never known there.
     */
    static void inventory(PlayerState p, ClientPlayerData data, Set<Unmodeled> gaps, Set<Unmodeled> resolves) {
        if (data.localMode == GameType.CREATIVE || p.abilities.instabuild)
            data.inventory.markInventoryCarriedUnknown();
        if (data.inventory.uncertain()) {
            gaps.add(Unmodeled.INVENTORY_CLIENT_ACTION);
        } else {
            resolves.add(Unmodeled.INVENTORY_CLIENT_ACTION);
        }
    }

    /**
     * The client's {@code client_tick_end}.
     */
    public Result tickEnded(PlayerState p, ClientPlayerData data) {
        EnumSet<Violation> violations = EnumSet.noneOf(Violation.class);
        TickActions tick = data.tick;
        // the next tick's gameMode.tick() announces a slot chosen by a hotbar key of this one; until then the slot
        // sent last is the best known (a tick starts with it)
        data.inventory.setSelected(data.carriedIndex);
        data.tick = new TickActions();
        EnumSet<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class), resolves = EnumSet.noneOf(Unmodeled.class);
        mainHand(data, gaps, resolves);
        inventory(p, data, gaps, resolves);
        ClientItemUse.ambiguity(data, gaps);
        ClientItemUse.useGaps(data, gaps, resolves);
        return new Result(violations, gaps, resolves);
    }

    private static void heldSlot(ClientPlayerData data, int slot, Set<Violation> violations) {
        TickActions tick = data.tick;
        if (tick.playerTicked)
            violations.add(Violation.KEYBIND_AFTER_PLAYER_TICK);
        if (!CompensatedInventory.isHotbarSlot(slot)) {
            violations.add(Violation.HELD_SLOT_OUT_OF_RANGE);
            return;
        }
        if (slot == data.carriedIndex)
            violations.add(Violation.HELD_SLOT_UNCHANGED);
        data.carriedIndex = slot;
        data.inventory.setSelected(slot);
    }

    private void drop(PlayerState p, ClientPlayerData data, boolean all, Set<Violation> violations) {
        TickActions tick = data.tick;
        if (tick.playerTicked)
            violations.add(Violation.KEYBIND_AFTER_PLAYER_TICK);
        // handleKeybinds reads LocalPlayer.isSpectator before a drop
        data.playerInfoMode();
        if (p.spectator) {
            violations.add(Violation.ACTION_IN_SPECTATOR);
            return;
        }
        tick.dropSeen = true;
        {
            // dropItem: removeFromSelected, ensureHasSentCarriedItem, drop -- the slot sent last is the dropped one
            CompensatedInventory inventory = data.inventory;
            int slot = data.carriedIndex;
            inventory.setSelected(slot);
            int high = Stacks.count(inventory.item(slot));
            inventory.setHotbarBounds(slot, removed(inventory.minCount(slot), all), removed(high, all));
        }
    }

    private static void swap(PlayerState p, ClientPlayerData data, Set<Violation> violations) {
        // the client only sends the action; the server swaps and sends both slots
        TickActions tick = data.tick;
        if (tick.playerTicked)
            violations.add(Violation.KEYBIND_AFTER_PLAYER_TICK);
        // handleKeybinds reads LocalPlayer.isSpectator before a swap
        data.playerInfoMode();
        if (p.spectator)
            violations.add(Violation.ACTION_IN_SPECTATOR);
        if (tick.dropSeen)
            violations.add(Violation.SWAP_AFTER_DROP);
    }

    /**
     * {@code LocalPlayer.closeContainer}: the id of its menu, then {@code Player.closeContainer()}.
     */
    private static void close(ClientPlayerData data, int containerId, Set<Violation> violations) {
        if (!data.inventory.mayBeOpen(containerId)) {
            violations.add(Violation.CLOSE_OF_UNKNOWN_CONTAINER);
        }
        data.inventory.closeContainer();
    }

    /**
     * {@code removeFromSelected}: {@code split(all ? count : 1)} of a stack of {@code count}.
     */
    private static int removed(int count, boolean all) {
        return count <= 0 || all ? 0 : count - 1;
    }

    /**
     * {@link Unmodeled#DROP_SLOT} follows whether the main hand may have been emptied.
     */
    static void mainHand(ClientPlayerData data, Set<Unmodeled> gaps, Set<Unmodeled> resolves) {
        if (data.inventory.mainHandMayBeEmpty()) {
            gaps.add(Unmodeled.DROP_SLOT);
        } else {
            resolves.add(Unmodeled.DROP_SLOT);
        }
    }
}
