package dev.radminlan.room;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owns decoded LAN-server favicons for the multiplayer screen lifetime. */
public final class LanRoomFaviconCache {
    private static final int MAX_FAVICON_LENGTH = 256 * 1024;
    private static final int MAX_CACHED_ICONS = 128;
    private static final Map<String, CachedIcon> ICONS = new LinkedHashMap<>(16, 0.75F, true);
    private static long generation;

    private LanRoomFaviconCache() {}

    public static synchronized Identifier texture(Minecraft minecraft, String address, String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_FAVICON_LENGTH) return null;

        CachedIcon cached = ICONS.get(address);
        if (cached != null && cached.data().equals(encoded)) {
            return cached.texture() == null ? null : cached.texture().textureLocation();
        }
        if (cached != null && cached.texture() != null) cached.texture().close();

        FaviconTexture texture = FaviconTexture.forServer(minecraft.getTextureManager(), "radminlan:" + address);
        try {
            int comma = encoded.indexOf(',');
            String base64 = comma >= 0 ? encoded.substring(comma + 1) : encoded;
            byte[] imageBytes = Base64.getDecoder().decode(base64);
            NativeImage image = NativeImage.read(imageBytes);
            if (image.getWidth() != 64 || image.getHeight() != 64) {
                image.close();
                texture.close();
                ICONS.put(address, new CachedIcon(encoded, null));
                trimCache();
                return null;
            }
            texture.upload(image);
            ICONS.put(address, new CachedIcon(encoded, texture));
            trimCache();
            return texture.textureLocation();
        } catch (IllegalArgumentException | IOException ignored) {
            texture.close();
            ICONS.put(address, new CachedIcon(encoded, null));
            trimCache();
            return null;
        }
    }

    private static void trimCache() {
        while (ICONS.size() > MAX_CACHED_ICONS) {
            String eldest = ICONS.keySet().iterator().next();
            CachedIcon removed = ICONS.remove(eldest);
            if (removed != null && removed.texture() != null) removed.texture().close();
        }
    }

    public static synchronized void clear() {
        for (CachedIcon icon : ICONS.values()) {
            if (icon.texture() != null) icon.texture().close();
        }
        ICONS.clear();
        generation++;
    }

    /** Changes whenever clear() invalidates all texture handles. */
    public static synchronized long generation() { return generation; }

    private record CachedIcon(String data, FaviconTexture texture) {}
}
