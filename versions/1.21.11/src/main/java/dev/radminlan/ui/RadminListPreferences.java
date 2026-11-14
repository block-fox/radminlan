package dev.radminlan.ui;

import dev.radminlan.network.RadminBackend;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/** List presentation and filtering state with no Minecraft UI dependencies. */
public final class RadminListPreferences {
    private static final Comparator<String> TEXT_ORDER = String.CASE_INSENSITIVE_ORDER
            .thenComparing(Comparator.naturalOrder());

    public ViewMode viewMode = ViewMode.LIST;
    public NetworkSort networkSort = NetworkSort.NAME;
    public SortDirection networkDirection = SortDirection.ASCENDING;
    public PeerSort peerSort = PeerSort.NAME;
    public SortDirection peerDirection = SortDirection.ASCENDING;
    public boolean excludeDisabled;
    public boolean excludeEnabled;
    public boolean excludePublic;
    public boolean excludePrivate;
    public boolean excludeOffline;
    public boolean excludeOnline;
    /** Empty means every network. Matching is exact, ignoring case and surrounding whitespace. */
    public String peerNetworkFilter = "";
    /** Empty means every connection type. Matching follows the same rules as network names. */
    public String peerConnectionFilter = "";

    public RadminListPreferences copy() {
        RadminListPreferences result = new RadminListPreferences();
        result.viewMode = viewMode;
        result.networkSort = networkSort;
        result.networkDirection = networkDirection;
        result.peerSort = peerSort;
        result.peerDirection = peerDirection;
        result.excludeDisabled = excludeDisabled;
        result.excludeEnabled = excludeEnabled;
        result.excludePublic = excludePublic;
        result.excludePrivate = excludePrivate;
        result.excludeOffline = excludeOffline;
        result.excludeOnline = excludeOnline;
        result.peerNetworkFilter = peerNetworkFilter;
        result.peerConnectionFilter = peerConnectionFilter;
        result.normalize();
        return result;
    }

    /** Restores safe defaults after reading optional or hand-edited persisted values. */
    public void normalize() {
        if (viewMode == null) viewMode = ViewMode.LIST;
        if (networkSort == null) networkSort = NetworkSort.NAME;
        if (networkDirection == null) networkDirection = SortDirection.ASCENDING;
        if (peerSort == null) peerSort = PeerSort.NAME;
        if (peerDirection == null) peerDirection = SortDirection.ASCENDING;
        peerNetworkFilter = text(peerNetworkFilter);
        peerConnectionFilter = text(peerConnectionFilter);
    }

    public boolean matchesNetwork(RadminBackend.Network network, boolean effectiveDisabled) {
        if (network == null) return false;
        return !(effectiveDisabled ? excludeDisabled : excludeEnabled)
                && !(network.publicNetwork() ? excludePublic : excludePrivate);
    }

    public boolean matchesPeer(RadminBackend.Peer peer) {
        return matchesPeer(peer, List.of());
    }

    /** Additional names let a member shared by several networks match each membership. */
    public boolean matchesPeer(RadminBackend.Peer peer, Collection<String> networkNames) {
        if (peer == null || (peer.online() ? excludeOnline : excludeOffline)) return false;
        String networkFilter = text(peerNetworkFilter);
        if (!networkFilter.isEmpty() && !sameText(networkFilter, peer.networkName())
                && (networkNames == null || networkNames.stream().noneMatch(name -> sameText(networkFilter, name)))) {
            return false;
        }
        String connectionFilter = text(peerConnectionFilter);
        return connectionFilter.isEmpty() || sameText(connectionFilter, peer.connectionType());
    }

    public boolean hasNetworkFilters() {
        return excludeDisabled || excludeEnabled || excludePublic || excludePrivate;
    }

    public boolean hasPeerFilters() {
        return excludeOffline || excludeOnline || !text(peerNetworkFilter).isEmpty()
                || !text(peerConnectionFilter).isEmpty();
    }

    public void clearNetworkFilters() {
        excludeDisabled = false;
        excludeEnabled = false;
        excludePublic = false;
        excludePrivate = false;
    }

    public void clearPeerFilters() {
        excludeOffline = false;
        excludeOnline = false;
        peerNetworkFilter = "";
        peerConnectionFilter = "";
    }

    public Comparator<RadminBackend.Network> networkComparator() {
        NetworkSort sort = networkSort == null ? NetworkSort.NAME : networkSort;
        Comparator<RadminBackend.Network> primary = switch (sort) {
            case NAME -> Comparator.comparing(network -> text(network.name()), TEXT_ORDER);
            case TYPE -> Comparator.comparing(RadminBackend.Network::publicNetwork);
            case MEMBER_COUNT -> Comparator.comparingInt(network -> network.peers().size());
        };
        if (networkDirection == SortDirection.DESCENDING) primary = primary.reversed();
        return Comparator.nullsLast(primary
                .thenComparing(network -> text(network.name()), TEXT_ORDER)
                .thenComparing(network -> text(network.guid()), TEXT_ORDER));
    }

    public Comparator<RadminBackend.Peer> peerComparator() {
        return peerComparator(RadminBackend.Peer::networkName);
    }

