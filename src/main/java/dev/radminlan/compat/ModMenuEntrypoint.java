package dev.radminlan.compat;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.radminlan.RadminLanClient;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.text.Text;

public final class ModMenuEntrypoint implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> YetAnotherConfigLib.createBuilder()
                .title(Text.translatable("category.radminlan"))
                .category(ConfigCategory.createBuilder()
                        .name(Text.translatable("category.radminlan"))
                        .option(Option.<Integer>createBuilder()
                                .name(Text.literal("Button X offset"))
                                .binding(0, () -> RadminLanClient.CONFIG.buttonOffsetX, value -> RadminLanClient.CONFIG.buttonOffsetX = value)
                                .controller(dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder::create)
                                .build())
                        .option(Option.<Integer>createBuilder()
                                .name(Text.literal("Button Y offset"))
                                .binding(0, () -> RadminLanClient.CONFIG.buttonOffsetY, value -> RadminLanClient.CONFIG.buttonOffsetY = value)
                                .controller(dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder::create)
                                .build())
                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Enable Radmin CLI"))
                                .binding(true, () -> RadminLanClient.CONFIG.enableCli, value -> RadminLanClient.CONFIG.enableCli = value)
                                .controller(dev.isxander.yacl3.api.controller.BooleanControllerBuilder::create)
                                .build())
                        .option(Option.<Integer>createBuilder()
                                .name(Text.literal("LAN query timeout (ms)"))
                                .binding(1000, () -> RadminLanClient.CONFIG.queryTimeoutMs, value -> RadminLanClient.CONFIG.queryTimeoutMs = Math.max(100, value))
                                .controller(dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder::create)
                                .build())
                        .build())
                .save(RadminLanClient.CONFIG::save)
                .build()
                .generateScreen(parent);
    }
}

