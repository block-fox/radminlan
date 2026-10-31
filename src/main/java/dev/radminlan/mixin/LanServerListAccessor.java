package dev.radminlan.mixin;

import net.minecraft.client.server.LanServer;
import net.minecraft.client.server.LanServerDetection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Exposes the vanilla LAN detector cache so Radmin entries can share the vanilla list. */
@Mixin(LanServerDetection.LanServerList.class)
public interface LanServerListAccessor {
    @Accessor("servers")
    List<LanServer> radminlan$getServers();
}
