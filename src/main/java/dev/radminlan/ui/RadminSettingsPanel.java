package dev.radminlan.ui;

import dev.radminlan.RadminLanClient;
import dev.radminlan.config.RadminConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** Reusable settings body. Its host owns widget registration, clipping and scrolling. */
public final class RadminSettingsPanel {
    private static final int SECTION_HEIGHT = 24;
    private static final int ROW_HEIGHT = 56;
    private static final int SECTION_GAP = 12;
    private static final int CONTENT_HEIGHT = SECTION_HEIGHT * 3 + ROW_HEIGHT * 5 + SECTION_GAP * 2 + 22;

    private Font font;
    private int panelX;
    private int panelWidth;
    private int originY;
    private int scrollOffset;
    private boolean compact;
    private String intervalDraft;
    private boolean intervalValid = true;
    private Button positionButton;
    private Button timeoutButton;
    private Button cliButton;
    private Button autoRefreshButton;
    private EditBox intervalBox;
    private List<AbstractWidget> widgets = List.of();
    private final List<Section> sections = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();

    /** Widgets are returned for addWidget(), and are reused when only scrolling changes. */
    public List<AbstractWidget> build(Font font, int screenWidth, int top, int scrollOffset) {
        if (font == null) throw new IllegalArgumentException("Settings require a font");
        boolean recreate = this.font != font || intervalBox == null;
        this.font = font;
        this.panelWidth = Math.max(1, Math.min(520, screenWidth - 40));
        this.panelX = (screenWidth - panelWidth) / 2;
        this.scrollOffset = Math.max(0, scrollOffset);
        this.originY = top - this.scrollOffset;
        this.compact = panelWidth < 420;
        if (intervalDraft == null) intervalDraft = Integer.toString(config().getRefreshIntervalTicks() / 20);
        if (recreate) createWidgets();

        positionButton.setMessage(positionValue());
        timeoutButton.setMessage(timeoutValue());
        cliButton.setMessage(toggleValue(config().enableCli));
        autoRefreshButton.setMessage(toggleValue(config().autoRefresh));
        sections.clear();
        rows.clear();
        int y = 0;
        y = addSection("screen.radminlan.settings.interface", y);
        y = addRow("screen.radminlan.button_position", "screen.radminlan.settings.position_description", positionButton, y);
        y = addSection("screen.radminlan.settings.connection", y + SECTION_GAP);
        y = addRow("screen.radminlan.settings.timeout_label", "screen.radminlan.settings.timeout_description", timeoutButton, y);
        y = addRow("screen.radminlan.settings.cli_label", "screen.radminlan.settings.cli_description", cliButton, y);
        y = addSection("screen.radminlan.settings.refresh", y + SECTION_GAP);
        y = addRow("screen.radminlan.settings.auto_refresh_label", "screen.radminlan.settings.auto_refresh_description", autoRefreshButton, y);
        addRow("screen.radminlan.refresh_interval", "screen.radminlan.settings.interval_description", intervalBox, y);
        validateInterval(false);
        return widgets;
    }

    public int contentHeight() { return CONTENT_HEIGHT; }

    /** The host can retain this separately from the network/member list scroll offset. */
    public int scrollOffset() { return scrollOffset; }

    public boolean isIntervalValid() { return intervalValid; }

