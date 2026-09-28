package com.rakshika.app.risk

import android.util.Log
import com.rakshika.app.geo.formatDistance
import com.rakshika.app.geo.geoPointAt
import com.rakshika.app.geo.pathLengthMeters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.net.URLEncoder
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * A stretch of one route that OpenStreetMap data flags as riskier than the rest: [center] is its
 * midpoint on the route, [atMeters] how far along the route that midpoint is, [lengthMeters] how
 * long the flagged stretch runs.
 */
data class RiskPatch(
    val kind: Kind,
    val center: DoubleArray,
    val radiusMeters: Double,
    val atMeters: Double,
    val lengthMeters: Double
) {
    enum class Kind(val label: String) { DARK("Dark lane"), ISOLATED("Isolated") }

    val detail: String
        get() = when (kind) {
            Kind.DARK -> "OpenStreetMap marks ${formatDistance(lengthMeters)} of road here as unlit."
            Kind.ISOLATED -> "No shops, cafés or help points mapped within $ISOLATION_RADIUS_M m for ${formatDistance(lengthMeters)}."
        }

    companion object {
        const val ISOLATION_RADIUS_M = 150
    }
}

/**
 * Finds [RiskPatch]es along a route from one Overpass query: roads tagged `lit=no` the route runs
 * on (dark), and stretches with no shop / café / police / hospital node nearby (isolated). Only what
 * OSM actually records is flagged — untagged lighting is unknown, not dark — and "isolated" is
 * skipped entirely when OSM has no places mapped anywhere along the route (coverage, not isolation).
 */
