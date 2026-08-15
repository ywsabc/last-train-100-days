package dev.ywsabc.lasttrain.route;

/** Role of a planned track exit. */
public enum RouteExitKind {
    /** The only exit that continues the main line; every segment carries exactly one. */
    MAIN_LINE,
    /** Optional side track such as a city spur; never a substitute for the main exit. */
    BRANCH
}
