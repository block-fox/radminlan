package dev.radminlan.ui;

/** Shared list geometry, expressed in GUI pixels independently of a Minecraft window. */
public final class RadminListLayout {
    private static final int LEFT = 20;
    private static final int RIGHT = 28;
    private static final int GAP = 4;

    private RadminListLayout() {}

    public record Cell(int x, int y, int width, int height) {}

    public static int columns(int screenWidth, RadminListPreferences.ViewMode mode) {
        int tileWidth = viewMode(mode).tileWidth();
        if (tileWidth <= 0) return 1;
        return Math.max(1, (screenWidth - LEFT - RIGHT + GAP) / (tileWidth + GAP));
    }

    public static Cell cellAt(int index, int screenWidth, int contentTop, int scrollOffset,
                              RadminListPreferences.ViewMode mode) {
        if (index < 0) throw new IllegalArgumentException("List index must not be negative");
        RadminListPreferences.ViewMode effectiveMode = viewMode(mode);
        int columns = columns(screenWidth, effectiveMode);
        int cellWidth = Math.max(1, (screenWidth - LEFT - RIGHT - (columns - 1) * GAP) / columns);
        int rowHeight = effectiveMode.rowHeight();
        return new Cell(LEFT + index % columns * (cellWidth + GAP),
                contentTop + index / columns * rowHeight - scrollOffset, cellWidth, rowHeight);
    }

    public static int rowsHeight(int count, int screenWidth, RadminListPreferences.ViewMode mode) {
        if (count <= 0) return 0;
        RadminListPreferences.ViewMode effectiveMode = viewMode(mode);
        int columns = columns(screenWidth, effectiveMode);
        return ((count - 1) / columns + 1) * effectiveMode.rowHeight();
    }

    private static RadminListPreferences.ViewMode viewMode(RadminListPreferences.ViewMode mode) {
        return mode == null ? RadminListPreferences.ViewMode.LIST : mode;
    }
}
