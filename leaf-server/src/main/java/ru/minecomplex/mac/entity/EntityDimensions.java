package ru.minecomplex.mac.entity;

/** Vanilla {@code EntityDimensions}. */
public record EntityDimensions(float width, float height, float eyeHeight, EntityAttachments attachments, boolean fixed) {

    /** {@code scale(factor)}. */
    public EntityDimensions scale(float factor) {
        return scale(factor, factor);
    }

    /** {@code scale(width, height)}: fixed dimensions and the identity scale return this value. */
    public EntityDimensions scale(float widthFactor, float heightFactor) {
        return !fixed && (widthFactor != 1.0F || heightFactor != 1.0F)
                ? new EntityDimensions(width * widthFactor, height * heightFactor, eyeHeight * heightFactor,
                        attachments.scale(widthFactor, heightFactor, widthFactor), false)
                : this;
    }
}
