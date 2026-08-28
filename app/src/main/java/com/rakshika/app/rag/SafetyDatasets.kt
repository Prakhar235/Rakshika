package com.rakshika.app.rag

/**
 * A named corpus of dummy safety knowledge. In the demo the user picks one of these; its
 * [docs] are embedded and inserted into the on-device vector store, and every safest-route
 * prediction is retrieved from that corpus.
 */
data class SafetyDataset(
    val id: String,
    val name: String,
    val blurb: String,
    val docs: List<SafetyDoc>
)

/**
 * Three hand-written scenario datasets. They are shaped so the retrieved evidence actually
 * drives the decision: [WEEKNIGHT] and [MONSOON] favour the main road, while [FESTIVAL]
 * flips the recommendation to the back lane.
 */
object SafetyDatasets {

    val WEEKNIGHT = SafetyDataset(
        id = "weeknight",
        name = "Weeknight · Central District",
        blurb = "A typical Tuesday night. Quiet back lanes, a lit and moderately busy main road.",
        docs = listOf(
            SafetyDoc(
                "wk-1",
                "The back lane behind Sector 5 has no working street lights after 8 pm; the last two poles are broken.",
                RouteCorridor.BACK_LANE, SafetyKind.LIGHTING, sentiment = -0.9f, severity = 0.85f, ageDays = 6
            ),
            SafetyDoc(
                "wk-2",
                "Two phone-snatching incidents reported on the Sector 5 back lane in the last three weeks, both around 9 pm.",
                RouteCorridor.BACK_LANE, SafetyKind.INCIDENT, sentiment = -1.0f, severity = 0.95f, ageDays = 12
            ),
            SafetyDoc(
                "wk-3",
                "Back lane is deserted on weeknights — almost no pedestrians once the tuition centre closes.",
                RouteCorridor.BACK_LANE, SafetyKind.FOOT_TRAFFIC, sentiment = -0.6f, severity = 0.6f, ageDays = 4
            ),
            SafetyDoc(
                "wk-4",
                "MG Road main stretch is fully lit with new LED street lights installed by the municipality this year.",
                RouteCorridor.MAIN_ROAD, SafetyKind.LIGHTING, sentiment = 0.85f, severity = 0.8f, ageDays = 20
            ),
            SafetyDoc(
                "wk-5",
                "Steady foot traffic on the main road until 11 pm — shops, a pharmacy and a tea stall stay open.",
                RouteCorridor.MAIN_ROAD, SafetyKind.FOOT_TRAFFIC, sentiment = 0.7f, severity = 0.7f, ageDays = 8
            ),
            SafetyDoc(
                "wk-6",
                "A police beat constable is usually stationed near the main-road bus stop between 8 pm and midnight.",
                RouteCorridor.MAIN_ROAD, SafetyKind.PATROL, sentiment = 0.75f, severity = 0.65f, ageDays = 15
            ),
            SafetyDoc(
                "wk-7",
                "Autos and app cabs are easy to flag down on the main road; the back lane has no pickup point.",
                RouteCorridor.MAIN_ROAD, SafetyKind.TRANSIT, sentiment = 0.5f, severity = 0.45f, ageDays = 10
            ),
            SafetyDoc(
                "wk-8",
                "General advice for the district at night: stay on lit arterial roads with people around rather than shortcuts.",
                RouteCorridor.BOTH, SafetyKind.PATROL, sentiment = -0.15f, severity = 0.3f, ageDays = 30
            )
        )
    )

