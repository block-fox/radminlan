package dev.radminlan.network;

import dev.radminlan.config.RadminConfig;

public final class RadminBackends {
    private RadminBackends() {}
    public static RadminBackend create(RadminConfig config) {
        if (!config.enableCli) return new UnavailableBackend("CLI integration disabled in config");
        return new WindowsCliBackend(config);
    }
}

