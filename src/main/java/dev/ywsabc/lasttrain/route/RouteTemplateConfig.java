package dev.ywsabc.lasttrain.route;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Appearance rules and slot bounds for deterministic route planning.
 *
 * <p>Weights are relative chances. Gaps are minimum counts of non-matching
 * segments between two appearances. The bridge/tunnel budget allows at most
 * {@code bridgeTunnelMaxPerWindow} bridges in any window of that many
 * consecutive segments. POI anchors are drawn inside
 * {@code [poiAnchorMin, poiAnchorMax]}, relative to the segment entry.</p>
 *
 * <p>Every field participates in {@link #seedSalt()}, which
 * {@link RouteSegmentPlanner} mixes into segment seeds: swapping configs under
 * the same rules version re-rolls future segments instead of silently keeping
 * the old route. Changing the selection logic itself must still bump
 * {@link RouteSegmentPlanner#DEFAULT_ROUTE_RULES_VERSION} so campaigns that
 * already committed plans keep them.</p>
 */
public record RouteTemplateConfig(
        int straightWeight,
        int stationWeight,
        int cityBypassWeight,
        int bridgeTunnelWeight,
        int cityBypassMinGap,
        int stationMinGap,
        int bridgeTunnelMaxPerWindow,
        int bridgeTunnelWindow,
        int maxPoiSlots,
        int poiAnchorMin,
        int poiAnchorMax) {

    /** Hard ceiling for interest point slots per segment. */
    public static final int MAX_POI_SLOTS = 2;

    public static final RouteTemplateConfig DEFAULT = new RouteTemplateConfig(
            50, 20, 20, 10,
            1, 2,
            1, 12,
            1,
            16, 48);

    public RouteTemplateConfig {
        if (straightWeight < 0
                || stationWeight < 0
                || cityBypassWeight < 0
                || bridgeTunnelWeight < 0) {
            throw new IllegalArgumentException("Template weights must not be negative");
        }
        if (cityBypassMinGap < 0 || stationMinGap < 0) {
            throw new IllegalArgumentException("Template gaps must not be negative");
        }
        if (bridgeTunnelWindow < 1) {
            throw new IllegalArgumentException("Bridge/tunnel budget window must be positive");
        }
        if (bridgeTunnelMaxPerWindow < 0 || bridgeTunnelMaxPerWindow > bridgeTunnelWindow) {
            throw new IllegalArgumentException(
                    "Bridge/tunnel budget cap must lie inside its window");
        }
        if (maxPoiSlots < 1 || maxPoiSlots > MAX_POI_SLOTS) {
            throw new IllegalArgumentException(
                    "POI slots must be between 1 and " + MAX_POI_SLOTS);
        }
        if (poiAnchorMin < 1
                || poiAnchorMax >= RouteGeometry.SEGMENT_LENGTH
                || poiAnchorMin > poiAnchorMax) {
            throw new IllegalArgumentException(
                    "POI anchor range must lie inside the segment and be ordered");
        }
    }

    public RouteTemplateConfig withWeights(
            int straightWeight,
            int stationWeight,
            int cityBypassWeight,
            int bridgeTunnelWeight) {
        return new RouteTemplateConfig(
                straightWeight,
                stationWeight,
                cityBypassWeight,
                bridgeTunnelWeight,
                cityBypassMinGap,
                stationMinGap,
                bridgeTunnelMaxPerWindow,
                bridgeTunnelWindow,
                maxPoiSlots,
                poiAnchorMin,
                poiAnchorMax);
    }

    public RouteTemplateConfig withCityBypassMinGap(int cityBypassMinGap) {
        return new RouteTemplateConfig(
                straightWeight,
                stationWeight,
                cityBypassWeight,
                bridgeTunnelWeight,
                cityBypassMinGap,
                stationMinGap,
                bridgeTunnelMaxPerWindow,
                bridgeTunnelWindow,
                maxPoiSlots,
                poiAnchorMin,
                poiAnchorMax);
    }

    public RouteTemplateConfig withStationMinGap(int stationMinGap) {
        return new RouteTemplateConfig(
                straightWeight,
                stationWeight,
                cityBypassWeight,
                bridgeTunnelWeight,
                cityBypassMinGap,
                stationMinGap,
                bridgeTunnelMaxPerWindow,
                bridgeTunnelWindow,
                maxPoiSlots,
                poiAnchorMin,
                poiAnchorMax);
    }

    public RouteTemplateConfig withBridgeTunnelBudget(int bridgeTunnelMaxPerWindow, int bridgeTunnelWindow) {
        return new RouteTemplateConfig(
                straightWeight,
                stationWeight,
                cityBypassWeight,
                bridgeTunnelWeight,
                cityBypassMinGap,
                stationMinGap,
                bridgeTunnelMaxPerWindow,
                bridgeTunnelWindow,
                maxPoiSlots,
                poiAnchorMin,
                poiAnchorMax);
    }

    public RouteTemplateConfig withMaxPoiSlots(int maxPoiSlots) {
        return new RouteTemplateConfig(
                straightWeight,
                stationWeight,
                cityBypassWeight,
                bridgeTunnelWeight,
                cityBypassMinGap,
                stationMinGap,
                bridgeTunnelMaxPerWindow,
                bridgeTunnelWindow,
                maxPoiSlots,
                poiAnchorMin,
                poiAnchorMax);
    }

    public RouteTemplateConfig withPoiAnchorRange(int poiAnchorMin, int poiAnchorMax) {
        return new RouteTemplateConfig(
                straightWeight,
                stationWeight,
                cityBypassWeight,
                bridgeTunnelWeight,
                cityBypassMinGap,
                stationMinGap,
                bridgeTunnelMaxPerWindow,
                bridgeTunnelWindow,
                maxPoiSlots,
                poiAnchorMin,
                poiAnchorMax);
    }

    /**
     * Low and high halves of a 256-bit config digest, in that order.
     */
    public record SeedSalt(long lo, long hi) {}

    /**
     * SHA-256 digest of this config's canonical field sequence, split into
     * two longs for mixing into segment seeds.
     *
     * <p>The digest covers all eleven fields as big-endian ints in component
     * order, so any field change feeds a different salt pair into the seed
     * derivation (distinct sequences hash to distinct digests unless a SHA-256
     * collision exists, which is not constructible in practice). The final
     * 64-bit segment seed is only a further reduction of this digest.</p>
     *
     * <p>The legacy single-long XOR/multiply fingerprint was provably not
     * collision-free: folding the 2^93+ config space into one long cannot be
     * injective, so two configs could share a fingerprint and a config swap
     * silently kept the old route. This method keeps the full field material
     * in the derivation instead of pre-collapsing it.</p>
     *
     * @return the low and high halves of the SHA-256 digest of the field
     *     sequence
     */
    public SeedSalt seedSalt() {
        byte[] fields = new byte[11 * Integer.BYTES];
        int offset = 0;
        offset = putInt(fields, offset, straightWeight);
        offset = putInt(fields, offset, stationWeight);
        offset = putInt(fields, offset, cityBypassWeight);
        offset = putInt(fields, offset, bridgeTunnelWeight);
        offset = putInt(fields, offset, cityBypassMinGap);
        offset = putInt(fields, offset, stationMinGap);
        offset = putInt(fields, offset, bridgeTunnelMaxPerWindow);
        offset = putInt(fields, offset, bridgeTunnelWindow);
        offset = putInt(fields, offset, maxPoiSlots);
        offset = putInt(fields, offset, poiAnchorMin);
        putInt(fields, offset, poiAnchorMax);
        byte[] digest = sha256(fields);
        return new SeedSalt(readLong(digest, 0), readLong(digest, Long.BYTES));
    }

    private static int putInt(byte[] out, int offset, int value) {
        out[offset] = (byte) (value >>> 24);
        out[offset + 1] = (byte) (value >>> 16);
        out[offset + 2] = (byte) (value >>> 8);
        out[offset + 3] = (byte) value;
        return offset + Integer.BYTES;
    }

    private static long readLong(byte[] in, int offset) {
        long value = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            value = (value << 8) | (in[offset + i] & 0xFFL);
        }
        return value;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must exist on every JDK", e);
        }
    }
}
