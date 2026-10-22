package ru.minecomplex.mac.fixture;

import java.util.*;
import java.util.function.Consumer;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.network.*;
import net.minecraft.network.codec.*;

import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.*;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.PositionStep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.*;
import ru.minecomplex.mac.fixture.inbound.*;
import ru.minecomplex.mac.fixture.outbound.*;
import ru.minecomplex.mac.inventory.item.Stacks;
import ru.minecomplex.mac.listener.MultiBlockChangeBitRepackHandler;
import ru.minecomplex.mac.packet.*;
import ru.minecomplex.mac.player.connection.PlayerConnectionContext;
import ru.minecomplex.mac.util.Tri;

/** Synthetic packet records become genuine native 26.3 packets before entering the model or recorder. */
final class NativeFixtureFactory {
    private NativeFixtureFactory() { }
    static <T> T read(StreamCodec<? super RegistryFriendlyByteBuf, T> codec, Consumer<RegistryFriendlyByteBuf> write) {
        var bytes = new RegistryFriendlyByteBuf(Unpooled.buffer(), NativeRuntime.registries());
        try { write.accept(bytes); return codec.decode(bytes); }
        finally { bytes.release(); }
    }
    static CompoundTag nbt(byte[] bytes) {
        try {
            var value = NbtIo.readAnyTag(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes)), NbtAccounter.unlimitedHeap());
            return value instanceof CompoundTag compound ? compound : null;
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    static InteractionHand hand(boolean off) { return off ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND; }
    static ItemStack stack(WireItem item) { return FixtureItems.stack(item); }

