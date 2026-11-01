package dev.radminlan.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.client.Minecraft;

/** Vanilla button surface with the Radmin VPN shield layered over the left edge. */
public final class RadminEntryButton extends Button {
    private static final Identifier ICON = Identifier.fromNamespaceAndPath("radminlan", "textures/radmin_pixel.png");

    public RadminEntryButton(int x, int y, int width, int height, Component message, Runnable action) {
        super(x, y, width, height, message, input -> action.run(), Button.DEFAULT_NARRATION);
        setTooltip(net.minecraft.client.gui.components.Tooltip.create(message));
    }

    @Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.drawCenteredString(Minecraft.getInstance().font, getMessage(),
                getX() + getWidth() / 2 + 5, getY() + (getHeight() - 8) / 2,
                active ? 0xFFFFFFFF : 0xFFA0A0A0);
        int iconSize = Math.min(16, getHeight() - 4);
        graphics.blit(RenderPipelines.GUI_TEXTURED, ICON, getX() + 4, getY() + (getHeight() - iconSize) / 2,
                0.0F, 0.0F, iconSize, iconSize, 16, 16, 16, 16);
    }
}
