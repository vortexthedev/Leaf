package ru.minecomplex.mac.world.piston;

import ru.minecomplex.mac.world.BlockDirection;
import ru.minecomplex.mac.world.BlockEvent;
import ru.minecomplex.mac.world.BlockPosition;
import ru.minecomplex.mac.world.BlockRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * Client PistonBaseBlock events and PistonMovingBlockEntity lifetime. World callbacks share the same hypothesis.
 */
public final class PistonWorld {

    private final World world;
    private final BlockRegistry registry;
    private final PistonProperties properties;

    public PistonWorld(World world, BlockRegistry registry) {
        this.world = world;
        this.registry = registry;
        properties = PistonProperties.get();
    }

    private boolean is(int state, String name) {
        return registry.block(state).name().equals("minecraft:" + name);
    }

    private String name(BlockDirection direction) {
        return direction.name().toLowerCase(Locale.ROOT);
    }

    private int facing(int state, BlockDirection direction) {
        return properties.with(state, "facing", name(direction));
    }

    private int moving(BlockDirection direction, boolean sticky) {
        int state = facing(properties.defaultState("minecraft:moving_piston"), direction);
        return properties.with(state, "type", sticky ? "sticky" : "normal");
    }

    public boolean event(BlockEvent event) {
        var pos = new BlockPosition(event.x(), event.y(), event.z());
        int state = world.block(pos);
        if (!registry.block(state).isInstance("PistonBaseBlock"))
            return false;
        BlockDirection direction = BlockDirection.valueOf(properties.value(state, "facing").toUpperCase(Locale.ROOT));
        boolean sticky = is(state, "sticky_piston");
        int action = event.action();
        if (action == 0) {
            if (!moveBlocks(pos, direction, true, sticky))
                return false;
            world.setBlock(pos, properties.with(state, "extended", "true"), 67);
        } else if (action == 1 || action == 2) {
            BlockPosition arm = pos.relative(direction);
            if (world.piston(arm) != null)
                finalTick(arm);
            int moving = moving(direction, sticky);
            world.setBlock(pos, moving, 276);
            int moved = facing(properties.defaultState(state), BlockDirection.legacy(event.parameter() & 7));
            world.piston(pos, new MovingPiston(moved, direction, false, true));
            world.neighbours(pos, moving, null);
            world.neighbourShapes(moving, pos, 2, false);
            if (sticky) {
                BlockPosition two = pos.relative(direction, 2);
                int target = world.block(two);
                MovingPiston entity = world.piston(two);
                boolean piece = is(target, "moving_piston") && entity != null && entity.direction() == direction && entity.extending();
                if (piece)
                    finalTick(two);
                else {
                    var resolver = new PistonResolver(world, registry, pos, direction, false);
                    if (action != 1 || registry.isAir(target) || !resolver.isPushable(target, two, direction.opposite(), false, direction) || properties.reaction(target) != PistonProperties.Reaction.NORMAL && !is(target, "piston") && !is(target, "sticky_piston"))
                        world.setBlock(arm, 0, 3);
                    else
                        moveBlocks(pos, direction, false, true);
                }
            } else
                world.setBlock(arm, 0, 3);
        }
        return true;
    }

