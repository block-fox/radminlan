package dev.radminlan.mixin;

import net.minecraft.client.server.LanServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;

@Mixin(ServerSelectionList.NetworkServerEntry.class)
public interface LanServerEntryDataAccessor {
    @Accessor("serverData")
    LanServer radminlan$getServerData();
}
