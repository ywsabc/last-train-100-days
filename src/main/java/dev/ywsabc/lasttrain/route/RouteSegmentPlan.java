package dev.ywsabc.lasttrain.route;

import dev.ywsabc.lasttrain.LastTrain;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * The committed plan of one route segment: template, interest points and
 * exits, all derived from the segment seed.
 *
 * <p>Invariant: every plan carries exactly one {@link RouteExitKind#MAIN_LINE}
 * exit, so a side branch can never replace the way forward, and the interest
 * points match the template — STATION carries exactly one station interest
 * point, CITY_BYPASS exactly one city interest point, and the straight and
 * bridge/tunnel templates carry none. Construction rejects plans that break
 * either invariant, so the realization layout can rely on the interest
 * points it reads instead of crashing on a missing entry.</p>
 *
 * <p>Plans for pending (planned but not yet realized) segments are persisted
 * in the campaign save so committed plans are adopted as-is on reload and
 * never silently re-rolled. A structurally corrupt persisted record is
 * dropped with a log line — like every other corrupt save entry — and the
 * planner re-derives the segment deterministically.</p>
 */
public record RouteSegmentPlan(
        int segmentIndex,
        long segmentSeed,
        SegmentTemplate template,
        List<RoutePoi> pois,
        List<RouteExit> exits) {

    public RouteSegmentPlan {
        if (segmentIndex < 1) {
            throw new IllegalArgumentException("Route segments start at 1");
        }
        Objects.requireNonNull(template, "template");
        pois = List.copyOf(Objects.requireNonNull(pois, "pois"));
        exits = List.copyOf(Objects.requireNonNull(exits, "exits"));
        long mainExitCount = exits.stream()
                .filter(exit -> exit.kind() == RouteExitKind.MAIN_LINE)
                .count();
        if (mainExitCount != 1) {
            throw new IllegalArgumentException(
                    "Every route segment must have exactly one main-line exit, got "
                            + mainExitCount);
        }
        requireTemplateShape(template, pois);
    }

    /** Structural template invariant: interest points must match the template. */
    private static void requireTemplateShape(SegmentTemplate template, List<RoutePoi> pois) {
        switch (template) {
            case STATION -> {
                if (pois.size() != 1 || pois.get(0).type() != RoutePoiType.STATION) {
                    throw new IllegalArgumentException(
                            "A station plan must carry exactly one station interest point, got "
                                    + pois);
                }
            }
            case CITY_BYPASS -> {
                if (pois.size() != 1 || pois.get(0).type() != RoutePoiType.CITY) {
                    throw new IllegalArgumentException(
                            "A city bypass plan must carry exactly one city interest point, got "
                                    + pois);
                }
            }
            case STRAIGHT, BRIDGE_TUNNEL -> {
                if (!pois.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Straight and bridge plans carry no interest points, got " + pois);
                }
            }
        }
    }

    /** The single main-line exit; plans without one are rejected on construction. */
    public RouteExit mainExit() {
        for (RouteExit exit : exits) {
            if (exit.kind() == RouteExitKind.MAIN_LINE) {
                return exit;
            }
        }
        throw new IllegalStateException("Segment " + segmentIndex + " lost its main-line exit");
    }

    /** Serializes this plan into a new NBT compound. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("segment", segmentIndex);
        tag.putLong("seed", segmentSeed);
        tag.putString("template", template.name());
        ListTag poisTag = new ListTag();
        for (RoutePoi poi : pois) {
            CompoundTag poiTag = new CompoundTag();
            poiTag.putString("type", poi.type().name());
            poiTag.putInt("anchor", poi.anchorOffset());
            poisTag.add(poiTag);
        }
        tag.put("pois", poisTag);
        ListTag exitsTag = new ListTag();
        for (RouteExit exit : exits) {
            CompoundTag exitTag = new CompoundTag();
            exitTag.putString("kind", exit.kind().name());
            exitTag.putInt("anchor", exit.anchorOffset());
            exitsTag.add(exitTag);
        }
        tag.put("exits", exitsTag);
        return tag;
    }

    /**
     * Parses a persisted plan, or null when the entry is malformed or
     * structurally corrupt. Corrupt entries are dropped with a log line
     * exactly like other corrupt save records: a faulty save can never stop
     * loading, and the planner re-derives the missing segment
     * deterministically.
     */
    public static RouteSegmentPlan load(CompoundTag tag) {
        if (tag == null
                || !tag.contains("segment", Tag.TAG_INT)
                || !tag.contains("seed", Tag.TAG_LONG)
                || !tag.contains("template", Tag.TAG_STRING)) {
            return dropCorrupt("missing segment, seed or template key");
        }
        int segment = tag.getInt("segment");
        long seed = tag.getLong("seed");
        SegmentTemplate template = enumByName(SegmentTemplate.class, tag.getString("template"));
        if (template == null) {
            return dropCorrupt("unknown template \"" + tag.getString("template") + "\"");
        }
        List<RoutePoi> pois = new ArrayList<>();
        for (Tag entry : tag.getList("pois", Tag.TAG_COMPOUND)) {
            CompoundTag poiTag = (CompoundTag) entry;
            RoutePoiType type = enumByName(RoutePoiType.class, poiTag.getString("type"));
            if (type == null) {
                return dropCorrupt("unknown interest point type \""
                        + poiTag.getString("type") + "\"");
            }
            try {
                pois.add(new RoutePoi(type, poiTag.getInt("anchor")));
            } catch (IllegalArgumentException rejected) {
                return dropCorrupt(rejected.getMessage());
            }
        }
        List<RouteExit> exits = new ArrayList<>();
        for (Tag entry : tag.getList("exits", Tag.TAG_COMPOUND)) {
            CompoundTag exitTag = (CompoundTag) entry;
            RouteExitKind kind = enumByName(RouteExitKind.class, exitTag.getString("kind"));
            if (kind == null) {
                return dropCorrupt("unknown exit kind \"" + exitTag.getString("kind") + "\"");
            }
            try {
                exits.add(new RouteExit(kind, exitTag.getInt("anchor")));
            } catch (IllegalArgumentException rejected) {
                return dropCorrupt(rejected.getMessage());
            }
        }
        try {
            return new RouteSegmentPlan(segment, seed, template, pois, exits);
        } catch (IllegalArgumentException | NullPointerException rejected) {
            return dropCorrupt(rejected.getMessage());
        }
    }

    /** Logs one corrupt persisted plan record and reports it unloadable. */
    private static RouteSegmentPlan dropCorrupt(String reason) {
        LastTrain.LOGGER.warn("Dropping corrupt route plan entry: {}", reason);
        return null;
    }

    private static <E extends Enum<E>> E enumByName(Class<E> type, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
