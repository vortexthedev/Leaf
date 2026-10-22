package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import net.minecraft.world.level.GameType;
import ru.minecomplex.mac.fixture.ItemSampleCodec;
import ru.minecomplex.mac.fixture.ItemComponentSample;
import ru.minecomplex.mac.fixture.FixtureItems;

import net.minecraft.world.item.ItemStack;

import ru.minecomplex.mac.fixture.WireItem;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.inventory.*;
import ru.minecomplex.mac.player.*;
import ru.minecomplex.mac.fixture.inbound.*;
import ru.minecomplex.mac.fixture.outbound.*;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.engine.movementtick.LocalPlayerOracleCodec;
import ru.minecomplex.mac.engine.movementtick.MovementTickerPlayer;
import ru.minecomplex.mac.engine.movementtick.LocalPlayerPhysicsOracleTest;
import ru.minecomplex.mac.engine.movementtick.LocalTickOracle;

import ru.minecomplex.mac.engine.movementtick.MovementOracleTest;
import ru.minecomplex.mac.inventory.item.ItemBehavior;
import ru.minecomplex.mac.inventory.item.ItemMechanics;
import ru.minecomplex.mac.inventory.item.Stacks;
import ru.minecomplex.mac.listener.ClientPlayerActions;
import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;

/** Real client item lifecycle during whole movement. Unresolved inventory outcomes are counted explicitly. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ItemUseCatalogOracleTest {
    @org.junit.jupiter.api.BeforeAll static void initializeNativePackets() { ru.minecomplex.mac.packet.NativeRuntime.ensure(); }
    static final Set<String> ITEMS=Set.of("diamond_sword","diamond_axe","bow","crossbow","trident","mace",
            "wind_charge","ender_pearl","chorus_fruit","cooked_beef","golden_apple","potion","splash_potion",
            "lingering_potion","milk_bucket","bucket","water_bucket","lava_bucket","powder_snow_bucket",
            "shield","elytra","firework_rocket","fishing_rod","lead","shears","flint_and_steel","bone_meal",
            "cow_spawn_egg","leather_boots","diamond_chestplate","diamond_leggings","diamond_helmet",
            "totem_of_undying","bundle","map","filled_map","spyglass","brush","goat_horn","snowball","egg");

    static ItemStack stack(DataInputStream in, ItemBehavior items) throws Exception {
        if(in.readBoolean()) return ItemStack.EMPTY;
        String name=in.readUTF(); int count=in.readInt();
        ItemComponentSample components=ItemSampleCodec.read(in,in.readInt());
        var stack=FixtureItems.resolved(name,count,components);
        assertEquals(components,FixtureItems.components(stack),"Resolved oracle components");
        assertEquals(in.readBoolean(),ru.minecomplex.mac.inventory.item.ItemMechanics.nextDamageWillBreak(stack),"Recorded component-derived durability");
        return stack;
    }

    @Test
    void everyCatalogItemInBothHandsAndAllMovementModes() throws Exception {
        var raw=getClass().getResourceAsStream("/vanilla/777/item-use-catalog.bin.gz"); assertNotNull(raw,"26.3");
        var items=ru.minecomplex.mac.inventory.item.ItemBehavior.get();
        var coverage=new HashMap<String,Set<String>>(); var uncertain=new TreeMap<String,Integer>();
        int ticks=0,completions=0,inventoryComparisons=0;
        try(var in=new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d41414d,in.readInt()); assertEquals(2,in.readUnsignedShort()); assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt());
            org.junit.jupiter.api.Assertions.assertEquals("26.3", in.readUTF(), "Fixture version"); assertEquals(ITEMS.size()*8,in.readInt());
            for(int s=0;s<ITEMS.size()*8;s++) {
                String item=in.readUTF(); boolean off=in.readBoolean(); String type=in.readUTF(); int mode=in.readInt(),floor=in.readInt();
                var data=new ClientPlayerData(); data.clientLoaded=true; data.localMode=GameType.SURVIVAL; data.food=14;
                var original=new ItemStack[41];
                for(int slot=0;slot<41;slot++) {original[slot]=stack(in,items);data.inventory.replace(slot,original[slot]);}
                var initial=LocalTickOracle.State.read(in); var base=initial.base();
                var p=base.player().toState(); var local=new LocalPlayerState();
                local.input=base.input();local.vector=base.vector();local.send=base.sendState();local.send.wasShiftKeyDown=initial.wasShift();
                var model=new PlayerModel(); var hypothesis=model.start(p,local,data,-1); long seq=-1;
                var blocks=new ArrayList<LocalTickOracle.Block>();
                for(int x=-12;x<=12;x++)for(int z=-12;z<=12;z++)blocks.add(new LocalTickOracle.Block(x,63,z,floor));
                var physics=new MovementTickerPlayer(LocalPlayerPhysicsOracleTest.environment(BlockRegistry.get(),blocks),p,local,data);
                var target=new ClientPlayerActions.Target(){public String block(int x,int y,int z){return null;}
                    public String entityType(int id){return id==42?type:null;}};
                assertEquals(192,in.readInt());
                coverage.computeIfAbsent(item.substring(10),ignored->new HashSet<>()).add(off+"/"+mode);
                for(int tick=0;tick<192;tick++) {
                    int op=in.readUnsignedByte(),mask=in.readUnsignedByte();float yaw=in.readFloat();
                    String context="26.3"+" "+item+" off="+off+" mode="+mode+" tick="+tick+" op="+op;
                    InboundEvent action=switch(op){case 1->new UseItem(off,tick,yaw,0);case 2->PlayerAction.of(PlayerAction.Action.RELEASE_USE_ITEM);
                        case 3->new Attack(42);case 4->new HeldSlot(1);case 5->new HeldSlot(0);default->null;};
                    if(action!=null) assertTrue(hypothesis.act(Wire.of(action,Wire.FACTS),target).isEmpty(),context);
                    OutboundEvent event=switch(op){case 6->new EntityEvent(1,9);case 7->new ItemCooldown(item,30);
                        case 9->new ItemCooldown(item,0);case 8->PlayerInventorySlot.of(off?40:0,item.equals("minecraft:goat_horn")
                                ? new WireItem(item,Stacks.count(original[off?40:0]),WireItem.INSTRUMENT,0,FixtureItems.components(original[off?40:0]))
                                : WireItem.of(item,Stacks.count(original[off?40:0])));default->null;};
                    if(event!=null){model.sent(++seq,Wire.of(event,Wire.FACTS));hypothesis.apply(new SyncWindow(seq,seq+1),seq);}
                    if(op==6)completions++;
                    hypothesis.livingTickStart();
                    var effects=data.usedStack().getOrDefault(net.minecraft.core.component.DataComponents.USE_EFFECTS, null);if(effects==null)effects=new net.minecraft.world.item.component.UseEffects(false,true,0.2F);
                    boolean glide=data.inventory.equipmentCanGlide()&&!p.onGround&&!p.passenger&&!p.fallFlying
                            &&!p.effects.has(ru.minecomplex.mac.entity.effect.MobEffect.LEVITATION)&&!p.wasTouchingWater;
                    p.yRot=yaw;p.xRot=0;
                    var actual=physics.tick(PlayerInput.fromMask(mask));hypothesis.playerTickEnd();hypothesis.tickEnded();
                    var after=LocalTickOracle.State.read(in);boolean using=in.readBoolean();int attackTicks=in.readInt();
                    int remaining=in.readInt();boolean usedOff=in.readBoolean();int food=in.readInt();float saturation=in.readFloat();
                    var inventory=new ItemStack[41];for(int slot=0;slot<41;slot++)inventory[slot]=stack(in,items);
                    int clock=in.readInt(),n=in.readInt();var cooldowns=new TreeMap<String,List<Integer>>();
                    for(int i=0;i<n;i++)cooldowns.put(in.readUTF(),List.of(in.readInt(),in.readInt()));
                    var packets=new ArrayList<InboundEvent>();int count=in.readInt();for(int i=0;i<count;i++)packets.add(LocalPlayerOracleCodec.packet(in));
                    assertTrue(actual.isEmpty(),context+actual);
                    assertEquals(List.of(),after.base().player().differences(MovementOracleTest.Recorded.of(p)),context);
                    hidden(after,p,local,context);
                    assertEquals(packets,Wire.in(physics.packets()),context+" packets");assertEquals(clock,data.cooldownClock,context+" cooldown clock");
                    assertEquals(using,data.usingItem,context+" using");if(using){assertEquals(usedOff,data.usingOffHand,context+" hand");assertEquals(remaining,data.useRemaining,context+" remaining");}
                    assertEquals(food,data.food,context+" food");assertEquals(Float.floatToIntBits(saturation),Float.floatToIntBits(data.saturation),context+" saturation");
                    for(var gap:hypothesis.gaps())uncertain.merge(item+"/"+gap,1,Integer::sum);
                    assertTrue(Set.of(Unmodeled.ITEM_USE_OUTCOME,Unmodeled.INVENTORY_CLIENT_ACTION,Unmodeled.ITEM_USE_TIMING)
                            .containsAll(hypothesis.gaps()),context+hypothesis.gaps());
                    if(!hypothesis.gaps().contains(Unmodeled.ITEM_USE_OUTCOME)){
                        for(int slot=0;slot<41;slot++)if(!data.inventory.unknown(slot)) {
                            var expectedStack=inventory[slot]; var actualStack=data.inventory.item(slot);
                            assertEquals(Stacks.count(expectedStack),Stacks.count(actualStack),context+" inventory count "+slot);
                            assertEquals(Stacks.key(expectedStack),Stacks.key(actualStack),context+" inventory item "+slot);
                            // MAAM records this component subset, not sounds/animations or the complete PE patch.
                            assertEquals(FixtureItems.components(expectedStack),FixtureItems.components(actualStack),context+" inventory components "+slot);
                        }
                        var actualCooldowns=new TreeMap<String,List<Integer>>();data.cooldowns.forEach((g,t)->actualCooldowns.put(g,List.of(t[0],t[1])));
                        assertEquals(cooldowns,actualCooldowns,context+" cooldown entries");inventoryComparisons++;
                    }
                    int[] attackBounds=new int[2];int bound=0;
                    for(String field:new String[]{"attackTicksMin","attackTicksMax"}){
                        var f=ClientPlayerData.class.getDeclaredField(field);f.setAccessible(true);attackBounds[bound++]=f.getInt(data);}
                    assertTrue(attackBounds[0]<=attackTicks&&attackTicks<=attackBounds[1],context+" attack clock outside "+Arrays.toString(attackBounds));
                    if(!hypothesis.gaps().contains(Unmodeled.INVENTORY_CLIENT_ACTION)) {
                        assertEquals(attackTicks,attackBounds[0],context+" certain attack min");assertEquals(attackTicks,attackBounds[1],context+" certain attack max");
                    }
                    ticks++;
                }
            }
            assertEquals(-1,in.read());
        }
        assertEquals(ITEMS,coverage.keySet());coverage.forEach((item,cases)->assertEquals(8,cases.size(),item));
        assertEquals(ITEMS.size()*8*192,ticks);assertTrue(completions>0);assertTrue(inventoryComparisons>ticks/2);
        System.out.println("Item catalog 26.3: "+ticks+" real ticks; completions="+completions+" exact inventory ticks="+inventoryComparisons+" unresolved inventory outcomes="+uncertain);
    }

    private static void hidden(LocalTickOracle.State after,PlayerState p,LocalPlayerState local,String context){
        var b=after.base();
        assertEquals(b.pose(),p.pose.name(),context+" pose");assertEquals(b.crouching(),local.crouching,context+" crouching");
        assertEquals(b.flying(),p.abilities.flying,context+" flying");assertEquals(b.swimming(),p.swimming,context+" swimming");
        assertEquals(b.fallFlying(),p.fallFlying,context+" gliding");
        assertEquals(b.jumpTriggerTime(),local.jumpTriggerTime,context+" jump timer");
        assertEquals(b.sprintTriggerTime(),local.sprintTriggerTime,context+" sprint timer");
        assertEquals(b.autoJumpTime(),local.autoJumpTime,context+" autojump timer");
        assertEquals(b.input(),local.input,context+" input");assertEquals(b.vector(),local.vector,context+" vector");
        assertEquals(Float.floatToIntBits(b.xxa()),Float.floatToIntBits(p.xxa),context+" xxa");
        assertEquals(Float.floatToIntBits(b.zza()),Float.floatToIntBits(p.zza),context+" zza");
        assertEquals(after.wasShift(),local.send.wasShiftKeyDown,context+" sent shift");
        assertEquals(Float.floatToIntBits(after.ridingScale()),Float.floatToIntBits(local.jumpRidingScale),context+" riding scale");
        assertEquals(after.ridingTicks(),local.jumpRidingTicks,context+" riding ticks");
        assertEquals(after.waterVision(),local.waterVisionTime,context+" water vision");
        assertEquals(after.wasFallFlying(),local.wasFallFlying,context+" was gliding");
        var expected=b.sendState();var actual=local.send;
        assertEquals(expected.lastSentInput,actual.lastSentInput,context+" sent input");
        assertEquals(expected.wasSprinting,actual.wasSprinting,context+" sent sprint");
        assertEquals(expected.positionReminder,actual.positionReminder,context+" position reminder");
        assertEquals(expected.lastOnGround,actual.lastOnGround,context+" sent ground");
        assertEquals(expected.lastHorizontalCollision,actual.lastHorizontalCollision,context+" sent collision");
        assertEquals(expected.autoJumpEnabled,actual.autoJumpEnabled,context+" autojump option");
        assertEquals(Double.doubleToLongBits(expected.xLast),Double.doubleToLongBits(actual.xLast),context+" sent x");
        assertEquals(Double.doubleToLongBits(expected.yLast),Double.doubleToLongBits(actual.yLast),context+" sent y");
        assertEquals(Double.doubleToLongBits(expected.zLast),Double.doubleToLongBits(actual.zLast),context+" sent z");
        assertEquals(Float.floatToIntBits(expected.yRotLast),Float.floatToIntBits(actual.yRotLast),context+" sent yaw");
        assertEquals(Float.floatToIntBits(expected.xRotLast),Float.floatToIntBits(actual.xRotLast),context+" sent pitch");
    }
}
