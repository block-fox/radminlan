import dev.radminlan.network.RadminBackend;
import dev.radminlan.ui.RadminListPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Plain Java checks for Explorer-style presentation, ordering and exclusions. */
public final class ListPreferencesRegression {
    public static void main(String[] args) {
        check();
        System.out.println("List preferences regression passed");
    }

    public static void check() {
        checkViewModes();
        checkNetworkOrderingAndFilters();
        checkPeerOrderingAndFilters();
        checkOptionalValues();
    }

    private static void checkViewModes() {
        RadminListPreferences preferences = new RadminListPreferences();
        require(preferences.viewMode == RadminListPreferences.ViewMode.LIST, "Default view is a list");
        require(RadminListPreferences.ViewMode.values().length == 8, "All eight view modes are present");
        int[] heights = {120, 96, 72, 24, 30, 36, 48, 42};
        int[] widths = {160, 128, 96, 180, 0, 0, 240, 0};
        for (RadminListPreferences.ViewMode mode : RadminListPreferences.ViewMode.values()) {
            require(mode.rowHeight() == heights[mode.ordinal()], "Height for " + mode);
            require(mode.tileWidth() == widths[mode.ordinal()], "Tile width for " + mode);
            require(mode.isGrid() == (mode.tileWidth() > 0), "Grid flag for " + mode);
        }
        require(RadminListPreferences.ViewMode.fromName("large_icons") == RadminListPreferences.ViewMode.LARGE_ICONS,
                "Saved view names ignore case");
        require(RadminListPreferences.ViewMode.fromName("broken") == RadminListPreferences.ViewMode.LIST,
                "Unknown saved view falls back to list");
    }

    private static void checkNetworkOrderingAndFilters() {
        RadminBackend.Peer member = peer("Member", "", "Direct", 1, true, 1);
        RadminBackend.Network alpha = network("Alpha", "a", true, List.of(member, member));
        RadminBackend.Network beta = network("beta [B2]", "b", false, List.of(member));
        RadminBackend.Network gamma = network("Gamma", "g", null, List.of());
        List<RadminBackend.Network> source = List.of(gamma, beta, alpha);
        RadminListPreferences preferences = new RadminListPreferences();
        equal(names(preferences.filteredNetworks(source, null)), List.of("Alpha", "beta [B2]", "Gamma"), "Name ascending");
        preferences.networkDirection = RadminListPreferences.SortDirection.DESCENDING;
        equal(names(preferences.filteredNetworks(source, null)), List.of("Gamma", "beta [B2]", "Alpha"), "Name descending");
        preferences.networkSort = RadminListPreferences.NetworkSort.MEMBER_COUNT;
        equal(names(preferences.filteredNetworks(source, null)), List.of("Alpha", "beta [B2]", "Gamma"), "Member count descending");
        preferences.networkSort = RadminListPreferences.NetworkSort.TYPE;
        preferences.networkDirection = RadminListPreferences.SortDirection.ASCENDING;
        equal(names(preferences.filteredNetworks(source, null)), List.of("Alpha", "Gamma", "beta [B2]"), "Private before public");

        preferences.excludeDisabled = true;
        equal(names(preferences.filteredNetworks(source, null)), List.of("Alpha", "Gamma"), "Backend disabled flag");
        equal(names(preferences.filteredNetworks(source, network -> network == beta || network == gamma)), List.of("Alpha"),
                "Effective local disabled fallback supplied by caller");
        preferences.excludeEnabled = true;
        require(preferences.filteredNetworks(source, null).isEmpty(), "Both enabled exclusions hide every network");
        preferences.clearNetworkFilters();
        preferences.excludePublic = true;
        require(!preferences.matchesNetwork(beta, false) && preferences.matchesNetwork(alpha, false), "Public exclusion");
        preferences.excludePrivate = true;
        require(preferences.filteredNetworks(source, null).isEmpty(), "Both type exclusions hide every network");
        preferences.clearNetworkFilters();
        require(!preferences.hasNetworkFilters(), "Network reset clears every network filter");
        equal(RadminListPreferences.availableNetworkNames(Arrays.asList(beta, alpha, null, network(" alpha ", "x", true, List.of()))),
                List.of("Alpha", "beta [B2]"), "Network menu names are trimmed, unique and sorted");
    }

