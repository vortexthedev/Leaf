package ru.minecomplex.mac.world.shulker;

/**
 * Vanilla lid fields; advance only for a resolved client block-entity tick, never for a marker acknowledgement.
 */
public record ShulkerAnimation(int openCount, Status status, float progress, float progressOld) {
    public static ShulkerAnimation closed() {
        return new ShulkerAnimation(0, Status.CLOSED, 0.0F, 0.0F);
    }

    /**
     * ShulkerBoxBlockEntity.triggerEvent. Counts above one leave the animation status unchanged.
     */
    public ShulkerAnimation event(int action, int parameter) {
        if (action != 1) return this;
        Status next = parameter == 0 ? Status.CLOSING : parameter == 1 ? Status.OPENING : status;
        return new ShulkerAnimation(parameter, next, progress, progressOld);
    }

    /**
     * Lid fields only. Neighbour updates and entity displacement require the dynamic-world replay.
     */
    public ShulkerAnimation tick() {
        float next = progress;
        Status nextStatus = status;
        switch (status) {
            case CLOSED -> next = 0.0F;
            case OPENING -> {
                next += 0.1F;
                if (next >= 1.0F) {
                    nextStatus = Status.OPENED;
                    next = 1.0F;
                }
            }
            case OPENED -> next = 1.0F;
            case CLOSING -> {
                next -= 0.1F;
                if (next <= 0.0F) {
                    nextStatus = Status.CLOSED;
                    next = 0.0F;
                }
            }
        }
        return new ShulkerAnimation(openCount, nextStatus, next, progress);
    }

    public boolean isClosed() {
        return status == Status.CLOSED;
    }

    public float getProgress(float partialTick) {
        return progressOld + partialTick * (progress - progressOld);
    }

    public enum Status {CLOSED, OPENING, OPENED, CLOSING}
}
