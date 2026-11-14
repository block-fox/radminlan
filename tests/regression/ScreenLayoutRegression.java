import dev.radminlan.ui.RadminListLayout;
import dev.radminlan.ui.RadminListPreferences.ViewMode;

/** Exercises the same geometry helpers used by the screen without opening Minecraft. */
public final class ScreenLayoutRegression {
    public static void main(String[] args) {
        check();
    }

    public static void check() {
        int scenarios = 0;
        for (int width : new int[] {320, 427, 640, 854}) {
            for (int height : new int[] {240, 480}) {
                for (ViewMode mode : ViewMode.values()) {
                    for (boolean multiSelect : new boolean[] {false, true}) {
                        checkGeometry(width, height, mode, multiSelect);
                        scenarios++;
                    }
                }
            }
        }
        require(RadminListLayout.columns(640, ViewMode.MEDIUM_ICONS) == 5,
                "Grid columns reserve the actual side margins before fitting 96px tiles");
        require(RadminListLayout.rowsHeight(0, 320, ViewMode.LIST) == 0, "Empty lists occupy no rows");
        require(RadminListLayout.rowsHeight(-1, 320, ViewMode.LIST) == 0, "Absent count occupies no rows");
        require(RadminListLayout.cellAt(0, 320, 129, 0, null).height() == ViewMode.LIST.rowHeight(),
                "Missing mode uses list geometry");
        try {
            RadminListLayout.cellAt(-1, 320, 129, 0, ViewMode.LIST);
            throw new AssertionError("Negative indexes must be rejected");
        } catch (IllegalArgumentException expected) {
        }
        System.out.println("SCREEN_LAYOUT_REGRESSION_OK scenarios=" + scenarios + " modes=8 widths=4 heights=2");
    }

    private static void checkGeometry(int width, int height, ViewMode mode, boolean multiSelect) {
        String scenario = "width=" + width + " height=" + height + " mode=" + mode + " multi=" + multiSelect;
        int listTop = 122;
        int listBottom = height - 44;
        int headerOffset = mode == ViewMode.DETAILS ? 23 : 7;
        int contentTop = listTop + headerOffset;
        int rowsTop = mode == ViewMode.DETAILS ? contentTop : listTop;
        int columns = RadminListLayout.columns(width, mode);
        require(columns > 0, "Positive column count: " + scenario);
        if (!mode.isGrid()) require(columns == 1, "Full-width modes use one column: " + scenario);
        int maxItems = columns * 3 + 1;
        for (int index = 0; index < maxItems; index++) {
            RadminListLayout.Cell cell = RadminListLayout.cellAt(index, width, contentTop, 0, mode);
            require(cell.x() >= 20 && cell.width() > 0 && cell.height() > 0, "Positive in-bounds cell: " + scenario);
            require(cell.x() + cell.width() <= width - 28, "Cell fits list horizontal edge: " + scenario);
            require(cell.height() == mode.rowHeight(), "Selected mode height: " + scenario);
            require(cell.y() == contentTop + index / columns * mode.rowHeight(), "Row position: " + scenario);
            if (mode.isGrid() && width - 48 >= mode.tileWidth()) {
                require(cell.width() >= mode.tileWidth(), "Grid keeps the minimum tile width: " + scenario);
            }
            int textInset = multiSelect ? 26 : 8;
            int reservedSignalWidth = 30;
            int reservedIconWidth = mode == ViewMode.TILES ? 31 : 19;
            require(cell.width() - textInset - reservedIconWidth - reservedSignalWidth > 0,
                    "Entry text retains positive space after controls: " + scenario);
            if (index % columns != 0) {
                RadminListLayout.Cell previous = RadminListLayout.cellAt(index - 1, width, contentTop, 0, mode);
                require(cell.x() >= previous.x() + previous.width() + 4 && cell.y() == previous.y(),
                        "Columns have a gap and never overlap: " + scenario);
            }
            if (index >= columns) {
                RadminListLayout.Cell previousRow = RadminListLayout.cellAt(index - columns, width, contentTop, 0, mode);
                require(cell.y() >= previousRow.y() + previousRow.height(), "Rows never overlap: " + scenario);
            }
        }
        if (mode == ViewMode.DETAILS) {
            require(contentTop - listTop == 23 && rowsTop == contentTop, "Details reserves a fixed column header: " + scenario);
        }
        for (int count : new int[] {1, columns, columns + 1, columns * 3 + 1, 100}) {
            int rowsHeight = RadminListLayout.rowsHeight(count, width, mode);
            int expectedRows = (count + columns - 1) / columns;
            require(rowsHeight == expectedRows * mode.rowHeight(), "Every partial row contributes content height: " + scenario);
            int contentHeight = rowsHeight + headerOffset;
            int maxScrollOffset = Math.max(0, contentHeight - (listBottom - listTop));
            RadminListLayout.Cell last = RadminListLayout.cellAt(count - 1, width, contentTop, maxScrollOffset, mode);
            require(last.y() < listBottom && last.y() + last.height() > rowsTop,
                    "Last item can enter the visible viewport at maximum scroll: " + scenario + " count=" + count);
            require(last.y() + last.height() <= listBottom,
                    "Last item bottom is reachable without being cut off: " + scenario + " count=" + count);
            if (contentHeight > listBottom - listTop) {
                require(last.y() + last.height() == listBottom, "Maximum scroll aligns the last item bottom: " + scenario);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
