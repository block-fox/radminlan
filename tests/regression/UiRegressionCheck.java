import com.google.gson.JsonParser;
import dev.radminlan.config.RadminConfig;
import dev.radminlan.input.MouseButtons;
import dev.radminlan.network.RadminBackend;
import dev.radminlan.ui.RadminSettingsPanel;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class UiRegressionCheck {
    public static void main(String[] args) throws Exception {
        boolean legacy = !args[0].equals("26.3");
        int expectedLeft = legacy ? 0 : 1;
        int expectedRight = legacy ? 1 : 3;
        require(MouseButtons.left() == expectedLeft, "left button mapping");
        require(MouseButtons.right() == expectedRight, "right button mapping");
        Button vanilla = Button.builder(Component.literal("Test"), input -> {}).build();
        Method accepts = AbstractWidget.class.getDeclaredMethod("isValidClickButton", MouseButtonInfo.class);
        accepts.setAccessible(true);
        require((boolean) accepts.invoke(vanilla, new MouseButtonInfo(MouseButtons.left(), 0)),
                "left click accepted by the real vanilla widget");
        require(!(boolean) accepts.invoke(vanilla, new MouseButtonInfo(MouseButtons.right(), 0)),
                "right click does not activate vanilla actions");
        var empty = new RadminBackend.RadminSnapshot(RadminBackend.ServiceState.READY, "", "", null, "");
        require(empty.networks().isEmpty(), "missing network list is safe for property views");
        var networks = new ArrayList<RadminBackend.Network>();
        networks.add(new RadminBackend.Network("Test", "abc", "", null));
        var snapshot = new RadminBackend.RadminSnapshot(RadminBackend.ServiceState.READY, "", "", networks, "");
        networks.clear();
        require(snapshot.networks().size() == 1, "UI snapshot is stable during backend changes");
        require(snapshot.networks().getFirst().peers().isEmpty(), "missing member list is safe");
        try {
            snapshot.networks().clear();
            throw new AssertionError("snapshot list must be immutable");
        } catch (UnsupportedOperationException expected) {
        }
        checkRefreshConfiguration(args[0]);
        checkRefreshIntervalInput();
        ListPreferencesRegression.check();
        ScreenLayoutRegression.check();
        NetworkParsingRegression.check();
        LanHeaderRegression.check();
        System.out.println("UI_REGRESSION_OK Minecraft=" + args[0] + " protocol=" + SharedConstants.getProtocolVersion()
            + " left=" + MouseButtons.left() + " right=" + MouseButtons.right());
    }

    static void checkRefreshConfiguration(String minecraftVersion) throws Exception {
        // Set only the loader's directory before RadminConfig initializes its static FILE.
        // The real load/save path then exercises Gson defaults and persisted normalization.
        Path workDir = Path.of(System.getProperty("uiRegression.workDir")).toAbsolutePath().normalize();
        Path isolatedGameDir = Files.createTempDirectory(workDir, "runtime-" + minecraftVersion + "-");
        FabricLoader loader = FabricLoader.getInstance();
        Method setGameDir = loader.getClass().getDeclaredMethod("setGameDir", Path.class);
        setGameDir.setAccessible(true);
        setGameDir.invoke(loader, isolatedGameDir);
        Path configFile = loader.getConfigDir().resolve("radminlan.json");
        require(configFile.startsWith(workDir), "config fixture stays inside the temporary regression directory");

        Files.writeString(configFile, "{\"enableCli\":false,\"queryTimeoutMs\":1700}");
        RadminConfig oldConfig = RadminConfig.load();
        require(oldConfig.autoRefresh, "older JSON enables automatic refresh by default");
        require(oldConfig.refreshIntervalSeconds == 2, "older JSON defaults to a two-second interval");
        require(oldConfig.getRefreshIntervalTicks() == 40, "default interval schedules forty game ticks");
        require(!oldConfig.enableCli && oldConfig.queryTimeoutMs == 1700, "older JSON keeps existing settings");
        require("LIST".equals(oldConfig.networkViewMode), "older JSON defaults the network view to list");
        require("LIST".equals(oldConfig.peerViewMode), "older JSON defaults the peer view to list");
        var migrated = JsonParser.parseString(Files.readString(configFile)).getAsJsonObject();
        require(migrated.get("autoRefresh").getAsBoolean(), "migration saves the default automatic refresh setting");
        require(migrated.get("refreshIntervalSeconds").getAsInt() == 2, "migration saves the default interval");

        oldConfig.networkViewMode = "DETAILS";
        oldConfig.peerViewMode = "LARGE_ICONS";
        oldConfig.save();
        RadminConfig savedViews = RadminConfig.load();
        require("DETAILS".equals(savedViews.networkViewMode), "network view survives save and reload");
        require("LARGE_ICONS".equals(savedViews.peerViewMode), "peer view survives save and reload independently");
        var persistedViews = JsonParser.parseString(Files.readString(configFile)).getAsJsonObject();
        require("DETAILS".equals(persistedViews.get("networkViewMode").getAsString()), "network view is persisted in JSON");
        require("LARGE_ICONS".equals(persistedViews.get("peerViewMode").getAsString()), "peer view is persisted in JSON");

        for (int seconds : new int[] {Integer.MIN_VALUE, -1, 0, 61, Integer.MAX_VALUE}) {
            int expectedSeconds = seconds < 1 ? 1 : 60;
            Files.writeString(configFile, "{\"autoRefresh\":false,\"refreshIntervalSeconds\":" + seconds + "}");
            RadminConfig loaded = RadminConfig.load();
            require(!loaded.autoRefresh, "explicitly disabled refresh survives loading: " + seconds);
            require(loaded.refreshIntervalSeconds == expectedSeconds, "invalid saved interval is normalized: " + seconds);
            require(loaded.getRefreshIntervalTicks() == expectedSeconds * 20, "normalized interval produces safe ticks: " + seconds);
            var saved = JsonParser.parseString(Files.readString(configFile)).getAsJsonObject();
            require(saved.get("refreshIntervalSeconds").getAsInt() == expectedSeconds,
                    "normalized interval is written back to JSON: " + seconds);
        }

        for (int seconds : new int[] {1, 2, 30, 60}) {
            Files.writeString(configFile, "{\"refreshIntervalSeconds\":" + seconds + "}");
            RadminConfig loaded = RadminConfig.load();
            require(loaded.refreshIntervalSeconds == seconds, "valid saved interval is preserved: " + seconds);
            require(loaded.getRefreshIntervalTicks() == seconds * 20, "valid saved interval converts to ticks: " + seconds);
        }
        System.out.println("CONFIG_REGRESSION_OK isolatedConfig=" + configFile);
    }

    static void checkRefreshIntervalInput() throws Exception {
        for (int seconds = 1; seconds <= 60; seconds++) {
            require(Integer.valueOf(seconds).equals(RadminSettingsPanel.parseInterval(Integer.toString(seconds))),
                    "settings screen accepts an integer interval: " + seconds);
        }
        for (String invalid : new String[] {null, "", " ", "\t", "0", "61", "-1", "2.0", "abc", "1 ", " 1", "2147483648"}) {
            require(RadminSettingsPanel.parseInterval(invalid) == null,
                    "settings screen rejects invalid interval text: " + invalid);
        }
        System.out.println("INTERVAL_INPUT_REGRESSION_OK validRange=1..60 invalidCases=12");
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
