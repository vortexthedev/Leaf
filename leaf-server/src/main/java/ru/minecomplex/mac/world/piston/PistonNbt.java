package ru.minecomplex.mac.world.piston;

import ru.minecomplex.mac.world.BinaryNbt;
import ru.minecomplex.mac.world.BlockDirection;
import ru.minecomplex.mac.world.BlockRegistry;

import java.util.Map;

/**
 * PistonMovingBlockEntity.loadAdditional; native BlockState/Direction codecs.
 */
public final class PistonNbt {
    private PistonNbt() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    private static BinaryNbt.Tag field(Map<String, BinaryNbt.Tag> map, String name) {
        return map.getOrDefault(name, BinaryNbt.EMPTY);
    }

    public static MovingPiston load(MovingPiston previous, byte[] bytes, BlockRegistry registry) {
        var input = BinaryNbt.root(bytes).compound();
        int state = blockState(field(input, "blockState"), registry, true, true);
        var facing = field(input, "facing");
        int direction = facing.number().byteValue();
        float progress = field(input, "progress").number().floatValue();
        boolean extending = field(input, "extending").byteValue() != 0, source = field(input, "source").byteValue() != 0;
        return new MovingPiston(state, BlockDirection.legacy(direction), extending, source, progress, progress, previous.deathTicks(), previous.lastTicked());
    }

    private static int blockState(BinaryNbt.Tag tag, BlockRegistry registry, boolean codec, boolean compact) {
        var map = tag.compound();
        String name = compact && tag.type() == 8 ? tag.string() : field(map, compact ? "id" : "Name").string();
        if (name.isEmpty())
            return 0;
        if (!name.contains(":"))
            name = "minecraft:" + name;
        else if (name.startsWith(":"))
            name = "minecraft" + name;
        if (!name.matches("[a-z0-9_.-]+:[a-z0-9/._-]*")) {
            if (codec)
                return 0;
            throw new IllegalArgumentException("Vanilla rejects block identifier " + name);
        }
        var block = registry.block(name);
        if (block == null)
            return 0;
        var facts = PistonProperties.get();
        int defaults = facts.defaultState(block.firstStateId());
        int result = defaults;
        var props = field(map, compact ? "properties" : "Properties").compound();
        for (var entry : props.entrySet()) {
            String property = entry.getKey();
            boolean known = false;
            for (String part : registry.properties(defaults).split(","))
                if (part.startsWith(property + "=")) {
                    known = true;
                    break;
                }
            if (!known)
                continue;
            try {
                result = facts.with(result, property, entry.getValue().string());
            } catch (IllegalArgumentException badValue) {
                /* Both codecs retain the default for this property. */
            }
        }
        return result;
    }
}