    static Object inbound(InboundEvent event, PlayerConnectionContext facts) {
        NativeRuntime.ensure();
        return switch (event) {
            case TickEnd p -> new ServerboundClientTickEndPacket();
            case Pong p -> new ServerboundPongPacket(p.id());
            case Movement p -> Packets.movement(ru.minecomplex.mac.packet.MoveKind.valueOf(p.kind().name()), p.x(),p.y(),p.z(),p.yRot(),p.xRot(),p.onGround(),p.horizontalCollision());
            case VehicleMovement p -> Packets.vehicleMove(new Vec3(p.x(),p.y(),p.z()),p.yaw(),p.pitch(),p.onGround());
            case PaddleBoat p -> new ServerboundPaddleBoatPacket(p.left(),p.right());
            case TeleportAccept p -> new ServerboundAcceptTeleportationPacket(p.id(),p.x(),p.y(),p.z(),p.yRot(),p.xRot());
            case PlayerInput p -> Packets.input(p.flags());
            case PlayerAbilities p -> Packets.abilities(p.flying());
            case PlayerCommand p -> Packets.command(ServerboundPlayerCommandPacket.Action.valueOf(p.action().name()),p.data());
            case HeldSlot p -> new ServerboundSetCarriedItemPacket(p.slot());
            case UseItem p -> new ServerboundUseItemPacket(hand(p.offHand()),p.sequence(),p.yRot(),p.xRot());
            case UseItemOn p -> new ServerboundUseItemOnPacket(hand(p.offHand()),new BlockHitResult(Vec3.ZERO,Direction.UP,new BlockPos(p.x(),p.y(),p.z()),false),p.sequence());
            case InteractEntity p -> new ServerboundInteractPacket(p.entityId(),hand(p.offHand()),p.at()?Vec3.ZERO:null,false);
            case Attack p -> new ServerboundAttackPacket(p.entityId());
            case PlayerAction p -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.valueOf(p.action().name()),new BlockPos(p.x(),p.y(),p.z()),Direction.values()[p.face()],p.sequence());
            case Swing p -> ServerboundPunchPacket.INSTANCE;
            case ContainerClosed p -> new ServerboundContainerClosePacket(p.containerId());
            case ContainerClick p -> click(p);
            case CreativeSlot p -> new ServerboundSetCreativeModeSlotPacket((short)p.slot(),stack(p.stack()));
            case PlayerLoaded p -> new ServerboundPlayerLoadedPacket();
            case KeepAlive p -> new ServerboundKeepAlivePacket(p.id());
            case ChunkBatchReceived p -> new ServerboundChunkBatchReceivedPacket(p.desiredChunksPerTick());
            case OtherInbound p -> new UnhandledPacket(false,p.name());
        };
    }

    static HashedStack hash(ClaimedStack claim) {
        WireItem item = claim.stack();
        if (item.isEmpty()) return HashedStack.EMPTY;
        var added = new LinkedHashMap<DataComponentType<?>,Integer>();
        var removed = new LinkedHashSet<DataComponentType<?>>();
        for (int bit=1;bit<=ItemComponentSample.TOOL;bit<<=1) {
            if ((item.set()&bit)!=0) added.put(ItemSamples.componentType(bit),0);
            if ((item.removed()&bit)!=0) removed.add(ItemSamples.componentType(bit));
        }
        return new HashedStack.ActualItem(Stacks.of(item.item(),item.count()).typeHolder(),item.count(),new HashedPatchMap(added,removed));
    }
    static Object click(ContainerClick p) {
        var slots = new it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap<HashedStack>();
        p.changed().forEach((slot,item)->slots.put(slot.intValue(),hash(item)));
        return new ServerboundContainerClickPacket(p.containerId(),p.stateId(),(short)p.slot(),(byte)p.button(),net.minecraft.world.inventory.ContainerInput.values()[p.clickType()],slots,hash(p.carried()));
    }

    static Object outbound(OutboundEvent event, PlayerConnectionContext facts) {
        NativeRuntime.ensure();
        return switch (event) {
            case WorldBorder p -> border(p);
            case Ping p -> new ClientboundPingPacket(p.id());
            case BundleDelimiter p -> new NativePacketCodec(NativeRuntime.registries()).bundleDelimiter();
            case EntityPassengers p -> new ClientboundSetPassengersPacket(p.vehicleId(),p.passengerIds());
            case EntityRemove p -> new ClientboundRemoveEntitiesPacket(p.entityIds());
            case VehicleCorrection p -> new ClientboundMoveVehiclePacket(PositionAndRotation.of(new Vec3(p.x(),p.y(),p.z()),p.yaw(),p.pitch()));
            case EntitySpawn p -> new ClientboundAddEntityPacket(p.entityId(),p.uuid(),p.x(),p.y(),p.z(),p.pitch(),p.yaw(),BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(p.type())),p.data(),new Vec3(p.velocityX(),p.velocityY(),p.velocityZ()),p.headYaw());
            case JoinGame p -> new ClientboundLoginPacket(p.playerEntityId(),false,Set.of(level(p.dimension())),0,p.chunkRadius(),p.chunkRadius(),false,true,false,spawn(p.dimension(),p.minY(),p.height(),p.gameMode(),p.canHaveWeather()),false,false);
            case Respawn p -> new ClientboundRespawnPacket(spawn(p.dimension(),p.minY(),p.height(),p.gameMode(),p.canHaveWeather()),(byte)p.dataToKeep());
            case ChunkData p -> chunk(p);
            case LightUpdate p -> new ClientboundLightUpdatePacket(p.x(),p.z(),light());
            case ChunkBiomes p -> new ClientboundChunksBiomesPacket(Arrays.stream(p.chunks()).mapToObj(c->new ClientboundChunksBiomesPacket.ChunkBiomeData(ChunkPos.unpack(c),new byte[0])).toList());
            case BlockTagsUpdate p -> tags(p);
            case EntityAnimation p -> read(ClientboundAnimatePacket.STREAM_CODEC,b->{b.writeVarInt(p.entityId());b.writeByte(p.action());});
            case ForgetChunk p -> new ClientboundForgetLevelChunkPacket(new ChunkPos(p.x(),p.z()));
            case BlockUpdate p -> new ClientboundBlockUpdatePacket(new BlockPos(p.x(),p.y(),p.z()),Block.stateById(p.state()));
            case BlockEvent p -> new ClientboundBlockEventPacket(new BlockPos(p.x(),p.y(),p.z()),BuiltInRegistries.BLOCK.byId(p.blockType()),p.action(),p.parameter());
            case BlockEntityData p -> new ClientboundBlockEntityDataPacket(new BlockPos(p.x(),p.y(),p.z()),BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(Identifier.parse(p.type())),nbt(p.nbt()));
            case SectionBlocksUpdate p -> section(new MultiBlockChangeBitRepackHandler.Update(p.sectionX(),p.sectionY(),p.sectionZ(),p.entries()));
            case BlockChangedAck p -> new ClientboundBlockChangedAckPacket(p.sequence());
            case ChunkBatch p -> p.finished()?new ClientboundChunkBatchFinishedPacket(p.batchSize()):ClientboundChunkBatchStartPacket.INSTANCE;
            case ChunkCacheCenter p -> new ClientboundSetChunkCacheCenterPacket(p.x(),p.z());
            case ChunkCacheRadius p -> new ClientboundSetChunkCacheRadiusPacket(p.radius());
            case PlayerPosition p -> new ClientboundPlayerPositionPacket(p.teleportId(),new PositionMoveRotation(new Vec3(p.x(),p.y(),p.z()),new Vec3(p.deltaX(),p.deltaY(),p.deltaZ()),p.yRot(),p.xRot()),Relative.unpack(p.relatives()));
            case PlayerRotation p -> new ClientboundPlayerRotationPacket(p.yRot(),p.relativeY(),p.xRot(),p.relativeX());
            case EntityVelocity p -> new ClientboundSetEntityMotionPacket(p.entityId(),new Vec3(p.x(),p.y(),p.z()));
            case Explosion p -> new ClientboundExplodePacket(new Vec3(p.centerX(),p.centerY(),p.centerZ()),0.0F,0,p.hasKnockback()?Optional.of(new Vec3(p.knockbackX(),p.knockbackY(),p.knockbackZ())):Optional.empty(),net.minecraft.core.particles.ParticleTypes.EXPLOSION,SoundEvents.GENERIC_EXPLODE,net.minecraft.util.random.WeightedList.of(),false);
            case Abilities p -> abilities(p);
            case GameEvent p -> read(ClientboundGameEventPacket.STREAM_CODEC,b->{b.writeByte(p.type());b.writeFloat(p.param());});
            case EffectUpdate p -> {
                var packet=new ClientboundUpdateMobEffectPacket(p.entityId(),new net.minecraft.world.effect.MobEffectInstance(effect(p.effect()),p.duration(),p.amplifier(),p.ambient(),p.visible(),p.showIcon()),p.blend());
                packet.setEffectAmplifier(p.amplifier());yield packet;
            }
            case EffectRemove p -> new ClientboundRemoveMobEffectPacket(p.entityId(),effect(p.effect()));
            case EntityAttributes p -> attributes(p);
            case PlayerHealth p -> new ClientboundSetHealthPacket(p.health(),p.food(),p.saturation());
            case PlayerInfoGameMode p -> profiles(p,facts);
            case ContainerContent p -> new ClientboundContainerSetContentPacket(p.containerId(),p.stateId(),p.stacks().stream().map(NativeFixtureFactory::stack).toList(),stack(p.carriedStack()));
            case ContainerSlot p -> new ClientboundContainerSetSlotPacket(p.containerId(),p.stateId(),p.slot(),stack(p.stack()));
            case PlayerInventorySlot p -> new ClientboundSetPlayerInventoryPacket(p.slot(),stack(p.stack()));
            case SetHeldSlot p -> new ClientboundSetHeldSlotPacket(p.slot());
            case EntityEvent p -> read(ClientboundEntityEventPacket.STREAM_CODEC,b->{b.writeInt(p.entityId());b.writeByte(p.event());});
            case ItemCooldown p -> new ClientboundCooldownPacket(Identifier.parse(p.group()),p.ticks());
            case CursorItem p -> new ClientboundSetCursorItemPacket(stack(p.stack()));
            case OpenContainer p -> new ClientboundOpenScreenPacket(p.containerId(),BuiltInRegistries.MENU.byId(p.menuType()),net.minecraft.network.chat.Component.empty());
            case MountScreenOpen p -> new ClientboundMountScreenOpenPacket(p.containerId(),p.inventoryColumns(),p.entityId());
            case CloseContainer p -> new ClientboundContainerClosePacket(p.containerId());
            case EntityMove p -> move(p);
            case EntityTeleport p -> new ClientboundTeleportEntityPacket(p.entityId(),new PositionMoveRotation(new Vec3(p.x(),p.y(),p.z()),new Vec3(p.deltaX(),p.deltaY(),p.deltaZ()),p.yRot(),p.xRot()),Relative.unpack(p.relatives()),p.onGround());
            case EntityPositionSync p -> sync(p);
            case EntitySyncedData p -> metadata(p);
            case EntityEquipment p -> equipment(p);
            case GameTime p -> new ClientboundSetTimePacket(p.gameTime(),Map.of());
            case PlayerInfoRemove p -> new ClientboundPlayerInfoRemovePacket(p.ids());
            case TeamUpdate p -> team(p);
            case OtherOutbound p -> new UnhandledPacket(true,p.name());
        };
    }
    static Holder<net.minecraft.world.effect.MobEffect> effect(String key) { return BuiltInRegistries.MOB_EFFECT.getOrThrow(ResourceKey.create(Registries.MOB_EFFECT,Identifier.parse(key))); }
    static ResourceKey<Level> level(String key) { return ResourceKey.create(Registries.DIMENSION,Identifier.parse(key)); }
    static CommonPlayerSpawnInfo spawn(String key,int minY,int height,int mode,Tri weather) {
        var base=NativeRuntime.registries().lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD).value();
        var dimension=new DimensionType(base.hasFixedTime(),weather!=Tri.FALSE,weather==Tri.FALSE,base.hasEnderDragonFight(),base.coordinateScale(),minY,height,height,base.infiniburn(),base.ambientLight(),base.monsterSettings(),base.skybox(),base.cardinalLightType(),base.attributes(),base.timelines(),base.defaultClock());
        return new CommonPlayerSpawnInfo(Holder.direct(dimension),level(key),0L,GameType.byId(mode),Optional.empty(),false,false,Optional.empty(),0,63);
    }
    static Object border(WorldBorder p) {
        return switch(p.operation()) {
            case CENTER -> { var packet=new ClientboundSetBorderCenterPacket(new net.minecraft.world.level.border.WorldBorder()); packet.setNewCenterX(p.centerX());packet.setNewCenterZ(p.centerZ());yield packet; }
            case SIZE -> { var packet=new ClientboundSetBorderSizePacket(new net.minecraft.world.level.border.WorldBorder());packet.setSize(p.newSize());yield packet; }
            case LERP -> { var packet=new ClientboundSetBorderLerpSizePacket(new net.minecraft.world.level.border.WorldBorder());packet.setOldSize(p.oldSize());packet.setNewSize(p.newSize());packet.setLerpTime(p.duration());yield packet; }
            case INITIALIZE -> {
                var packet=new ClientboundInitializeBorderPacket(new net.minecraft.world.level.border.WorldBorder());
                packet.setNewCenterX(p.centerX());packet.setNewCenterZ(p.centerZ());packet.setOldSize(p.oldSize());packet.setNewSize(p.newSize());packet.setLerpTime(p.duration());packet.setNewAbsoluteMaxSize(p.absoluteMax());yield packet;
            }
        };
    }
    static Object abilities(Abilities p) {
        var a=new net.minecraft.world.entity.player.Abilities();a.invulnerable=p.invulnerable();a.flying=p.flying();a.mayfly=p.mayFly();a.instabuild=p.instabuild();a.setFlyingSpeed(p.flyingSpeed());a.setWalkingSpeed(p.walkingSpeed());return new ClientboundPlayerAbilitiesPacket(a);
    }
    static Object attributes(EntityAttributes p) {
        var values=new ArrayList<ClientboundUpdateAttributesPacket.AttributeSnapshot>();
        for(var attribute:p.snapshots()) {
            var modifiers=new ArrayList<net.minecraft.world.entity.ai.attributes.AttributeModifier>();
            for(var modifier:attribute.modifiers()) modifiers.add(new net.minecraft.world.entity.ai.attributes.AttributeModifier(Identifier.parse(modifier.id()),modifier.amount(),net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.values()[modifier.operation()]));
            values.add(new ClientboundUpdateAttributesPacket.AttributeSnapshot(BuiltInRegistries.ATTRIBUTE.getOrThrow(ResourceKey.create(Registries.ATTRIBUTE,Identifier.parse(attribute.attribute()))),attribute.base(),modifiers));
        }
        return new ClientboundUpdateAttributesPacket(p.entityId(),values);
    }
    static Object profiles(PlayerInfoGameMode p,PlayerConnectionContext facts) {
        var actions=EnumSet.noneOf(ClientboundPlayerInfoUpdatePacket.Action.class);
        if(p.added()||!p.profiles().isEmpty())actions.add(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER);
        if(p.updatesGameMode()||!p.gameModes().isEmpty())actions.add(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE);
        var names=new LinkedHashMap<UUID,String>();var modes=new HashMap<UUID,Integer>();
        p.profiles().forEach(v->names.put(v.id(),v.name()));p.gameModes().forEach(v->{names.putIfAbsent(v.id(),"");modes.put(v.id(),v.gameMode());});
        if(p.concernsOwnEntry()){names.putIfAbsent(facts.localUuid(),facts.localName()==null?"":facts.localName());modes.put(facts.localUuid(),p.gameMode());}
        var entries=new ArrayList<ClientboundPlayerInfoUpdatePacket.Entry>();
        names.forEach((id,name)->entries.add(new ClientboundPlayerInfoUpdatePacket.Entry(id,new com.mojang.authlib.GameProfile(id,name),true,0,GameType.byId(modes.getOrDefault(id,0)),null,false,0,null)));
        return new ClientboundPlayerInfoUpdatePacket(actions,entries);
    }
    static Object team(TeamUpdate p) {
        Optional<ClientboundSetPlayerTeamPacket.Parameters> parameters=p.collisionRule()<0?Optional.empty():Optional.of(new ClientboundSetPlayerTeamPacket.Parameters(net.minecraft.network.chat.Component.empty(),net.minecraft.network.chat.Component.empty(),net.minecraft.network.chat.Component.empty(),net.minecraft.world.scores.Team.Visibility.ALWAYS,net.minecraft.world.scores.Team.CollisionRule.values()[p.collisionRule()],Optional.empty(),(byte)0));
        return new ClientboundSetPlayerTeamPacket(p.name(),p.method(),parameters,p.players());
    }
    static Object equipment(EntityEquipment p) {
        var items=new ArrayList<Pair<net.minecraft.world.entity.EquipmentSlot,ItemStack>>();
        if(p.saddleSent())items.add(Pair.of(net.minecraft.world.entity.EquipmentSlot.SADDLE,stack(p.saddleStack())));
        if(p.bodySent())items.add(Pair.of(net.minecraft.world.entity.EquipmentSlot.BODY,stack(p.bodyStack())));
        return new ClientboundSetEquipmentPacket(p.entityId(),items);
    }
    static Object move(EntityMove p) {
        if(!p.hasPosition())return new ClientboundMoveEntityPacket.Rot(p.entityId(),p.yRot(),p.xRot(),p.onGround());
        var deltas=p.deltas();VecDelta delta;
        if(p.stepCount()==0)delta=new VecDelta.Linear(deltas[0][0],deltas[0][1],deltas[0][2]);
        else {var steps=new ArrayList<VecDelta.Stepped.DeltaStep>();for(int i=0;i<deltas.length;i++)steps.add(new VecDelta.Stepped.DeltaStep(deltas[i][0],deltas[i][1],deltas[i][2],p.ticks()[i]));delta=new VecDelta.Stepped(steps);}
        return p.hasRotation()?new ClientboundMoveEntityPacket.PosRot(p.entityId(),delta,p.yRot(),p.xRot(),p.onGround()):new ClientboundMoveEntityPacket.Pos(p.entityId(),delta,p.onGround());
    }
    static Object sync(EntityPositionSync p) {
        PositionPath path;
        if(p.stepPositions()==null)path=PositionPath.of(new Vec3(p.x(),p.y(),p.z()));
        else {var steps=new ArrayList<PositionStep>();for(int i=0;i<p.stepPositions().length;i++){var pos=p.stepPositions()[i];steps.add(new PositionStep(new Vec3(pos[0],pos[1],pos[2]),p.stepTicks()[i]));}path=PositionPath.stepped(steps);}
        return new ClientboundEntityPositionSyncPacket(p.entityId(),path,p.yRot(),p.xRot(),p.onGround());
    }
    static Object metadata(EntitySyncedData p) {
        var items=new ArrayList<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>>();
        var serializers=net.minecraft.network.syncher.EntityDataSerializers.class;
        if(p.has(EntitySyncedData.SHARED_FLAGS))items.add(new net.minecraft.network.syncher.SynchedEntityData.DataValue<>(0,net.minecraft.network.syncher.EntityDataSerializers.BYTE,p.sharedFlags()));
        if(p.has(EntitySyncedData.NO_GRAVITY))items.add(new net.minecraft.network.syncher.SynchedEntityData.DataValue<>(5,net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN,p.noGravity()));
        if(p.has(EntitySyncedData.POSE))items.add(new net.minecraft.network.syncher.SynchedEntityData.DataValue<>(6,net.minecraft.network.syncher.EntityDataSerializers.POSE,net.minecraft.world.entity.Pose.valueOf(p.pose())));
        if(p.has(EntitySyncedData.LIVING_FLAGS))items.add(new net.minecraft.network.syncher.SynchedEntityData.DataValue<>(8,net.minecraft.network.syncher.EntityDataSerializers.BYTE,p.livingFlags()));
        if(p.has(EntitySyncedData.HEALTH))items.add(new net.minecraft.network.syncher.SynchedEntityData.DataValue<>(9,net.minecraft.network.syncher.EntityDataSerializers.FLOAT,p.health()));
        if(p.has(EntitySyncedData.SLEEPING_POS))items.add(new net.minecraft.network.syncher.SynchedEntityData.DataValue<>(14,net.minecraft.network.syncher.EntityDataSerializers.OPTIONAL_BLOCK_POS,p.sleeping()?Optional.of(new BlockPos(p.sleepX(),p.sleepY(),p.sleepZ())):Optional.empty()));
        p.mobItemValues().forEach((id,value)->{
            if(value instanceof Byte v)items.add(new net.minecraft.network.syncher.SynchedEntityData.DataValue<>(id,net.minecraft.network.syncher.EntityDataSerializers.BYTE,v));
            else if(value instanceof Boolean v)items.add(new net.minecraft.network.syncher.SynchedEntityData.DataValue<>(id,net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN,v));
            else if(value instanceof Integer v)items.add(new net.minecraft.network.syncher.SynchedEntityData.DataValue<>(id,net.minecraft.network.syncher.EntityDataSerializers.INT,v));
            else if(value instanceof Long v)items.add(new net.minecraft.network.syncher.SynchedEntityData.DataValue<>(id,net.minecraft.network.syncher.EntityDataSerializers.LONG,v));
            else if(value instanceof Float v)items.add(new net.minecraft.network.syncher.SynchedEntityData.DataValue<>(id,net.minecraft.network.syncher.EntityDataSerializers.FLOAT,v));
            else if(value instanceof OptionalInt v)items.add(new net.minecraft.network.syncher.SynchedEntityData.DataValue<>(id,net.minecraft.network.syncher.EntityDataSerializers.OPTIONAL_UNSIGNED_INT,v));
            else throw new IllegalArgumentException("Unsupported metadata "+value);
        });
        return new ClientboundSetEntityDataPacket(p.entityId(),items);
    }
    static ClientboundLightUpdatePacketData light() { return new ClientboundLightUpdatePacketData(new BitSet(),new BitSet(),new BitSet(),new BitSet(),List.of(),List.of()); }
    static ClientboundSectionBlocksUpdatePacket section(MultiBlockChangeBitRepackHandler.Update p) {
        return read(ClientboundSectionBlocksUpdatePacket.STREAM_CODEC,b->{
            b.writeLong(SectionPos.asLong(p.sectionX(),p.sectionY(),p.sectionZ()));b.writeVarInt(p.count());for(int i=0;i<p.count();i++)b.writeVarLong(p.entry(i));
        });
    }
    static ClientboundUpdateTagsPacket tags(BlockTagsUpdate p) {
        var tags=new HashMap<Identifier,it.unimi.dsi.fastutil.ints.IntList>();
        p.tags().forEach((key,ids)->tags.put(Identifier.parse(key),new it.unimi.dsi.fastutil.ints.IntArrayList(ids)));
        return new ClientboundUpdateTagsPacket(Map.of(Registries.BLOCK,new net.minecraft.tags.TagNetworkSerialization.NetworkPayload(tags)));
    }
    static ClientboundLevelChunkWithLightPacket chunk(ChunkData p) {
        var bytes=new FriendlyByteBuf(Unpooled.buffer());
        try {
            int paletteAt=0,dataAt=0;
            var factory=PalettedContainerFactory.create(NativeRuntime.registries());
            for(int section=0;section<p.sectionCount();section++) {
                int bits=p.sectionBits()[section],size=p.paletteSizes()[section];
                var nativeSection=new LevelChunkSection(factory);
                for(int index=0;index<4096;index++) {
                    int state;
                    if(bits==0)state=p.palette()[paletteAt];
                    else {int perLong=64/bits;int encoded=(int)(p.data()[dataAt+index/perLong]>>>((index%perLong)*bits)&((1L<<bits)-1));state=size<0?encoded:p.palette()[paletteAt+encoded];}
                    if(state!=0)nativeSection.setBlockState(index&15,index>>8,(index>>4)&15,Block.stateById(state),false);
                }
                nativeSection.write(bytes);
                if(size>0)paletteAt+=size;
                if(bits>0)dataAt+=(4096+64/bits-1)/(64/bits);
            }
            byte[] sections=new byte[bytes.readableBytes()];bytes.readBytes(sections);
            var entities=ChunkBlockEntities.decode(p.blockEntities());
            var data=read(ClientboundLevelChunkPacketData.STREAM_CODEC,b->{
                b.writeVarInt(0);b.writeByteArray(sections);b.writeVarInt(entities.size());
                for(var entity:entities){b.writeByte((entity.x()&15)<<4|(entity.z()&15));b.writeShort(entity.y());b.writeVarInt(BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(Identifier.parse(entity.type()))));ByteBufCodecs.OPTIONAL_COMPOUND_TAG.encode(b,Optional.ofNullable(nbt(entity.nbt())));}
            });
            return new ClientboundLevelChunkWithLightPacket(p.x(),p.z(),data,light());
        } finally {bytes.release();}
    }
}
