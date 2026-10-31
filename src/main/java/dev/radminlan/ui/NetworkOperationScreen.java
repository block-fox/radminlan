package dev.radminlan.ui;

import dev.radminlan.RadminLanClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** Modal form for joining or creating a Radmin network. */
public final class NetworkOperationScreen extends Screen {
    public enum Mode { JOIN, CREATE }

    private final RadminScreen parent;
    private final Mode mode;
    private EditBox nameBox;
    private EditBox passwordBox;
    private EditBox confirmBox;
    private String message = "";

    public NetworkOperationScreen(RadminScreen parent, Mode mode) {
        super(Component.translatable(mode == Mode.CREATE ? "screen.radminlan.create_network" : "screen.radminlan.join_network"));
        this.parent = parent;
        this.mode = mode;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(400, width - 24);
        int x = (width - panelWidth) / 2;
        int y = (height - (mode == Mode.CREATE ? 250 : 212)) / 2;
        int fieldWidth = panelWidth - 40;
        nameBox = new EditBox(font, x + 20, y + 48, fieldWidth, 20, Component.translatable("screen.radminlan.network_name"));
        nameBox.setMaxLength(128);
        nameBox.setHint(Component.translatable("screen.radminlan.network_name_hint"));
        addRenderableWidget(nameBox);

        passwordBox = new EditBox(font, x + 20, y + 92, fieldWidth, 20, Component.translatable("screen.radminlan.password"));
        passwordBox.setMaxLength(128);
        passwordBox.setHint(Component.translatable("screen.radminlan.password"));
        passwordBox.addFormatter((value, cursor) -> FormattedCharSequence.forward("*".repeat(value.length()), Style.EMPTY));
        addRenderableWidget(passwordBox);

        if (mode == Mode.CREATE) {
            confirmBox = new EditBox(font, x + 20, y + 136, fieldWidth, 20, Component.translatable("screen.radminlan.confirm_password"));
            confirmBox.setMaxLength(128);
            confirmBox.setHint(Component.translatable("screen.radminlan.confirm_password"));
            confirmBox.addFormatter((value, cursor) -> FormattedCharSequence.forward("*".repeat(value.length()), Style.EMPTY));
            addRenderableWidget(confirmBox);
        }

        int buttonsY = y + (mode == Mode.CREATE ? 188 : 144);
        addRenderableWidget(Button.builder(Component.translatable(mode == Mode.CREATE
                        ? "screen.radminlan.create_network" : "screen.radminlan.join_network"), button -> submit())
                .bounds(x + 20, buttonsY, (fieldWidth - 8) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.cancel"), button -> onClose())
                .bounds(x + 28 + (fieldWidth - 8) / 2, buttonsY, (fieldWidth - 8) / 2, 20).build());
        setInitialFocus(nameBox);
    }

    private void submit() {
        String name = nameBox.getValue().trim();
        String password = passwordBox.getValue();
        if (name.isBlank()) {
            message = "screen.radminlan.enter_network_name";
            return;
        }
        if (mode == Mode.CREATE && !password.equals(confirmBox.getValue())) {
            message = "screen.radminlan.password_mismatch";
            return;
        }
        message = "screen.radminlan.request_pending";
        var request = mode == Mode.CREATE
                ? RadminLanClient.BACKEND.createNetwork(name, password)
                : RadminLanClient.BACKEND.joinNetwork(name, password);
        request.whenComplete((ok, error) -> {
            if (minecraft == null) return;
            minecraft.execute(() -> {
                String result = error == null && Boolean.TRUE.equals(ok)
                        ? "screen.radminlan.request_sent" : "screen.radminlan.request_failed";
                parent.refreshAfterOperation(result);
                minecraft.setScreenAndShow(parent);
            });
        });
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreenAndShow(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        extractTransparentBackground(graphics);
        graphics.fill(0, 0, width, height, 0x88000000);
        int panelWidth = Math.min(400, width - 24);
        int panelHeight = mode == Mode.CREATE ? 250 : 212;
        int x = (width - panelWidth) / 2;
        int y = (height - panelHeight) / 2;
        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xF0101010);
        graphics.fill(x, y, x + panelWidth, y + 26, 0xFF202830);
        graphics.text(font, title, x + 12, y + 9, 0xFFFFFFFF);
        graphics.text(font, Component.translatable("screen.radminlan.network_name"), x + 20, y + 34, 0xFFB9C2CA);
        graphics.text(font, Component.translatable("screen.radminlan.password"), x + 20, y + 78, 0xFFB9C2CA);
        if (mode == Mode.CREATE) graphics.text(font, Component.translatable("screen.radminlan.confirm_password"), x + 20, y + 122, 0xFFB9C2CA);
        if (!message.isBlank()) graphics.text(font, Component.translatable(message), x + 20, y + panelHeight - 42, 0xFFFFCC66);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
}
