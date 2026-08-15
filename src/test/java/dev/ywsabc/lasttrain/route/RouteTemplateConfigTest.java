package dev.ywsabc.lasttrain.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RouteTemplateConfigTest {
    @Test
    void defaultConfigMixesAllFourTemplates() {
        RouteTemplateConfig config = RouteTemplateConfig.DEFAULT;
        assertTrue(config.straightWeight() > 0);
        assertTrue(config.stationWeight() > 0);
        assertTrue(config.cityBypassWeight() > 0);
        assertTrue(config.bridgeTunnelWeight() > 0);
    }

    @Test
    void negativeWeightsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withWeights(-1, 20, 20, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withWeights(50, -1, 20, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withWeights(50, 20, -1, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withWeights(50, 20, 20, -1));
    }

    @Test
    void poiAnchorRangeMustFitInsideTheSegment() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withPoiAnchorRange(0, 48));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withPoiAnchorRange(
                        16,
                        RouteGeometry.SEGMENT_LENGTH));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withPoiAnchorRange(40, 16));
    }

    @Test
    void bridgeBudgetRequiresPositiveWindowAndCapInsideWindow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withBridgeTunnelBudget(1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withBridgeTunnelBudget(13, 12));
    }

    @Test
    void poiSlotCeilingIsEnforced() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withMaxPoiSlots(
                        RouteTemplateConfig.MAX_POI_SLOTS + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withMaxPoiSlots(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withMaxPoiSlots(-1));
    }

    @Test
    void negativeMinimumGapsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withCityBypassMinGap(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withStationMinGap(-1));
    }

    @Test
    void fingerprintIsDeterministicAndReactsToEveryConfigField() {
        RouteTemplateConfig config = RouteTemplateConfig.DEFAULT;
        long baseline = config.fingerprint();
        assertEquals(baseline, RouteTemplateConfig.DEFAULT.fingerprint());
        assertNotEquals(baseline, config.withWeights(51, 20, 20, 10).fingerprint());
        assertNotEquals(baseline, config.withWeights(50, 21, 20, 10).fingerprint());
        assertNotEquals(baseline, config.withWeights(50, 20, 21, 10).fingerprint());
        assertNotEquals(baseline, config.withWeights(50, 20, 20, 11).fingerprint());
        assertNotEquals(baseline, config.withCityBypassMinGap(2).fingerprint());
        assertNotEquals(baseline, config.withStationMinGap(3).fingerprint());
        assertNotEquals(baseline, config.withBridgeTunnelBudget(2, 12).fingerprint());
        assertNotEquals(baseline, config.withBridgeTunnelBudget(1, 13).fingerprint());
        assertNotEquals(baseline, config.withMaxPoiSlots(2).fingerprint());
        assertNotEquals(baseline, config.withPoiAnchorRange(17, 48).fingerprint());
        assertNotEquals(baseline, config.withPoiAnchorRange(16, 47).fingerprint());
    }
}
