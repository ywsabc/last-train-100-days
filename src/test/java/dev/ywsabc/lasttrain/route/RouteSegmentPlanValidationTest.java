package dev.ywsabc.lasttrain.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

/**
 * Structural validation of committed route plans: template and interest
 * points must match, both on construction and on load. A corrupt plan record
 * is dropped instead of surviving into the layout, where the realization
 * would otherwise crash on the missing interest point.
 */
class RouteSegmentPlanValidationTest {
    private static final long SEED = 0x4C415354L;

    private static final RouteExit MAIN_LINE =
            new RouteExit(RouteExitKind.MAIN_LINE, RouteGeometry.SEGMENT_LENGTH);

    @Test
    void stationPlansRequireExactlyOneStationPoi() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteSegmentPlan(3, SEED, SegmentTemplate.STATION, List.of(), List.of(MAIN_LINE)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteSegmentPlan(
                        3,
                        SEED,
                        SegmentTemplate.STATION,
                        List.of(new RoutePoi(RoutePoiType.CITY, 24)),
                        List.of(MAIN_LINE)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteSegmentPlan(
                        3,
                        SEED,
                        SegmentTemplate.STATION,
                        List.of(
                                new RoutePoi(RoutePoiType.STATION, 20),
                                new RoutePoi(RoutePoiType.STATION, 40)),
                        List.of(MAIN_LINE)));
    }

