package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import net.minecraft.world.level.GameType;
import ru.minecomplex.mac.fixture.FixtureItems;
import net.minecraft.world.item.ItemStack;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.inventory.*;
import ru.minecomplex.mac.player.*;
import ru.minecomplex.mac.fixture.inbound.*;

import ru.minecomplex.mac.inventory.item.ItemBehavior;
import ru.minecomplex.mac.inventory.item.Stacks;
import ru.minecomplex.mac.listener.ClientPlayerActions;
import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

/** Actual positive/negative block/entity uses check the model's exact or explicitly bounded inventory/use contract. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ItemContextOracleTest {
    @org.junit.jupiter.api.BeforeAll static void initializeNativePackets() { ru.minecomplex.mac.packet.NativeRuntime.ensure(); }

    @Test
    void actualInteractionsRemainWithinReportedClientUncertainty()throws Exception{
        var raw=getClass().getResourceAsStream("/vanilla/777/item-contexts.bin.gz");assertNotNull(raw,"26.3");
        var items=ru.minecomplex.mac.inventory.item.ItemBehavior.get();
        var names=new HashMap<String,Set<String>>();var successful=new HashSet<String>();var unknown=new TreeMap<String,Integer>();
        try(var in=new DataInputStream(new GZIPInputStream(raw))){
            assertEquals(0x4d414958,in.readInt());assertEquals(2,in.readUnsignedShort());assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt());org.junit.jupiter.api.Assertions.assertEquals("26.3", in.readUTF(), "Fixture version");
            int scenarios=in.readInt();assertEquals(140,scenarios);
            for(int s=0;s<scenarios;s++){
                String name=in.readUTF(),item=in.readUTF();boolean off=in.readBoolean();int route=in.readUnsignedByte(),entityPath=in.readUnsignedByte();String target=in.readUTF();
                var p=new PlayerState();p.position=new Vec3d(8.5,65,8.5);p.xRot=90;
                var data=new ClientPlayerData();data.localMode=GameType.SURVIVAL;data.food=14;
                for(int slot=0;slot<41;slot++)data.inventory.replace(slot,ItemUseCatalogOracleTest.stack(in,items));
                names.computeIfAbsent(name,k->new HashSet<>()).add(off+"/"+Stacks.count(data.inventory.item(off?40:0)));
                var actions=new ClientPlayerActions();
                InboundEvent event=route==0?new UseItemOn(off,1,8,63,8):route==1?new UseItem(off,1,0,90):new InteractEntity(42,off,false);
                var query=new ClientPlayerActions.Target(){public String block(int x,int y,int z){return target;}
                    public String entityType(int id){return id==42?target:null;}};
                var gaps=java.util.EnumSet.noneOf(Unmodeled.class);
                if((entityPath&1)!=0){var atResult=actions.apply(p,data,Wire.of(new InteractEntity(42,off,true)),query);assertTrue(atResult.violations().isEmpty());gaps.addAll(atResult.gaps());}
                var result=route==2&&entityPath==1?new ClientPlayerActions.Result(Set.of(),gaps,Set.of()):actions.apply(p,data,Wire.of(event,Wire.FACTS),query);
                gaps.addAll(result.gaps());result=new ClientPlayerActions.Result(result.violations(),gaps,result.resolves());
                assertTrue(result.violations().isEmpty(),"26.3"+" "+name+result.violations());
                boolean consumes=in.readBoolean(),using=in.readBoolean();int remaining=in.readInt();
                var after=new ItemStack[41];for(int slot=0;slot<41;slot++)after[slot]=ItemUseCatalogOracleTest.stack(in,items);
                boolean blockChanged=in.readBoolean();int blocks=in.readInt();for(int b=0;b<blocks;b++){in.readLong();in.readInt();}
                if(consumes)successful.add(name);
                String context="26.3"+" "+name+" "+off;
                if(result.gaps().contains(Unmodeled.ITEM_USE_OUTCOME))unknown.merge(name+"/ITEM_USE_OUTCOME",1,Integer::sum);
                else for(int slot=0;slot<41;slot++){
                    if(data.inventory.unknown(slot)){assertTrue(result.gaps().contains(Unmodeled.INVENTORY_CLIENT_ACTION),context+" unknown without gap");unknown.merge(name+"/INVENTORY_CLIENT_ACTION",1,Integer::sum);continue;}
                    var actual=after[slot];var model=data.inventory.item(slot);
                    assertTrue(Stacks.count(actual)>=data.inventory.minCount(slot)&&Stacks.count(actual)<=Stacks.count(model),context+" count bounds slot="+slot+" "+actual+" "+model);
                    if(!actual.isEmpty()){
                        assertEquals(Stacks.key(actual),Stacks.key(model),context+" item slot="+slot);
                        assertEquals(FixtureItems.components(actual),FixtureItems.components(model),context+" components slot="+slot);
                    }
                }
                if(result.gaps().contains(Unmodeled.ITEM_USE_TIMING))unknown.merge(name+"/ITEM_USE_TIMING",1,Integer::sum);
                else{assertEquals(using,data.usingItem,context+" using");if(using)assertEquals(remaining,data.useRemaining,context+" use remaining");}
                // Recorded block mutations are evidence of real entry-point execution, not a claim of ported placement/growth.
                if(blockChanged)successful.add(name+"/local-block-write");
            }
            assertEquals(-1,in.read());
        }
        assertEquals(Set.of("flint_fire","flint_tnt","bone_meal_crop","bone_meal_sapling","brush_sand","brush_gravel",
                "shears_pumpkin","shears_sheep","shears_mooshroom","lead_cow","lead_fence","bucket_water","bucket_lava",
                "bucket_powder_snow","water_place","lava_place","waterlog_slab","snow_place","spawn_egg_ground",
                "spawn_egg_spawner","armor_stand_equip","allay_give","bucket_cow","bowl_mooshroom",
                "shears_pumpkin_off_target","bottle_water","potion_mud","shield_block_click","sword_block_click",
                "axe_strip","axe_scrape","axe_unwax","fishing_reel","lead_unbind","flint_obsidian"),names.keySet());
        names.forEach((name,cases)->{
            assertTrue(cases.stream().anyMatch(c->c.startsWith("true/")),name+" offhand");
            assertTrue(cases.stream().anyMatch(c->c.startsWith("false/")),name+" main hand");
        });
        assertTrue(successful.containsAll(Set.of("brush_sand","brush_gravel","flint_fire","flint_tnt","spawn_egg_ground","fishing_reel")),"Positive interactions "+successful);
        System.out.println("Item contexts 26.3 positive="+successful+" explicit uncertainty="+unknown);
    }
}