object RiskPatches {
    private const val TAG = "RiskPatches"
    private const val POI_AMENITIES = "restaurant|cafe|fast_food|pharmacy|bank|atm|fuel|bar|pub|police|hospital|clinic|fire_station"
    private const val DARK_WAY_M = 25.0
    private const val MIN_DARK_RUN_M = 80.0
    private const val MIN_ISOLATED_RUN_M = 250.0
    private const val MAX_PATCHES = 8
    /** Public Overpass instances, tried in turn — the main one often answers 429/504 under load. */
    private val ENDPOINTS = listOf(
        ContextSources.OVERPASS_URL,
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    /** Map data fetched for one area: unlit road geometries and place nodes, and when it was fetched. */
    private class AreaData(
        val bbox: DoubleArray,
        val darkWays: List<List<DoubleArray>>,
        val pois: List<DoubleArray>,
        val fetchedAt: Long
    ) {
        fun contains(south: Double, west: Double, north: Double, east: Double) =
            bbox[0] <= south && bbox[1] <= west && bbox[2] >= north && bbox[3] >= east
    }

    /** Recent areas already fetched — kept in memory and on disk, so a reroute, a second look or a later
     *  trip nearby doesn't depend on the public servers being up at that moment. */
    private val cache = ArrayDeque<AreaData>()
    private const val CACHE_SIZE = 12
    private const val CACHE_TTL_MS = 14L * 24 * 60 * 60 * 1000
    private var cacheFile: File? = null
    private var diskLoaded = false
    /** Areas are fetched snapped outward to this grid (~1 km), so nearby trips share a cached area. */
    private const val GRID_DEG = 0.01
    /** Half-width of the area prefetched around the rider before she even picks a destination (~1.5 km). */
    private const val PREFETCH_HALF_DEG = 0.015
    private const val REQUEST_TIMEOUT_MS = 30_000

    /** Detached from any caller: a slow server keeps its thread until it times out, without holding anyone up. */
    private val raceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Keeps fetched areas in [dir] across app restarts. */
    fun useDiskCache(dir: File) {
        cacheFile = File(dir, "risk_areas.json")
    }

    /**
     * Fetches the map data around [lat]/[lng] ahead of time, so the patches for whatever destination she
     * picks next are usually ready instantly — the public servers can take many seconds, or fail, at the
     * moment she actually needs them.
     */
    suspend fun prefetchAround(lat: Double, lng: Double) {
        fetchArea(lat - PREFETCH_HALF_DEG, lng - PREFETCH_HALF_DEG, lat + PREFETCH_HALF_DEG, lng + PREFETCH_HALF_DEG)
    }

    /**
     * Patches for every route in [routes] (keyed by corridor). The map data comes from one bounding-box
     * query covering all of them — far cheaper for Overpass than searching along each route line — and
     * the distance checks then run on the phone. A route's value is null when OSM couldn't be reached:
     * "unknown", as opposed to an empty list ("none found").
     */
    suspend fun forRoutes(routes: Map<String, List<DoubleArray>>): Map<String, List<RiskPatch>?> = withContext(Dispatchers.IO) {
        val usable = routes.filterValues { it.size >= 2 }
        if (usable.isEmpty()) return@withContext routes.mapValues { null }

        val points = usable.values.flatten()
        // Pad by the isolation radius so places just outside the route's extent still count.
        val padLat = RiskPatch.ISOLATION_RADIUS_M / 110_540.0
        val padLng = RiskPatch.ISOLATION_RADIUS_M / (111_320.0 * cos(Math.toRadians(points[0][0])))
        val area = fetchArea(
            points.minOf { it[0] } - padLat, points.minOf { it[1] } - padLng,
            points.maxOf { it[0] } + padLat, points.maxOf { it[1] } + padLng
        ) ?: return@withContext routes.mapValues { null }
        routes.mapValues { (_, route) -> if (route.size < 2) null else findPatches(route, area.darkWays, area.pois) }
    }

    private suspend fun fetchArea(s0: Double, w0: Double, n0: Double, e0: Double): AreaData? = withContext(Dispatchers.IO) {
        val south = floor(s0 / GRID_DEG) * GRID_DEG
        val west = floor(w0 / GRID_DEG) * GRID_DEG
        val north = ceil(n0 / GRID_DEG) * GRID_DEG
        val east = ceil(e0 / GRID_DEG) * GRID_DEG

        cached(south, west, north, east)?.let {
            Log.i(TAG, "Risk data for this area already on the phone — reusing it")
            return@withContext it
        }

        val box = "%.4f,%.4f,%.4f,%.4f".format(Locale.US, south, west, north, east)
        val query = "[out:json][timeout:25][bbox:$box];(" +
            "way[\"highway\"][\"lit\"=\"no\"];" +
            "node[\"amenity\"~\"^($POI_AMENITIES)$\"];" +
            "node[\"shop\"];" +
            ");out geom;"
        val body = firstAnswer("data=" + URLEncoder.encode(query, "UTF-8")) ?: return@withContext null

        runCatching {
            val darkWays = mutableListOf<List<DoubleArray>>()
            val pois = mutableListOf<DoubleArray>()
            val elements = JSONObject(body).getJSONArray("elements")
            for (i in 0 until elements.length()) {
                val e = elements.getJSONObject(i)
                when (e.getString("type")) {
                    "way" -> e.optJSONArray("geometry")?.let { g ->
                        darkWays += (0 until g.length()).map { j ->
                            val p = g.getJSONObject(j)
                            doubleArrayOf(p.getDouble("lat"), p.getDouble("lon"))
                        }
                    }
                    "node" -> pois += doubleArrayOf(e.getDouble("lat"), e.getDouble("lon"))
                }
            }
            AreaData(doubleArrayOf(south, west, north, east), darkWays, pois, System.currentTimeMillis()).also { remember(it) }
        }.onFailure { Log.w(TAG, "Overpass parse failed", it) }.getOrNull()
    }

    /** Sends [postBody] to every endpoint at once and returns the first real answer, or null if all fail. */
    private suspend fun firstAnswer(postBody: String): String? {
        val winner = CompletableDeferred<String?>()
        val failures = AtomicInteger(0)
        ENDPOINTS.forEach { url ->
            raceScope.launch {
                val body = ContextSources.http(url, method = "POST", readTimeoutMs = REQUEST_TIMEOUT_MS, postBody = postBody)
                if (body != null) {
                    if (winner.complete(body)) Log.i(TAG, "Overpass answered first from ${url.substringAfter("//").substringBefore('/')}")
                } else if (failures.incrementAndGet() == ENDPOINTS.size) {
                    winner.complete(null)
                }
            }
        }
        return winner.await()
    }

    private fun cached(south: Double, west: Double, north: Double, east: Double): AreaData? = synchronized(cache) {
        loadDiskOnce()
        val now = System.currentTimeMillis()
        cache.removeAll { now - it.fetchedAt > CACHE_TTL_MS }
        cache.firstOrNull { it.contains(south, west, north, east) }
    }

    private fun remember(area: AreaData) = synchronized(cache) {
        cache.addFirst(area)
        while (cache.size > CACHE_SIZE) cache.removeLast()
        saveDisk()
    }

    private fun loadDiskOnce() {
        if (diskLoaded) return
        diskLoaded = true
        val file = cacheFile?.takeIf { it.exists() } ?: return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val b = o.getJSONArray("bbox")
                cache.addLast(
                    AreaData(
                        DoubleArray(4) { b.getDouble(it) },
                        o.getJSONArray("dark").let { ways ->
                            (0 until ways.length()).map { w -> points(ways.getJSONArray(w)) }
                        },
                        points(o.getJSONArray("pois")),
                        o.getLong("at")
                    )
                )
            }
        }.onFailure { Log.w(TAG, "Risk area cache unreadable — starting fresh", it) }
    }

