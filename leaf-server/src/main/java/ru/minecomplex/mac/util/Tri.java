package ru.minecomplex.mac.util;

/** Three-valued knowledge of client state: EITHER requires accepting both possible outcomes. */
public enum Tri {
    FALSE,
    TRUE,
    EITHER;

    /** A known answer. */
    public static Tri of(boolean value) {
        return value ? TRUE : FALSE;
    }

    public boolean possible() {
        return this != FALSE;
    }

    public boolean certain() {
        return this == TRUE;
    }
}
