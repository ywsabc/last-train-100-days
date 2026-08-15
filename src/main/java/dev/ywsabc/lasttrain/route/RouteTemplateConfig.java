package dev.ywsabc.lasttrain.route;

/**
 * Appearance rules and slot bounds for deterministic route planning.
 *
 * <p>Weights are relative chances. Gaps are minimum counts of non-matching
 * segments between two appearances. The bridge/tunnel budget allows at most
 * {@code bridgeTunnelMaxPerWindow} bridges in any window of that many
 * consecutive segments. POI anchors are drawn inside
 * {@code [poiAnchorMin, poiAnchorMax]}, relative to the segment entry.</p>
 *
 * <p>Every field participates in {@link #fingerprint()}, which
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

    private static final long FINGERPRINT_BASE = 0x9E3779B97F4A7C15L;
    private static final long FINGERPRINT_FOLD = 0xBF58476D1CE4E5B9L;

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
     * Stable content fingerprint of every planning input of this config.
     *
     * <p>All weights, minimum gaps, the bridge/tunnel budget and the POI
     * anchor range are folded into one long. Each fold step is invertible, so
     * two configs with any differing field can never share a fingerprint.</p>
     *
     * @return a deterministic long identifying this exact config content
     */
    public long fingerprint() {
        long mixed = FINGERPRINT_BASE;
        mixed = fold(mixed, straightWeight);
        mixed = fold(mixed, stationWeight);
        mixed = fold(mixed, cityBypassWeight);
        mixed = fold(mixed, bridgeTunnelWeight);
        mixed = fold(mixed, cityBypassMinGap);
        mixed = fold(mixed, stationMinGap);
        mixed = fold(mixed, bridgeTunnelMaxPerWindow);
        mixed = fold(mixed, bridgeTunnelWindow);
        mixed = fold(mixed, maxPoiSlots);
        mixed = fold(mixed, poiAnchorMin);
        mixed = fold(mixed, poiAnchorMax);
        return mixed;
    }

    private static long fold(long mixed, int field) {
        return (mixed ^ field) * FINGERPRINT_FOLD;
    }
}
