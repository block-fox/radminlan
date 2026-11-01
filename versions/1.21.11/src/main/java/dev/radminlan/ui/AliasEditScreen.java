package dev.radminlan.ui;

import dev.radminlan.RadminLanClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Small modal editor for the local Radmin alias. */
public final class AliasEditScreen extends Screen {
    private final RadminScreen parent;
    private final String initial;
    private EditBox aliasBox;
    private String message = "";

    public AliasEditScreen(RadminScreen parent, String initial) {
        super(Component.translatable("screen.radminlan.edit_alias"));
        this.parent = parent;
        this.initial = initial == null ? "" : initial;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(360, width - 24);
        int x = (width - panelWidth) / 2;
        int y = (height - 132) / 2;
        aliasBox = new EditBox(font, x + 16, y + 48, panelWidth - 32, 20,
                Component.translatable("screen.radminlan.alias"));
        aliasBox.setMaxLength(64);
        aliasBox.setValue(initial);
        addRenderableWidget(aliasBox);
        addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.save"), button -> submit())
                .bounds(x + 16, y + 92, (panelWidth - 40) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.cancel"), button -> onClose())
                .bounds(x + 24 + (panelWidth - 40) / 2, y + 92, (panelWidth - 40) / 2, 20).build());
        setInitialFocus(aliasBox);
    }

    private void submit() {
        String alias = aliasBox.getValue().trim();
        if (alias.isBlank()) {
            message = "screen.radminlan.alias_required";
            return;
        }
        message = "screen.radminlan.request_pending";
        RadminLanClient.BACKEND.setAlias(alias).whenComplete((ok, error) -> {
            if (minecraft == null) return;
            minecraft.execute(() -> {
                parent.refreshAfterOperation(error == null && Boolean.TRUE.equals(ok)
                        ? "screen.radminlan.request_sent" : "screen.radminlan.request_failed");
                minecraft.setScreen(parent);
            });
        });
    }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);
        graphics.fill(0, 0, width, height, 0x88000000);
        int panelWidth = Math.min(360, width - 24);
        int x = (width - panelWidth) / 2;
        int y = (height - 132) / 2;
        graphics.fill(x, y, x + panelWidth, y + 132, 0xF0101010);
        graphics.renderOutline(x, y, panelWidth, 132, 0xFF53616D);
        graphics.fill(x, y, x + panelWidth, y + 26, 0xFF202830);
        graphics.drawString(font, title, x + 12, y + 9, 0xFFFFFFFF);
        graphics.drawString(font, Component.translatable("screen.radminlan.alias"), x + 16, y + 34, 0xFFB9C2CA);
        if (!message.isBlank()) graphics.drawString(font, Component.translatable(message), x + 16, y + 118, 0xFFFFCC66);
        super.render(graphics, mouseX, mouseY, delta);
    }
}