    private fun saveDisk() {
        val file = cacheFile ?: return
        runCatching {
            val arr = JSONArray()
            cache.forEach { a ->
                arr.put(
                    JSONObject()
                        .put("bbox", JSONArray(a.bbox.toList()))
                        .put("at", a.fetchedAt)
                        .put("dark", JSONArray().also { ways -> a.darkWays.forEach { ways.put(pointsJson(it)) } })
                        .put("pois", pointsJson(a.pois))
                )
            }
            file.writeText(arr.toString())
        }.onFailure { Log.w(TAG, "Couldn't save risk area cache", it) }
    }

    private fun points(arr: JSONArray): List<DoubleArray> =
        (0 until arr.length()).map { i -> arr.getJSONArray(i).let { doubleArrayOf(it.getDouble(0), it.getDouble(1)) } }

    private fun pointsJson(points: List<DoubleArray>): JSONArray =
        JSONArray().also { arr -> points.forEach { arr.put(JSONArray().put(it[0]).put(it[1])) } }

    private fun findPatches(route: List<DoubleArray>, darkWays: List<List<DoubleArray>>, pois: List<DoubleArray>): List<RiskPatch> {
        val total = pathLengthMeters(route)
        if (total <= 0) return emptyList()
        val step = maxOf(40.0, total / 400)
        val samples = generateSequence(0.0) { it + step }.takeWhile { it <= total }.toList()
        val points = samples.map { geoPointAt(route, (it / total).toFloat()) }
        val proj = LocalProjection(route.first())

        val darkSegs = darkWays.flatMap { way -> way.zipWithNext { a, b -> proj.xy(a) to proj.xy(b) } }
        val poiXy = pois.map { proj.xy(it) }

        val dark = points.map { p ->
            val xy = proj.xy(p)
            darkSegs.any { (a, b) -> segmentDistance(xy, a, b) <= DARK_WAY_M }
        }
        val nearPoi = points.map { p ->
            val xy = proj.xy(p)
            poiXy.any { hypot(it[0] - xy[0], it[1] - xy[1]) <= RiskPatch.ISOLATION_RADIUS_M }
        }
        // No place mapped anywhere along this route means OSM doesn't cover it — not that it's isolated.
        val isolated = if (nearPoi.none { it }) points.map { false } else nearPoi.map { !it }

        val patches = runs(dark, samples, route, total, RiskPatch.Kind.DARK, MIN_DARK_RUN_M) +
            runs(isolated, samples, route, total, RiskPatch.Kind.ISOLATED, MIN_ISOLATED_RUN_M)
        return patches.sortedBy { it.atMeters }.take(MAX_PATCHES)
    }

    /** Each unbroken run of flagged samples at least [minLength] long becomes one patch. */
    private fun runs(
        flags: List<Boolean>,
        samples: List<Double>,
        route: List<DoubleArray>,
        total: Double,
        kind: RiskPatch.Kind,
        minLength: Double
    ): List<RiskPatch> {
        val out = mutableListOf<RiskPatch>()
        var start = -1
        for (i in 0..flags.size) {
            val on = i < flags.size && flags[i]
            if (on && start < 0) start = i
            if (!on && start >= 0) {
                val from = samples[start]
                val to = samples[i - 1]
                val length = to - from
                if (length >= minLength) {
                    val mid = (from + to) / 2
                    out += RiskPatch(
                        kind = kind,
                        center = geoPointAt(route, (mid / total).toFloat()),
                        radiusMeters = (length / 2 + 30).coerceIn(50.0, 250.0),
                        atMeters = mid,
                        lengthMeters = length
                    )
                }
                start = -1
            }
        }
        return out
    }

    /** Flat x/y metres around [origin] — accurate enough over a walking route. */
    private class LocalProjection(origin: DoubleArray) {
        private val lat0 = origin[0]
        private val lng0 = origin[1]
        private val kx = 111_320.0 * cos(Math.toRadians(lat0))
        fun xy(p: DoubleArray) = doubleArrayOf((p[1] - lng0) * kx, (p[0] - lat0) * 110_540.0)
    }

    private fun segmentDistance(p: DoubleArray, a: DoubleArray, b: DoubleArray): Double {
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy))
    }
}
