package veeci.practicing.rts.routing.domain

/** Which directions a way may be driven, relative to the order of its nodes. */
enum class Oneway {
    /** Both directions: each pair of neighbouring nodes gives two edges. */
    NO,

    /** Only in node order. */
    FORWARD,

    /** Only against node order (OSM `oneway=-1`). */
    REVERSE,
}
