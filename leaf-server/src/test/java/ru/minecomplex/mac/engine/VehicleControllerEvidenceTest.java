package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import java.util.Set;

import ru.minecomplex.mac.replica.CompensatedEntities;
import ru.minecomplex.mac.frame.ClientTickFrame;

import net.minecraft.world.item.ItemStack;

import ru.minecomplex.mac.fixture.FixtureItems;
import ru.minecomplex.mac.fixture.WireItem;
import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.fixture.inbound.*;
import ru.minecomplex.mac.fixture.outbound.*;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.util.Tri;

/** Riding packets prove tick authority, but a handler reply or a missing saddle/item cannot manufacture it. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class VehicleControllerEvidenceTest {
    @Test
    void aCorrectionReplyIsNotATickControllerAndAnUnannouncedSlotIsNotAnInventoryAssignment() {
        var entities = new CompensatedEntities(7);
        entities.spawn(Wire.of(new EntitySpawn(22,"minecraft:pig",8.5,16,8.5,0F,0F,0F,0,false,0,0,0)),chunk -> true);
        entities.apply(Wire.of(new EntityPassengers(22,new int[]{7})));
        entities.equipment(Wire.of(EntityEquipment.of(22,WireItem.of("minecraft:saddle",1),null)));
        var data = new ClientPlayerData();
        data.inventory.setItem(0,FixtureItems.stack(WireItem.of("minecraft:carrot_on_a_stick",1)));
        data.inventory.setSelected(1);
        var probe = new VehicleCheckRunner();
        var rot = Movement.rot(0F,0F,false,false);
        var vehicle = new VehicleMovement(8.5,16,8.5,0F,0F,true);
        assertEquals(Tri.FALSE,probe.controller(entities,22,data));
        assertEquals(Tri.TRUE,probe.controllerForTick(entities,22,data,frame(List.of(rot,vehicle),rot),true));
        assertEquals(1,data.inventory.selected(),"packet evidence does not choose an unannounced inventory slot");
        assertEquals(Tri.FALSE,probe.controllerForTick(entities,22,data,frame(List.of(vehicle,rot),rot),true));
        assertEquals(Tri.FALSE,probe.controllerForTick(entities,22,data,frame(List.of(rot),rot),true));
        assertEquals(Tri.FALSE,probe.controllerForTick(entities,22,data,frame(List.of(rot,vehicle),rot),false));
        entities.equipment(Wire.of(EntityEquipment.of(22,WireItem.EMPTY,null)));
        assertEquals(Tri.FALSE,probe.controllerForTick(entities,22,data,frame(List.of(rot,vehicle),rot),true));
        entities.equipment(Wire.of(EntityEquipment.of(22,WireItem.of("minecraft:saddle",1),null)));
        data.inventory.setItem(0,ItemStack.EMPTY);
        assertEquals(Tri.FALSE,probe.controllerForTick(entities,22,data,frame(List.of(rot,vehicle),rot),true));
    }

    private static ClientTickFrame frame(List<InboundEvent> packets, Movement rot) {
        return new ClientTickFrame(0,0,0,new SyncWindow(-1,0),Wire.ofAll(packets),
                (new ru.minecomplex.mac.fixture.inbound.PlayerInput(0)).flags(),false,Wire.of(rot),Set.of());
    }
}
