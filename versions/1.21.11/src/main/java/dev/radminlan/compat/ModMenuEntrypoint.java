package dev.radminlan.compat;

import dev.radminlan.RadminLanClient;
import dev.radminlan.ui.RadminConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class ModMenuEntrypoint implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new RadminConfigScreen(parent);
    }
}