    /** The resolver can supply the same combined network name shown by the caller. */
    public Comparator<RadminBackend.Peer> peerComparator(Function<RadminBackend.Peer, String> networkDisplayName) {
        Function<RadminBackend.Peer, String> networkName = networkDisplayName == null
                ? RadminBackend.Peer::networkName : networkDisplayName;
        PeerSort sort = peerSort == null ? PeerSort.NAME : peerSort;
        boolean descending = peerDirection == SortDirection.DESCENDING;
        Comparator<RadminBackend.Peer> primary = switch (sort) {
            case NAME -> Comparator.comparing(peer -> text(peer.name()), TEXT_ORDER);
            case NETWORK -> Comparator.comparing(peer -> text(networkName.apply(peer)), TEXT_ORDER);
            case CONNECTION -> Comparator.comparing(peer -> text(peer.connectionType()), TEXT_ORDER);
            case LATENCY -> latencyComparator(descending);
        };
        // Unknown latency always follows real measurements, including in descending order.
        if (descending && sort != PeerSort.LATENCY) primary = primary.reversed();
        return Comparator.nullsLast(primary
                .thenComparing(peer -> text(peer.name()), TEXT_ORDER)
                .thenComparing((left, right) -> Long.compareUnsigned(left.nodeId(), right.nodeId()))
                .thenComparing(peer -> text(peer.virtualIp()), TEXT_ORDER));
    }

    public List<RadminBackend.Network> filteredNetworks(Collection<RadminBackend.Network> networks,
                                                       Predicate<RadminBackend.Network> effectiveDisabled) {
        if (networks == null) return List.of();
        Predicate<RadminBackend.Network> disabled = effectiveDisabled == null
                ? network -> Boolean.FALSE.equals(network.enabled()) : effectiveDisabled;
        return networks.stream().filter(Objects::nonNull)
                .filter(network -> matchesNetwork(network, disabled.test(network)))
                .sorted(networkComparator()).toList();
    }

    public List<RadminBackend.Peer> filteredPeers(Collection<RadminBackend.Peer> peers) {
        return filteredPeers(peers, ignored -> List.of(), RadminBackend.Peer::networkName);
    }

    public List<RadminBackend.Peer> filteredPeers(Collection<RadminBackend.Peer> peers,
                                                Function<RadminBackend.Peer, ? extends Collection<String>> networkNames,
                                                Function<RadminBackend.Peer, String> networkDisplayName) {
        if (peers == null) return List.of();
        return peers.stream().filter(Objects::nonNull)
                .filter(peer -> matchesPeer(peer, networkNames == null ? List.of() : networkNames.apply(peer)))
                .sorted(peerComparator(networkDisplayName)).toList();
    }

    public static List<String> availableNetworkNames(Collection<RadminBackend.Network> networks) {
        return distinctNames(networks == null ? List.of() : networks.stream().filter(Objects::nonNull)
                .map(RadminBackend.Network::name).toList());
    }

    public static List<String> availableConnectionTypes(Collection<RadminBackend.Peer> peers) {
        return distinctNames(peers == null ? List.of() : peers.stream().filter(Objects::nonNull)
                .map(RadminBackend.Peer::connectionType).toList());
    }

    private static List<String> distinctNames(Collection<String> names) {
        TreeMap<String, String> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : names) {
            String trimmed = text(name);
            if (!trimmed.isEmpty()) byName.putIfAbsent(trimmed, trimmed);
        }
        return List.copyOf(new ArrayList<>(byName.values()));
    }

    private static Comparator<RadminBackend.Peer> latencyComparator(boolean descending) {
        return (left, right) -> {
            boolean leftKnown = left.online() && left.latencyMs() >= 0;
            boolean rightKnown = right.online() && right.latencyMs() >= 0;
            if (leftKnown != rightKnown) return leftKnown ? -1 : 1;
            if (!leftKnown) return 0;
            return descending ? Long.compare(right.latencyMs(), left.latencyMs())
                    : Long.compare(left.latencyMs(), right.latencyMs());
        };
    }

    private static boolean sameText(String left, String right) {
        return text(left).equalsIgnoreCase(text(right));
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    public enum ViewMode {
        EXTRA_LARGE_ICONS(120, 160),
        LARGE_ICONS(96, 128),
        MEDIUM_ICONS(72, 96),
        SMALL_ICONS(24, 180),
        LIST(30, 0),
        DETAILS(36, 0),
        TILES(48, 240),
        CONTENT(42, 0);

        private final int rowHeight;
        private final int tileWidth;

        ViewMode(int rowHeight, int tileWidth) {
            this.rowHeight = rowHeight;
            this.tileWidth = tileWidth;
        }

        public int rowHeight() { return rowHeight; }
        /** Zero means the row uses the available list width. */
        public int tileWidth() { return tileWidth; }
        public boolean isGrid() { return tileWidth > 0; }
        public String labelKey() { return "screen.radminlan.view." + name().toLowerCase(Locale.ROOT); }

        public static ViewMode fromName(String name) {
            if (name != null) {
                try {
                    return valueOf(name.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return LIST;
        }
    }

    public enum NetworkSort {
        NAME("name"), TYPE("network_type"), MEMBER_COUNT("member_count");

        private final String key;
        NetworkSort(String key) { this.key = key; }
        public String labelKey() { return "screen.radminlan.sort." + key; }
    }

    public enum PeerSort {
        NAME("name"), NETWORK("network"), CONNECTION("connection"), LATENCY("latency");

        private final String key;
        PeerSort(String key) { this.key = key; }
        public String labelKey() { return "screen.radminlan.sort." + key; }
    }

    public enum SortDirection {
        ASCENDING, DESCENDING;

        public SortDirection opposite() { return this == ASCENDING ? DESCENDING : ASCENDING; }
        public String labelKey() { return "screen.radminlan.sort." + name().toLowerCase(Locale.ROOT); }
    }
}
