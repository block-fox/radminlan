package dev.radminlan.mixin;

import dev.radminlan.RadminLanClient;
import dev.radminlan.config.RadminConfig;
import dev.radminlan.ui.RadminScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {
    protected MultiplayerScreenMixin(Text title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void radminlan$addButton(CallbackInfo ci) {
        RadminConfig config = RadminLanClient.CONFIG;
        if (config == null) return;
        int x;
        int y;
        switch (config.buttonAnchor) {
            case TOP_LEFT -> { x = 12; y = 12; }
            case TOP_RIGHT -> { x = width - 112; y = 12; }
            case MULTIPLAYER_TITLE -> { x = width / 2 - 210; y = 8; }
            default -> { x = width / 2 - 210; y = 8; }
        }
        x += config.buttonOffsetX;
        y += config.buttonOffsetY;
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.radminlan.title"), button ->
                client.setScreen(new RadminScreen(this)))
                .dimensions(x, y, 102, 20).build());
    }
}