    /** Draws the body decoration and text; the host extracts every returned widget once. */
    public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (font == null) return;
        for (Section section : sections) {
            int y = originY + section.offset();
            graphics.text(font, fit(Component.translatable(section.title()).getString(), panelWidth - 24),
                    panelX + 12, y + 7, 0xFFB9D6EA);
            graphics.fill(panelX + 12, y + SECTION_HEIGHT - 2, panelX + panelWidth - 12, y + SECTION_HEIGHT - 1, 0x665A6D7B);
        }
        for (Row row : rows) {
            int y = originY + row.offset();
            graphics.fill(panelX, y, panelX + panelWidth, y + ROW_HEIGHT - 1, 0x55121920);
            int labelWidth = row.control().getX() - panelX - 24;
            List<FormattedCharSequence> labelLines = font.split(Component.translatable(row.label()), Math.max(1, labelWidth));
            int lineCount = Math.min(2, labelLines.size());
            for (int line = 0; line < lineCount; line++) {
                graphics.text(font, labelLines.get(line), panelX + 12, y + 8 + line * 11, 0xFFE6EDF3);
            }
            boolean invalidInterval = row.control() == intervalBox && !intervalValid;
            String description = Component.translatable(invalidInterval
                    ? "screen.radminlan.refresh_interval_invalid" : row.description()).getString();
            int descriptionWidth = compact ? panelWidth - 24 : labelWidth;
            graphics.text(font, fit(description, descriptionWidth), panelX + 12, y + 39,
                    invalidInterval ? 0xFFFF7777 : 0xFF8E9BA6);
        }
        graphics.text(font, fit(Component.translatable("screen.radminlan.settings.saved_automatically").getString(), panelWidth - 24),
                panelX + 12, originY + CONTENT_HEIGHT - 14, 0xFF81909B);
    }

    /** Saves valid edits; an unfinished invalid interval keeps the last saved value. */
    public void save() {
        if (intervalBox != null) intervalDraft = intervalBox.getValue();
        Integer seconds = parseInterval(intervalDraft);
        if (seconds != null) config().refreshIntervalSeconds = seconds;
        config().save();
        validateInterval(false);
    }

    public static Integer parseInterval(String text) {
        try {
            int seconds = Integer.parseInt(text);
            return seconds >= RadminConfig.MIN_REFRESH_INTERVAL_SECONDS
                    && seconds <= RadminConfig.MAX_REFRESH_INTERVAL_SECONDS ? seconds : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void createWidgets() {
        positionButton = Button.builder(positionValue(), button -> {
            config().buttonAnchor = nextAnchor(config().buttonAnchor);
            button.setMessage(positionValue());
            save();
        }).bounds(0, 0, 100, 20).build();
        timeoutButton = Button.builder(timeoutValue(), button -> {
            int current = config().queryTimeoutMs;
            config().queryTimeoutMs = current < 100 || current >= 2000 ? 100 : Math.min(2000, current + 100);
            button.setMessage(timeoutValue());
            save();
        }).bounds(0, 0, 100, 20).build();
        cliButton = Button.builder(toggleValue(config().enableCli), button -> {
            config().enableCli = !config().enableCli;
            button.setMessage(toggleValue(config().enableCli));
            save();
        }).bounds(0, 0, 100, 20).build();
        autoRefreshButton = Button.builder(toggleValue(config().autoRefresh), button -> {
            config().autoRefresh = !config().autoRefresh;
            button.setMessage(toggleValue(config().autoRefresh));
            save();
        }).bounds(0, 0, 100, 20).build();
        intervalBox = new EditBox(font, 0, 0, 100, 20, Component.translatable("screen.radminlan.refresh_interval"));
        intervalBox.setMaxLength(2);
        intervalBox.setValue(intervalDraft);
        intervalBox.setResponder(value -> {
            intervalDraft = value;
            validateInterval(true);
        });
        widgets = List.of(positionButton, timeoutButton, cliButton, autoRefreshButton, intervalBox);
    }

    private int addSection(String title, int offset) {
        sections.add(new Section(title, offset));
        return offset + SECTION_HEIGHT;
    }

    private int addRow(String label, String description, AbstractWidget widget, int offset) {
        int controlWidth = compact ? Math.min(146, Math.max(100, panelWidth * 38 / 100)) : 170;
        widget.setX(panelX + panelWidth - 12 - controlWidth);
        widget.setY(originY + offset + (compact ? 9 : 12));
        widget.setWidth(controlWidth);
        widget.setTooltip(Tooltip.create(Component.translatable(label).append("\n").append(Component.translatable(description))));
        rows.add(new Row(label, description, widget, offset));
        return offset + ROW_HEIGHT;
    }

    private void validateInterval(boolean saveValid) {
        Integer seconds = parseInterval(intervalDraft);
        intervalValid = seconds != null;
        if (intervalBox != null) {
            intervalBox.setTextColor(intervalValid ? 0xFFE6EDF3 : 0xFFFF7777);
            if (!intervalValid) intervalBox.setTooltip(Tooltip.create(Component.translatable("screen.radminlan.refresh_interval_invalid")));
            else intervalBox.setTooltip(Tooltip.create(Component.translatable("screen.radminlan.refresh_interval")
                    .append("\n").append(Component.translatable("screen.radminlan.settings.interval_description"))));
        }
        if (saveValid && seconds != null) {
            config().refreshIntervalSeconds = seconds;
            config().save();
        }
    }

    private String fit(String value, int width) {
        if (font.width(value) <= width) return value;
        return font.plainSubstrByWidth(value, Math.max(1, width - font.width("…"))) + "…";
    }

    private static RadminConfig config() { return RadminLanClient.CONFIG; }

    private static Component timeoutValue() {
        return Component.translatable("screen.radminlan.settings.timeout_value", config().queryTimeoutMs);
    }

    private static Component toggleValue(boolean enabled) {
        return Component.translatable(enabled ? "screen.radminlan.enabled" : "screen.radminlan.disabled");
    }

    private static Component positionValue() {
        String key = config().buttonAnchor == null ? "title_before" : switch (config().buttonAnchor) {
            case TITLE_AFTER -> "title_after";
            case BOTTOM_LEFT -> "bottom_left";
            case BOTTOM_RIGHT -> "bottom_right";
            case MULTIPLAYER_TITLE, TITLE_BEFORE -> "title_before";
        };
        return Component.translatable("screen.radminlan.button_position." + key);
    }

    private static RadminConfig.ButtonAnchor nextAnchor(RadminConfig.ButtonAnchor current) {
        return switch (current == null ? RadminConfig.ButtonAnchor.TITLE_BEFORE : current) {
            case TITLE_BEFORE, MULTIPLAYER_TITLE -> RadminConfig.ButtonAnchor.TITLE_AFTER;
            case TITLE_AFTER -> RadminConfig.ButtonAnchor.BOTTOM_LEFT;
            case BOTTOM_LEFT -> RadminConfig.ButtonAnchor.BOTTOM_RIGHT;
            case BOTTOM_RIGHT -> RadminConfig.ButtonAnchor.TITLE_BEFORE;
        };
    }

    private record Section(String title, int offset) {}
    private record Row(String label, String description, AbstractWidget control, int offset) {}
}
