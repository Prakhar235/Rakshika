package com.rakshika.app.demo

import com.rakshika.app.data.model.ContactStatus

enum class DemoScene { SOS, ROUTE }

enum class DemoView { HOME, MAP }

data class DemoState(
    val scene: DemoScene = DemoScene.SOS,
    val beatIndex: Int = 0,
    val view: DemoView = DemoView.HOME,
    val isPlaying: Boolean = false,
    val isMuted: Boolean = false,
    val caption: String = "",
    val teleStatus: String = "Idle",
    val teleDetail: String = "—",
    val teleContacts: String = "—",

    // SOS Tracking scene
    val sosHolding: Boolean = false,
    val sosTriggered: Boolean = false,
    val sharingLive: Boolean = false,
    val contactAmma: ContactStatus? = null,
    val contactRohan: ContactStatus? = null,
    val markedSafe: Boolean = false,

    // Safe Route scene
    val searching: Boolean = false,
    val routesFound: Boolean = false,
    val routeRecommended: Boolean = false,
    val walking: Boolean = false
)

data class DemoBeat(
    val view: DemoView,
    val caption: String,
    val teleStatus: String,
    val teleDetail: String,
    val teleContacts: String,
    val animateTrack: Boolean = false,
    val animateRoute: Boolean = false,
    val apply: (DemoState) -> DemoState
)

object DemoScript {
    val sosBeats = listOf(
        DemoBeat(
            view = DemoView.HOME,
            caption = "Priya holds the SOS button for two seconds to trigger an alert.",
            teleStatus = "Arming…",
            teleDetail = "Hold 2.0s to confirm",
            teleContacts = "0 / 2 notified",
            apply = {
                it.copy(
                    sosHolding = true,
                    sosTriggered = false,
                    sharingLive = false,
                    markedSafe = false,
                    contactAmma = null,
                    contactRohan = null
                )
            }
        ),
        DemoBeat(
            view = DemoView.MAP,
            caption = "Both emergency contacts are alerted instantly — push if she's online, SMS if she isn't.",
            teleStatus = "SOS active",
            teleDetail = "Alert sent · 08:14 PM",
            teleContacts = "2 / 2 notified",
            apply = {
                it.copy(
                    sosHolding = false,
                    sosTriggered = true,
                    contactAmma = ContactStatus.NOTIFIED,
                    contactRohan = ContactStatus.NOTIFIED,
                    sharingLive = false
                )
            }
        ),
        DemoBeat(
            view = DemoView.MAP,
            caption = "They watch her live location update in real time as she walks — no need to call and ask if she's okay.",
            teleStatus = "Sharing live",
            teleDetail = "Accuracy 8m · GPS",
            teleContacts = "Amma seen · Rohan seen",
            animateTrack = true,
            apply = {
                it.copy(
                    sharingLive = true,
                    contactAmma = ContactStatus.SEEN,
                    contactRohan = ContactStatus.SEEN
                )
            }
        ),
        DemoBeat(
            view = DemoView.MAP,
            caption = "The moment she checks in safe, the alert closes — and every contact is told first.",
            teleStatus = "Marked safe",
            teleDetail = "Resolved in 4m 12s",
            teleContacts = "Rohan responding",
            apply = {
                it.copy(
                    contactRohan = ContactStatus.RESPONDING,
                    markedSafe = true
                )
            }
        )
    )

    val routeBeats = listOf(
        DemoBeat(
            view = DemoView.MAP,
            caption = "Before Priya starts walking, Rakshika compares routes to her destination — not just for speed.",
            teleStatus = "Finding route",
            teleDetail = "Hostel → MG Road Metro",
            teleContacts = "—",
            apply = {
                it.copy(
                    searching = true,
                    routesFound = false,
                    routeRecommended = false,
                    walking = false
                )
            }
        ),
        DemoBeat(
            view = DemoView.MAP,
            caption = "The back lane is four minutes faster — but it's unlit, and had two reported incidents this month.",
            teleStatus = "2 routes found",
            teleDetail = "Route A · 9 min · unlit",
            teleContacts = "—",
            apply = { it.copy(searching = false, routesFound = true) }
        ),
        DemoBeat(
            view = DemoView.MAP,
            caption = "So Rakshika recommends the main road instead — busier, better lit, and barely slower.",
            teleStatus = "Route B recommended",
            teleDetail = "Well-lit · high foot traffic",
            teleContacts = "—",
            apply = { it.copy(routeRecommended = true) }
        ),
        DemoBeat(
            view = DemoView.MAP,
            caption = "Once she starts walking, her contacts can follow this exact route with her, live.",
            teleStatus = "Walking · Route B",
            teleDetail = "ETA 13 min",
            teleContacts = "Sharing with Amma",
            animateRoute = true,
            apply = { it.copy(walking = true) }
        )
    )

    fun beatsFor(scene: DemoScene): List<DemoBeat> = if (scene == DemoScene.SOS) sosBeats else routeBeats
}
