package dev.radminlan.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.RenderPipelines;

/** Vanilla button surface with the Radmin VPN shield layered over the left edge. */
public final class RadminEntryButton extends Button.Plain {
    private static final Identifier ICON = Identifier.fromNamespaceAndPath("radminlan", "textures/radmin_pixel.png");

    public RadminEntryButton(int x, int y, int width, int height, Component message, Runnable action) {
        super(x, y, width, height, message, input -> action.run(), Button.DEFAULT_NARRATION);
        setTooltip(net.minecraft.client.gui.components.Tooltip.create(message));
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractContents(graphics, mouseX, mouseY, delta);
        int iconSize = Math.min(16, getHeight() - 4);
        graphics.blit(RenderPipelines.GUI_TEXTURED, ICON, getX() + 4, getY() + (getHeight() - iconSize) / 2,
                0.0F, 0.0F, iconSize, iconSize, 16, 16, 16, 16);
    }
}