    val MONSOON = SafetyDataset(
        id = "monsoon",
        name = "Monsoon Evening",
        blurb = "Heavy rain earlier. Waterlogging and stalled traffic on low-lying stretches.",
        docs = listOf(
            SafetyDoc(
                "mn-1",
                "The main-road underpass near the metro collects ankle-deep water in heavy rain and drains within an hour or so.",
                RouteCorridor.MAIN_ROAD, SafetyKind.HAZARD, sentiment = -0.7f, severity = 0.8f, ageDays = 1
            ),
            SafetyDoc(
                "mn-2",
                "Traffic on the main road slows near the underpass when it rains, but a raised footpath stays walkable alongside it.",
                RouteCorridor.MAIN_ROAD, SafetyKind.HAZARD, sentiment = -0.45f, severity = 0.5f, ageDays = 1
            ),
            SafetyDoc(
                "mn-3",
                "Autos are refusing short trips in the rain and surge pricing is high across the district tonight.",
                RouteCorridor.BOTH, SafetyKind.TRANSIT, sentiment = -0.4f, severity = 0.45f, ageDays = 1
            ),
            SafetyDoc(
                "mn-4",
                "Main road keeps continuous street lighting and a line of covered shopfronts to walk under and shelter in.",
                RouteCorridor.MAIN_ROAD, SafetyKind.LIGHTING, sentiment = 0.8f, severity = 0.85f, ageDays = 20
            ),
            SafetyDoc(
                "mn-5",
                "The back lane sits higher and does not flood, but it stays completely unlit and slippery when wet.",
                RouteCorridor.BACK_LANE, SafetyKind.LIGHTING, sentiment = -0.75f, severity = 0.75f, ageDays = 6
            ),
            SafetyDoc(
                "mn-6",
                "Back lane has open uncovered drains along one side that are a real hazard once it is dark and raining.",
                RouteCorridor.BACK_LANE, SafetyKind.HAZARD, sentiment = -0.8f, severity = 0.85f, ageDays = 9
            ),
            SafetyDoc(
                "mn-7",
                "Shops and the tea stall on the main road stay open in the rain, so there is steady pedestrian foot traffic.",
                RouteCorridor.MAIN_ROAD, SafetyKind.FOOT_TRAFFIC, sentiment = 0.7f, severity = 0.7f, ageDays = 8
            ),
            SafetyDoc(
                "mn-8",
                "Municipal pump crew plus a traffic police unit are stationed at the main-road underpass all evening.",
                RouteCorridor.MAIN_ROAD, SafetyKind.PATROL, sentiment = 0.55f, severity = 0.55f, ageDays = 1
            ),
            SafetyDoc(
                "mn-9",
                "There is no shelter anywhere on the back lane, so you would be walking exposed with poor visibility the whole way.",
                RouteCorridor.BACK_LANE, SafetyKind.HAZARD, sentiment = -0.55f, severity = 0.6f, ageDays = 6
            )
        )
    )

    val FESTIVAL = SafetyDataset(
        id = "festival",
        name = "Festival Night · Old City",
        blurb = "A procession is on the main road. Dense crowds and diversions; back lanes lit for the festival.",
        docs = listOf(
            SafetyDoc(
                "fs-1",
                "A religious procession is moving down the main road tonight with a tightly packed crowd and frequent crushes.",
                RouteCorridor.MAIN_ROAD, SafetyKind.HAZARD, sentiment = -0.85f, severity = 0.9f, ageDays = 0
            ),
            SafetyDoc(
                "fs-2",
                "Police have set up barricades and diversions on the main road; pedestrians are being pushed onto side streets.",
                RouteCorridor.MAIN_ROAD, SafetyKind.HAZARD, sentiment = -0.6f, severity = 0.65f, ageDays = 0
            ),
            SafetyDoc(
                "fs-3",
                "Several pickpocketing and groping complaints filed from inside the main-road procession crowd last festival night.",
                RouteCorridor.MAIN_ROAD, SafetyKind.INCIDENT, sentiment = -0.95f, severity = 0.9f, ageDays = 365
            ),
            SafetyDoc(
                "fs-4",
                "Heavy police deployment along the main-road procession route, but officers are focused on crowd control, not individuals.",
                RouteCorridor.MAIN_ROAD, SafetyKind.PATROL, sentiment = 0.25f, severity = 0.4f, ageDays = 0
            ),
            SafetyDoc(
                "fs-5",
                "The back lane has been strung with festival string lights and lanterns and is well lit all the way through tonight.",
                RouteCorridor.BACK_LANE, SafetyKind.LIGHTING, sentiment = 0.85f, severity = 0.8f, ageDays = 0
            ),
            SafetyDoc(
                "fs-6",
                "Food and bangle vendors have set up along the back lane for the festival, so there is a steady, calm stream of families walking through.",
                RouteCorridor.BACK_LANE, SafetyKind.FOOT_TRAFFIC, sentiment = 0.8f, severity = 0.8f, ageDays = 0
            ),
            SafetyDoc(
                "fs-7",
                "A community volunteer group is stationed at both ends of the back lane checking on people during the festival.",
                RouteCorridor.BACK_LANE, SafetyKind.PATROL, sentiment = 0.7f, severity = 0.6f, ageDays = 0
            ),
            SafetyDoc(
                "fs-8",
                "Back lane is the recommended pedestrian route on the police festival advisory while the main road carries the procession.",
                RouteCorridor.BACK_LANE, SafetyKind.PATROL, sentiment = 0.6f, severity = 0.55f, ageDays = 0
            ),
            SafetyDoc(
                "fs-9",
                "Old-city general note: on non-festival nights the back lane is dim and quiet and the main road is the safer choice.",
                RouteCorridor.BACK_LANE, SafetyKind.LIGHTING, sentiment = -0.3f, severity = 0.25f, ageDays = 40
            )
        )
    )

    val ALL = listOf(WEEKNIGHT, MONSOON, FESTIVAL)

    fun byId(id: String): SafetyDataset = ALL.firstOrNull { it.id == id } ?: WEEKNIGHT
}
