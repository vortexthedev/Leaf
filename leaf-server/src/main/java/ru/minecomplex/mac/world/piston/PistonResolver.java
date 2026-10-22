package ru.minecomplex.mac.world.piston;

import ru.minecomplex.mac.world.BlockDirection;
import ru.minecomplex.mac.world.BlockPosition;
import ru.minecomplex.mac.world.BlockRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Vanilla PistonStructureResolver; preserves ordered push/destroy lists, sticky recursion and collision reordering.
 */
public final class PistonResolver {
    private final World world;
    private final BlockRegistry registry;
    private final PistonProperties properties;
    private final BlockPosition piston, start;
    private final BlockDirection facing, push;
    private final boolean extending;
    private final List<BlockPosition> toPush = new ArrayList<>(), toDestroy = new ArrayList<>();
    public PistonResolver(World world, BlockRegistry registry, BlockPosition piston, BlockDirection facing, boolean extending) {
        this.world = world;
        this.registry = registry;
        this.properties = PistonProperties.get();
        this.piston = piston;
        this.facing = facing;
        this.extending = extending;
        push = extending ? facing : facing.opposite();
        start = piston.relative(facing, extending ? 1 : 2);
    }

    public boolean isPushable(int state, BlockPosition pos, BlockDirection direction, boolean destroy, BlockDirection connection) {
        if (pos.y() < world.minY() || pos.y() > world.maxY() || !world.withinBorder(pos)) return false;
        if (registry.isAir(state)) return true;
        if (direction == BlockDirection.DOWN && pos.y() == world.minY()) return false;
        if (direction == BlockDirection.UP && pos.y() == world.maxY()) return false;
        return properties.intrinsicallyPushable(state, destroy, direction == connection);
    }

    public boolean resolve() {
        toPush.clear();
        toDestroy.clear();
        int next = world.block(start);
        if (!isPushable(next, start, push, false, facing)) {
            if (extending && properties.reaction(next) == PistonProperties.Reaction.DESTROY) {
                toDestroy.add(start);
                return true;
            }
            return false;
        }
        if (!addBlockLine(start, push)) return false;
        for (int i = 0; i < toPush.size(); i++)
            if (sticky(world.block(toPush.get(i))) && !addBranchingBlocks(toPush.get(i))) return false;
        return true;
    }

    private boolean sticky(int state) {
        String name = registry.block(state).name();
        return name.equals("minecraft:slime_block") || name.equals("minecraft:honey_block");
    }

    private boolean canStick(int first, int second) {
        String a = registry.block(first).name(), b = registry.block(second).name();
        if (a.equals("minecraft:honey_block") && b.equals("minecraft:slime_block")) return false;
        if (a.equals("minecraft:slime_block") && b.equals("minecraft:honey_block")) return false;
        return sticky(first) || sticky(second);
    }

    private boolean addBlockLine(BlockPosition origin, BlockDirection connection) {
        int next = world.block(origin);
        if (registry.isAir(next) || !isPushable(next, origin, push, false, connection) || origin.equals(piston) || toPush.contains(origin))
            return true;
        int count = 1;
        if (count + toPush.size() > 12) return false;
        while (sticky(next)) {
            BlockPosition pos = origin.relative(push.opposite(), count);
            int previous = next;
            next = world.block(pos);
            if (registry.isAir(next) || !canStick(previous, next) || !isPushable(next, pos, push, false, push.opposite()) || pos.equals(piston))
                break;
            if (++count + toPush.size() > 12) return false;
        }
        int added = 0;
        for (int i = count - 1; i >= 0; i--) {
            toPush.add(origin.relative(push.opposite(), i));
            added++;
        }
        for (int i = 1; ; i++) {
            BlockPosition pos = origin.relative(push, i);
            int collision = toPush.indexOf(pos);
            if (collision > -1) {
                var head = new ArrayList<>(toPush.subList(0, collision));
                var last = new ArrayList<>(toPush.subList(toPush.size() - added, toPush.size()));
                var middle = new ArrayList<>(toPush.subList(collision, toPush.size() - added));
                toPush.clear();
                toPush.addAll(head);
                toPush.addAll(last);
                toPush.addAll(middle);
                for (int j = 0; j <= collision + added; j++) {
                    BlockPosition branch = toPush.get(j);
                    if (sticky(world.block(branch)) && !addBranchingBlocks(branch)) return false;
                }
                return true;
            }
            next = world.block(pos);
            if (registry.isAir(next)) return true;
            if (!isPushable(next, pos, push, true, push) || pos.equals(piston)) return false;
            if (properties.reaction(next) == PistonProperties.Reaction.DESTROY) {
                toDestroy.add(pos);
                return true;
            }
            if (toPush.size() >= 12) return false;
            toPush.add(pos);
            added++;
        }
    }

    private boolean addBranchingBlocks(BlockPosition pos) {
        int from = world.block(pos);
        for (var direction : BlockDirection.values())
            if (direction.axis != push.axis) {
                BlockPosition neighbour = pos.relative(direction);
                if (canStick(world.block(neighbour), from) && !addBlockLine(neighbour, direction)) return false;
            }
        return true;
    }

    public BlockDirection pushDirection() {
        return push;
    }

    public List<BlockPosition> toPush() {
        return List.copyOf(toPush);
    }

    public List<BlockPosition> toDestroy() {
        return List.copyOf(toDestroy);
    }

    public interface World {
        int block(BlockPosition pos);

        int minY();

        int maxY();

        boolean withinBorder(BlockPosition pos);
    }
}
