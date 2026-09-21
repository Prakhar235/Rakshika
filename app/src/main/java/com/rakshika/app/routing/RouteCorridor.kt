package com.rakshika.app.routing

/** The two candidate walking corridors the app compares between an origin and a destination. */
enum class RouteCorridor { MAIN_ROAD, BACK_LANE }

/** The id the risk model and its stored assessments use for this corridor. */
val RouteCorridor.key: String
    get() = when (this) {
        RouteCorridor.MAIN_ROAD -> "main"
        RouteCorridor.BACK_LANE -> "back"
    }
