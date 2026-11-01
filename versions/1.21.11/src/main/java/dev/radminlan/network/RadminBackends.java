package dev.radminlan.network;

import dev.radminlan.config.RadminConfig;

public final class RadminBackends {
    private RadminBackends() {}
    public static RadminBackend create(RadminConfig config) {
        if (config.pipeName != null && !config.pipeName.isBlank()) {
            RadminBackend pipe = new PipeRadminBackend(config);
            if (config.enableCli && config.radminExecutable != null && !config.radminExecutable.isBlank()) {
                return new FallbackRadminBackend(pipe, new WindowsCliBackend(config));
            }
            return pipe;
        }
        if (!config.enableCli) return new UnavailableBackend("CLI integration disabled in config");
        return new WindowsCliBackend(config);
    }
}

