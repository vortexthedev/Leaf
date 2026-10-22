package ru.minecomplex.mac.engine.movementtick;

import ru.minecomplex.mac.player.state.PlayerInput;

/**
 * Movement key mappings before KeyboardInput.tick; window/screen facts belong to the client hypothesis.
 */
public final class LocalPlayerControls {

    public record Options(boolean toggleCrouch, boolean toggleSprint, int keyboardBindings, boolean restoreKeysAfterGrab, int pollableKeyboardBindings) {

        public Options(boolean toggleCrouch, boolean toggleSprint, int keyboardBindings, boolean restoreKeysAfterGrab) {
            this(toggleCrouch, toggleSprint, keyboardBindings, restoreKeysAfterGrab, keyboardBindings);
        }
    }

    private int down, releasedByScreen;

    public boolean windowFocused = true, mouseGrabbed;

    private static boolean toggles(int key, Options options) {
        return key == 5 && options.toggleCrouch() || key == 6 && options.toggleSprint();
    }

    /**
     * Delivered key-mapping event, including repeats; this is not a poll of held physical keys.
     */
    public void keyEvent(int key, boolean pressed, Options options) {
        int bit = 1 << key;
        if (toggles(key, options)) {
            if (pressed)
                down ^= bit;
        } else
            down = pressed ? down | bit : down & ~bit;
    }

    public PlayerInput keys() {
        return PlayerInput.fromMask(down);
    }

    public void releaseAll(Options options) {
        for (int key = 0; key < 7; key++) {
            int bit = 1 << key;
            if (key >= 5) {
                if (toggles(key, options) && (down & bit) != 0 || (releasedByScreen & bit) != 0)
                    releasedByScreen |= bit;
                down &= ~bit;
            } else
                keyEvent(key, false, options);
        }
    }

    public void resetToggleKeys() {
        down &= ~96;
    }

    /**
     * Gui/Minecraft.setScreen(non-null), without rendering or native cursor operations.
     */
    public void screenOpened(Options options) {
        mouseGrabbed = false;
        releaseAll(options);
    }

    /**
     * Gui/Minecraft.setScreen(null): restore toggles, then conditionally poll keyboard mappings.
     */
    public void screenClosed(Options options, boolean levelPresent, int physicalKeys) {
        if (levelPresent) {
            for (int key = 5; key < 7; key++) {
                int bit = 1 << key;
                boolean restore = toggles(key, options) && (options.keyboardBindings() & bit) != 0 && (releasedByScreen & bit) != 0;
                releasedByScreen &= ~bit;
                if (restore)
                    keyEvent(key, true, options);
            }
        }
        grabMouse(options, physicalKeys);
    }

    public void grabMouse(Options options, int physicalKeys) {
        if (!windowFocused || mouseGrabbed)
            return;
        if (options.restoreKeysAfterGrab())
            for (int key = 0; key < 7; key++) {
                int bit = 1 << key;
                if ((options.pollableKeyboardBindings() & bit) != 0 && (!toggles(key, options)))
                    keyEvent(key, (physicalKeys & bit) != 0, options);
            }
        mouseGrabbed = true;
    }

    public LocalPlayerControls copy() {
        var copy = new LocalPlayerControls();
        copy.down = down;
        copy.releasedByScreen = releasedByScreen;
        copy.windowFocused = windowFocused;
        copy.mouseGrabbed = mouseGrabbed;
        return copy;
    }
}
