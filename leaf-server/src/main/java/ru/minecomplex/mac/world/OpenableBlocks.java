package ru.minecomplex.mac.world;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;



import ru.minecomplex.mac.replica.CompensatedInventory;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.player.state.ClientPlayerData;
import net.minecraft.world.level.GameType;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import ru.minecomplex.mac.replica.CompensatedWorld;

/** Client performUseItemOn's consuming block path. Exact vanilla door/trapdoor/gate and server-only containers. */
public final class OpenableBlocks {
    private OpenableBlocks() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    public static boolean consumes(BlockRegistry registry, int state, PlayerState player, ClientPlayerData data, ServerboundUseItemOnPacket use) {
        if (state == CompensatedWorld.UNLOADED || use.hand() == net.minecraft.world.InteractionHand.OFF_HAND || data.localMode == GameType.SPECTATOR) return false;
        BlockType block = registry.block(state);
        boolean openable = block.isInstance("DoorBlock") || block.isInstance("TrapDoorBlock") || block.isInstance("FenceGateBlock");
        boolean container = block.isInstance("ChestBlock") || block.isInstance("EnderChestBlock") || block.isInstance("BarrelBlock") || block.isInstance("ShulkerBoxBlock");
        if (!openable && !container) return false;
        // BlockSetType.canOpenByHand is false only for iron in the audited vanilla registries (all thirteen releases).
        if (block.name().equals("minecraft:iron_door") || block.name().equals("minecraft:iron_trapdoor")) return false;
        if (!player.shiftKeyDown) return true;
        CompensatedInventory inventory = data.inventory;
        // Secondary use bypasses block interaction if either hand has an item. Unknown/bounded hands stay unresolved.
        int main = inventory.handSlot(false);
        return main >= 0 && !inventory.unknown(main) && inventory.item(main).isEmpty()
                && !inventory.unknown(CompensatedInventory.OFFHAND) && inventory.item(CompensatedInventory.OFFHAND).isEmpty();
    }
}