    private boolean moveBlocks(BlockPosition pos, BlockDirection direction, boolean extending, boolean sticky) {
        BlockPosition arm = pos.relative(direction);
        if (!extending && is(world.block(arm), "piston_head"))
            world.setBlock(arm, 0, 276);
        var resolver = new PistonResolver(world, registry, pos, direction, extending);
        if (!resolver.resolve())
            return false;
        var push = resolver.toPush();
        var destroy = resolver.toDestroy();
        var deleted = new HashMap<BlockPosition, Integer>();
        var moved = new ArrayList<Integer>();
        for (BlockPosition from : push) {
            int state = world.block(from);
            moved.add(state);
            deleted.put(from, state);
        }
        var updates = new ArrayList<Integer>();
        for (int i = destroy.size() - 1; i >= 0; i--) {
            BlockPosition from = destroy.get(i);
            int state = world.block(from);
            world.setBlock(from, 0, 18);
            updates.add(state);
        }
        BlockDirection pushDirection = resolver.pushDirection();
        for (int i = push.size() - 1; i >= 0; i--) {
            BlockPosition from = push.get(i);
            int before = world.block(from);
            BlockPosition to = from.relative(pushDirection);
            deleted.remove(to);
            int actual = moving(direction, false);
            world.setBlock(to, actual, 324);
            world.piston(to, new MovingPiston(moved.get(i), direction, extending, false));
            updates.add(before);
        }
        if (extending) {
            int head = facing(properties.defaultState("minecraft:piston_head"), direction);
            head = properties.with(head, "type", sticky ? "sticky" : "normal");
            int actual = moving(direction, sticky);
            deleted.remove(arm);
            world.setBlock(arm, actual, 324);
            world.piston(arm, new MovingPiston(head, direction, true, true));
        }
        for (BlockPosition from : deleted.keySet()) world.setBlock(from, 0, 82);
        for (var entry : deleted.entrySet()) {
            world.neighbourShapes(entry.getValue(), entry.getKey(), 2, true);
            world.neighbourShapes(0, entry.getKey(), 2, false);
            world.neighbourShapes(0, entry.getKey(), 2, true);
        }
        int updateIndex = 0;
        for (int i = destroy.size() - 1; i >= 0; i--) {
            int before = updates.get(updateIndex++);
            BlockPosition from = destroy.get(i);
            world.neighbourShapes(before, from, 2, true);
            world.neighbours(from, before, pushDirection);
        }
        for (int i = push.size() - 1; i >= 0; i--)
            world.neighbours(push.get(i), updates.get(updateIndex++), pushDirection);
        if (extending)
            world.neighbours(arm, properties.defaultState("minecraft:piston_head"), pushDirection);
        return true;
    }

    public void finalTick(BlockPosition pos) {
        MovingPiston piston = world.piston(pos);
        if (piston == null)
            return;
        world.piston(pos, null);
        if (is(world.block(pos), "moving_piston")) {
            int state = piston.source() ? 0 : world.updateFromNeighbours(piston.movedState(), pos);
            world.setBlock(pos, state, 3);
            world.neighbours(pos, state, piston.movementDirection());
        }
    }

    public void tick(BlockPosition pos, long gameTime) {
        MovingPiston piston = world.piston(pos);
        if (piston == null)
            return;
        if (piston.progress() >= 1.0F) {
            if (piston.deathTicks() < 5)
                world.piston(pos, piston.waitForRemoval(gameTime));
            else {
                world.piston(pos, null);
                if (is(world.block(pos), "moving_piston")) {
                    int state = world.updateFromNeighbours(piston.movedState(), pos);
                    if (registry.isAir(state)) {
                        world.setBlock(pos, piston.movedState(), 340);
                        // Block.updateOrDestroy: client AIR replacement is setBlock, not server destruction/drops.
                        world.setBlock(pos, state, 3);
                    } else {
                        if ("true".equals(properties.value(state, "waterlogged")))
                            state = properties.with(state, "waterlogged", "false");
                        world.setBlock(pos, state, 67);
                        world.neighbours(pos, state, piston.movementDirection());
                    }
                }
            }
        } else {
            float next = piston.progress() + 0.5F;
            world.displacement(pos, piston, next);
            world.piston(pos, piston.advance(gameTime));
        }
    }

    public interface World extends PistonResolver.World {

        MovingPiston piston(BlockPosition pos);

        void piston(BlockPosition pos, MovingPiston value);

        void setBlock(BlockPosition pos, int state, int flags);

        int updateFromNeighbours(int state, BlockPosition pos);

        void neighbourShapes(int state, BlockPosition pos, int flags, boolean indirect);

        void neighbours(BlockPosition pos, int oldState, BlockDirection push);

        void displacement(BlockPosition pos, MovingPiston piston, float nextProgress);
    }
}
