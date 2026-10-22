package ru.minecomplex.mac.player;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.inventory.item.Stacks;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

/** Named client use, authoritative server consequences and actual bundle menu clicks on vanilla 26.3. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ItemServerOutcomeOracleTest {
    static { ru.minecomplex.mac.packet.NativeRuntime.ensure(); }

    @Test
    void authoritativeOutcomesAndClientInventoryLifecycle()throws Exception{
        PlayerSyncOracleTest.verify("item-outcomes",true);
    }
    static void checkNames(Set<String> actual){
        var expected=new HashSet<String>();
        var names=new ArrayList<>(List.of("pearl_teleport","chorus_teleport","wind_charge_impulse","mace_wind_burst",
                "potion_effects","milk_removal","totem_recovery","bundle_manipulation","armor_swap","crossbow_charged",
                "crossbow_multishot","crossbow_quick_charge","horn_cooldown","loyalty_throw","food_completion","honey_completion"));
        if(Stacks.of("minecraft:shield", 1).has(net.minecraft.core.component.DataComponents.BLOCKS_ATTACKS))names.addAll(List.of("sword_blocks_attacks","axe_blocks_attacks"));
        for(String name:names)for(boolean off:new boolean[]{false,true})expected.add(name+"/"+off);
        assertEquals(expected,actual,"Every named category in both hands; component-blocking only where present");
    }
}
