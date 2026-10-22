package ru.minecomplex.mac.player;

import net.minecraft.world.level.GameType;
import ru.minecomplex.mac.replica.CompensatedInventory;
import net.minecraft.core.component.DataComponents;
import ru.minecomplex.mac.inventory.item.ItemBehavior;
import net.minecraft.world.item.ItemStack;
import ru.minecomplex.mac.inventory.item.Stacks;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.BlockType;
import ru.minecomplex.mac.player.state.ClientPlayerData;

/**
 * Whether {@code MultiPlayerGameMode.destroyBlock} breaks a block in the client's own world (it runs inside the prediction
 * of a {@code player_action}: {@code STOP_DESTROY_BLOCK}, or {@code START_DESTROY_BLOCK} while {@code abilities.instabuild}).
 * Every check that fails leaves the block, so the block breaks only when all pass:
 *
 * <ol>
 *   <li>{@code Player.blockActionRestricted(level, pos, localPlayerMode)}: a spectator never, an adventure player only
 *   with a main-hand stack whose {@code can_break} allows it ({@code mayBuild} is false in adventure; the predicate is not
 *   modelled);</li>
 *   <li>the main-hand item's rule: before 1.21.5 {@code Item.canAttackBlock} ({@code SwordItem}, {@code MaceItem},
 *   {@code TridentItem}: not while {@code isCreative()}, the player's {@code PlayerInfo} game mode; {@code DebugStickItem}:
 *   never), from 1.21.5 {@code ItemStack.canDestroyBlock} ({@code tool} with {@code can_destroy_blocks_in_creative} false:
 *   not while {@code abilities.instabuild}; {@code DebugStickItem}: never) ({@code DESTROY_BLOCK_TOOL_RULE},
 *   item facts from MAIT v4);</li>
 *   <li>a {@code GameMasterBlock} (command, structure, jigsaw, test and test instance blocks) only with
 *   {@code canUseGameMasterBlocks()}: {@code instabuild} and an operator permission the model does not track;</li>
 *   <li>not air.</li>
 * </ol>
 *
 * Breaking then runs {@code playerWillDestroy} (on the client only a decorated pot cracks first) and
 * {@code setBlock(pos, fluid.createLegacyBlock(), 11)}, then {@code destroy} (a moving piston removes its extended base).
 * Those two blocks break with an uncertain world ({@link Outcome#BREAKS_UNCERTAIN}). Audit: every override of
 * {@code playerWillDestroy} and {@code Block.destroy} in 1.21.4, 1.21.11 and 26.3 (bytecode scan), the others act only
 * on a server level.
 */
public final class ClientBlockBreaking {
    private ClientBlockBreaking() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    public static enum Outcome {

        BREAKS,
        /**
         * Breaks, with a client-side side effect on other blocks the model does not follow.
         */
        BREAKS_UNCERTAIN,
        KEEPS,
        UNKNOWN
    }

    private static final String[] GAME_MASTER_BLOCKS = { "CommandBlock", "StructureBlock", "JigsawBlock", "TestBlock", "TestInstanceBlock" };

    /**
     * {@code destroyBlock} for {@code block} (its state {@code air} or not) by this client player.
     */
    public static Outcome destroyBlock(ItemBehavior items, PlayerState p, ClientPlayerData data, BlockType block, boolean air) {
        if (air)
            return Outcome.KEEPS;
        Tri allowed = and(restriction(data), itemRule(items, p, data));
        if (gameMaster(block))
            allowed = and(allowed, p.abilities.instabuild ? Tri.EITHER : Tri.FALSE);
        if (allowed == Tri.FALSE)
            return Outcome.KEEPS;
        if (allowed == Tri.EITHER)
            return Outcome.UNKNOWN;
        return block.isInstance("DecoratedPotBlock") || block.isInstance("MovingPistonBlock") ? Outcome.BREAKS_UNCERTAIN : Outcome.BREAKS;
    }

    /**
     * {@code !blockActionRestricted}.
     */
    private static Tri restriction(ClientPlayerData data) {
        if (data.localMode == null)
            return Tri.EITHER;
        return switch(data.localMode) {
            case SPECTATOR ->
                Tri.FALSE;
            // mayBuild is false in adventure: the main-hand stack's can_break decides, an empty hand never breaks
            case ADVENTURE ->
                mainHand(data, stack -> stack.isEmpty() ? Tri.FALSE : Tri.EITHER);
            default ->
                Tri.TRUE;
        };
    }

    /**
     * The main-hand item's rule.
     */
    private static Tri itemRule(ItemBehavior items, PlayerState p, ClientPlayerData data) {
        return mainHand(data, stack -> {
            if (stack.isEmpty())
                return Tri.TRUE;
            String override = items.destroyRuleOverride(Stacks.key(stack));
            return switch(override) {
                case "" ->
                    {
                        var added = stack.getComponentsPatch().split().added();
                        if (added.keySet().contains(DataComponents.TOOL))
                            yield Tri.EITHER;
                        var tool = stack.getOrDefault(DataComponents.TOOL, null);
                        yield tool != null && !tool.canDestroyBlocksInCreative() ? Tri.of(!p.abilities.instabuild) : Tri.TRUE;
                    }
                case "DebugStickItem" ->
                    Tri.FALSE;
                default ->
                    Tri.EITHER;
            };
        });
    }

    /**
     * A rule of the main-hand stack, uncertain when the model does not know the stack or it may have been used up.
     */
    private static Tri mainHand(ClientPlayerData data, java.util.function.Function<ItemStack, Tri> rule) {
        CompensatedInventory inventory = data.inventory;
        int slot = inventory.handSlot(false);
        if (slot < 0 || inventory.unknown(slot))
            return Tri.EITHER;
        ItemStack stack = inventory.item(slot);
        Tri result = rule.apply(stack);
        if (!stack.isEmpty() && inventory.minCount(slot) == 0 && rule.apply(ItemStack.EMPTY) != result)
            return Tri.EITHER;
        return result;
    }

    private static boolean gameMaster(BlockType block) {
        for (String name : GAME_MASTER_BLOCKS) if (block.isInstance(name))
            return true;
        return false;
    }

    private static Tri and(Tri a, Tri b) {
        if (a == Tri.FALSE || b == Tri.FALSE)
            return Tri.FALSE;
        if (a == Tri.EITHER || b == Tri.EITHER)
            return Tri.EITHER;
        return Tri.TRUE;
    }
}