    @Test
    void cityBypassPlansRequireExactlyOneCityPoi() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteSegmentPlan(3, SEED, SegmentTemplate.CITY_BYPASS, List.of(), List.of(MAIN_LINE)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteSegmentPlan(
                        3,
                        SEED,
                        SegmentTemplate.CITY_BYPASS,
                        List.of(new RoutePoi(RoutePoiType.STATION, 24)),
                        List.of(MAIN_LINE)));
    }

    @Test
    void straightAndBridgePlansCarryNoPois() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteSegmentPlan(
                        3,
                        SEED,
                        SegmentTemplate.STRAIGHT,
                        List.of(new RoutePoi(RoutePoiType.CITY, 24)),
                        List.of(MAIN_LINE)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteSegmentPlan(
                        3,
                        SEED,
                        SegmentTemplate.BRIDGE_TUNNEL,
                        List.of(new RoutePoi(RoutePoiType.STATION, 24)),
                        List.of(MAIN_LINE)));
    }

    @Test
    void cityBypassPlansRequireExactlyOneBranchExit() {
        RoutePoi city = new RoutePoi(RoutePoiType.CITY, 24);
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteSegmentPlan(
                        3, SEED, SegmentTemplate.CITY_BYPASS, List.of(city), List.of(MAIN_LINE)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteSegmentPlan(
                        3,
                        SEED,
                        SegmentTemplate.CITY_BYPASS,
                        List.of(city),
                        List.of(
                                MAIN_LINE,
                                new RouteExit(RouteExitKind.BRANCH, 20),
                                new RouteExit(RouteExitKind.BRANCH, 40))));
    }

    @Test
    void straightStationAndBridgePlansRejectBranchExits() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteSegmentPlan(
                        3,
                        SEED,
                        SegmentTemplate.STRAIGHT,
                        List.of(),
                        List.of(MAIN_LINE, new RouteExit(RouteExitKind.BRANCH, 20))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteSegmentPlan(
                        3,
                        SEED,
                        SegmentTemplate.STATION,
                        List.of(new RoutePoi(RoutePoiType.STATION, 24)),
                        List.of(MAIN_LINE, new RouteExit(RouteExitKind.BRANCH, 20))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteSegmentPlan(
                        3,
                        SEED,
                        SegmentTemplate.BRIDGE_TUNNEL,
                        List.of(),
                        List.of(MAIN_LINE, new RouteExit(RouteExitKind.BRANCH, 20))));
    }

    @Test
    void validTemplateAndExitCombinationsStillConstruct() {
        RouteExit branch = new RouteExit(RouteExitKind.BRANCH, 24);
        // CITY_BYPASS: main + exactly one branch, next to the city POI.
        assertEquals(
                2,
                new RouteSegmentPlan(
                                3,
                                SEED,
                                SegmentTemplate.CITY_BYPASS,
                                List.of(new RoutePoi(RoutePoiType.CITY, 24)),
                                List.of(MAIN_LINE, branch))
                        .exits()
                        .size());
        // STRAIGHT / STATION / BRIDGE_TUNNEL: main line only.
        for (SegmentTemplate template :
                List.of(SegmentTemplate.STRAIGHT, SegmentTemplate.BRIDGE_TUNNEL)) {
            RouteSegmentPlan plan = new RouteSegmentPlan(
                    3, SEED, template, List.of(), List.of(MAIN_LINE));
            assertEquals(MAIN_LINE, plan.mainExit());
            assertEquals(RouteGeometry.SEGMENT_LENGTH, plan.mainExit().anchorOffset());
        }
        RouteSegmentPlan station = new RouteSegmentPlan(
                3,
                SEED,
                SegmentTemplate.STATION,
                List.of(new RoutePoi(RoutePoiType.STATION, 30)),
                List.of(MAIN_LINE));
        assertEquals(RouteGeometry.SEGMENT_LENGTH, station.mainExit().anchorOffset());
    }

    @Test
    void loadDropsExitCombinationsThatBreakTheTemplateInvariant() {
        // CITY_BYPASS without its required branch exit.
        CompoundTag cityWithoutBranch = validCityTag(5, 24);
        cityWithoutBranch.put("exits", mainOnlyExitsTag());
        assertNull(RouteSegmentPlan.load(cityWithoutBranch));

        // STRAIGHT carrying an unexpected branch exit.
        CompoundTag straightWithBranch = validStraightTag(5);
        ListTag exits = mainOnlyExitsTag();
        CompoundTag branch = new CompoundTag();
        branch.putString("kind", "BRANCH");
        branch.putInt("anchor", 20);
        exits.add(branch);
        straightWithBranch.put("exits", exits);
        assertNull(RouteSegmentPlan.load(straightWithBranch));
    }

    @Test
    void loadDropsStructurallyCorruptPlans() {
        // STATION without the required station interest point.
        CompoundTag noPoi = validStationTag(5);
        noPoi.put("pois", new ListTag());
        assertNull(RouteSegmentPlan.load(noPoi));

        // CITY_BYPASS carrying a station interest point instead of a city one.
        CompoundTag wrongKind = validCityTag(5, 24);
        ListTag stationPoi = new ListTag();
        CompoundTag poiTag = new CompoundTag();
        poiTag.putString("type", "STATION");
        poiTag.putInt("anchor", 24);
        stationPoi.add(poiTag);
        wrongKind.put("pois", stationPoi);
        assertNull(RouteSegmentPlan.load(wrongKind));

        // STRAIGHT with an unexpected interest point.
        CompoundTag extraPoi = validStraightTag(5);
        extraPoi.put("pois", stationPoi);
        assertNull(RouteSegmentPlan.load(extraPoi));
    }

    @Test
    void validPlansStillLoadAndRoundTrip() {
        RouteSegmentPlan station = new RouteSegmentPlan(
                5,
                SEED,
                SegmentTemplate.STATION,
                List.of(new RoutePoi(RoutePoiType.STATION, 30)),
                List.of(MAIN_LINE));
        assertEquals(station, RouteSegmentPlan.load(station.save()));
        assertEquals(station, RouteSegmentPlan.load(RouteSegmentPlan.load(station.save()).save()));
    }

    private static CompoundTag validStationTag(int segment) {
        return new RouteSegmentPlan(
                segment,
                SEED,
                SegmentTemplate.STATION,
                List.of(new RoutePoi(RoutePoiType.STATION, 30)),
                List.of(MAIN_LINE))
                .save();
    }

    private static CompoundTag validCityTag(int segment, int anchor) {
        return new RouteSegmentPlan(
                segment,
                SEED,
                SegmentTemplate.CITY_BYPASS,
                List.of(new RoutePoi(RoutePoiType.CITY, anchor)),
                List.of(
                        MAIN_LINE,
                        new RouteExit(RouteExitKind.BRANCH, anchor)))
                .save();
    }

    private static CompoundTag validStraightTag(int segment) {
        return new RouteSegmentPlan(
                segment,
                SEED,
                SegmentTemplate.STRAIGHT,
                List.of(),
                List.of(MAIN_LINE))
                .save();
    }

    /** An exits list holding only the main-line exit at the segment end. */
    private static ListTag mainOnlyExitsTag() {
        ListTag exits = new ListTag();
        CompoundTag main = new CompoundTag();
        main.putString("kind", "MAIN_LINE");
        main.putInt("anchor", RouteGeometry.SEGMENT_LENGTH);
        exits.add(main);
        return exits;
    }
}