    private static void checkPeerOrderingAndFilters() {
        RadminBackend.Peer slow = peer("Alpha", "Zulu", "Relay", 120, true, 1);
        RadminBackend.Peer fast = peer("beta", "Alpha", "Direct", 5, true, 2);
        RadminBackend.Peer unknown = peer("Gamma", "Beta", "Direct", -1, true, 3);
        RadminBackend.Peer offline = peer("Delta", "Alpha", "Relay", 0, false, 4);
        List<RadminBackend.Peer> source = List.of(unknown, slow, offline, fast);
        RadminListPreferences preferences = new RadminListPreferences();
        preferences.peerSort = RadminListPreferences.PeerSort.NETWORK;
        equal(peerNames(preferences.filteredPeers(source)), List.of("beta", "Delta", "Gamma", "Alpha"), "Network ordering");
        List<RadminBackend.Peer> resolvedOrder = new ArrayList<>(List.of(slow, fast));
        resolvedOrder.sort(preferences.peerComparator(peer -> peer == slow ? "Aardvark" : "Zebra"));
        equal(peerNames(resolvedOrder), List.of("Alpha", "beta"), "Network sort uses display resolver");
        preferences.peerSort = RadminListPreferences.PeerSort.CONNECTION;
        equal(peerNames(preferences.filteredPeers(source)), List.of("beta", "Gamma", "Alpha", "Delta"), "Connection ordering");
        preferences.peerSort = RadminListPreferences.PeerSort.LATENCY;
        equal(peerNames(preferences.filteredPeers(source)), List.of("beta", "Alpha", "Delta", "Gamma"), "Known latency first ascending");
        preferences.peerDirection = RadminListPreferences.SortDirection.DESCENDING;
        equal(peerNames(preferences.filteredPeers(source)), List.of("Alpha", "beta", "Delta", "Gamma"), "Unknown latency stays last descending");

        preferences.excludeOffline = true;
        require(!preferences.matchesPeer(offline) && preferences.matchesPeer(fast), "Offline exclusion");
        preferences.excludeOnline = true;
        require(preferences.filteredPeers(source).isEmpty(), "Both presence exclusions hide every peer");
        preferences.clearPeerFilters();
        preferences.peerNetworkFilter = " alpha ";
        require(preferences.matchesPeer(fast) && !preferences.matchesPeer(slow), "Specific network exact match ignoring case");
        require(preferences.matchesPeer(slow, List.of("Zulu", "Alpha")), "A shared member matches additional memberships");
        require(!preferences.matchesPeer(slow, List.of("Alpha extended")), "Network filtering is not a substring search");
        preferences.peerConnectionFilter = " direct ";
        equal(peerNames(preferences.filteredPeers(source)), List.of("beta"), "Combined network and connection filters");
        preferences.clearPeerFilters();
        equal(RadminListPreferences.availableConnectionTypes(Arrays.asList(slow, fast, unknown, null,
                peer("Extra", "", " direct ", 1, true, 9))), List.of("Direct", "Relay"), "Connection menu options");
        require(!preferences.hasPeerFilters(), "Peer reset clears every peer filter");
    }

    private static void checkOptionalValues() {
        RadminListPreferences preferences = new RadminListPreferences();
        preferences.viewMode = null;
        preferences.networkSort = null;
        preferences.networkDirection = null;
        preferences.peerSort = null;
        preferences.peerDirection = null;
        preferences.peerNetworkFilter = null;
        preferences.peerConnectionFilter = null;
        preferences.excludeDisabled = true;
        RadminListPreferences copy = preferences.copy();
        require(copy.viewMode == RadminListPreferences.ViewMode.LIST && copy.networkSort == RadminListPreferences.NetworkSort.NAME,
                "Copy normalizes optional persisted enums");
        require(copy.peerNetworkFilter.isEmpty() && copy.peerConnectionFilter.isEmpty(), "Copy normalizes optional strings");
        copy.clearNetworkFilters();
        require(preferences.excludeDisabled, "Copy has independent state");
        require(!preferences.matchesNetwork(null, false) && !preferences.matchesPeer(null), "Null entries do not pass filters");
        require(preferences.filteredNetworks(null, null).isEmpty() && preferences.filteredPeers(null).isEmpty(), "Null input is empty");
        RadminBackend.Peer unnamed = peer(null, null, null, -1, false, 10);
        List<RadminBackend.Peer> peers = new ArrayList<>(Arrays.asList(null, unnamed, peer("Named", "", "", 0, true, 11)));
        peers.sort(preferences.peerComparator());
        require(peers.get(0) == unnamed && peers.get(2) == null, "Comparators handle absent text and put null entries last");
    }

    private static RadminBackend.Network network(String name, String guid, Boolean enabled, List<RadminBackend.Peer> peers) {
        return new RadminBackend.Network(name, guid, "", peers, null, enabled, null, null, null, false);
    }

    private static RadminBackend.Peer peer(String name, String network, String connection, long latency, boolean online, long nodeId) {
        return new RadminBackend.Peer(name, "26.0.0." + nodeId, "", "", latency, online, nodeId, "", connection, network, null, "");
    }

    private static List<String> names(List<RadminBackend.Network> networks) {
        return networks.stream().map(RadminBackend.Network::name).toList();
    }

    private static List<String> peerNames(List<RadminBackend.Peer> peers) {
        return peers.stream().map(RadminBackend.Peer::name).toList();
    }

    private static void equal(Object actual, Object expected, String description) {
        if (!expected.equals(actual)) throw new AssertionError(description + ": expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
