package dev.radminlan.input;

import net.minecraft.SharedConstants;

/** Mouse button ids changed between the 26.2 and 26.3 client APIs. */
public final class MouseButtons {
    // 26.2 reports protocol 776 and 26.3 reports 777.
    private static final boolean SDL_BUTTON_IDS = SharedConstants.getProtocolVersion() >= 777;

    private MouseButtons() {
    }

    public static int left() {
        return SDL_BUTTON_IDS ? 1 : 0;
    }

    public static int right() {
        return SDL_BUTTON_IDS ? 3 : 1;
    }
}
