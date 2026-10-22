package ru.minecomplex.mac.entity.attribute;

/** {@code net.minecraft.world.entity.ai.attributes.AttributeModifier}. */
public record AttributeModifier(Identifier id, double amount, Operation operation) {
    public enum Operation {
        ADD_VALUE,
        ADD_MULTIPLIED_BASE,
        ADD_MULTIPLIED_TOTAL
    }

    /** {@code LivingEntity.SPEED_MODIFIER_SPRINTING}: {@code 0.3F}, widened to double. */
    public static final AttributeModifier SPRINTING = new AttributeModifier(Identifier.minecraft("sprinting"), 0.3F,
            Operation.ADD_MULTIPLIED_TOTAL);
}
