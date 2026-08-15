package dev.ywsabc.lasttrain.route;

/** The deterministic structure class planned for one route segment. */
public enum SegmentTemplate {
    /** Plain eastbound corridor; the guaranteed fallback when nothing else is allowed. */
    STRAIGHT,
    /** Ordinary station anchor carrying one station interest point on the main line. */
    STATION,
    /** Main line passes the city; a branch spur leads to one city interest point. */
    CITY_BYPASS,
    /** Bridge or tunnel corridor reserved for terrain crossings under a hard budget. */
    BRIDGE_TUNNEL
}
