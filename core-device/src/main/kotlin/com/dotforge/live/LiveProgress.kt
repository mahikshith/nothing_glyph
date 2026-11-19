package com.dotforge.live

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.ceil
import kotlin.math.max

/** A delivery (food, groceries, parcels) or a ride; their words and stages differ. */
enum class TripKind { DELIVERY, RIDE }

/**
 * How far an order or a ride has got, from its notification's words. [fraction] is where the ring stands at
 * that stage; [icon] is the library glyph shown in the middle when no minutes are known.
 */
enum class Stage(val label: String, val fraction: Float, val icon: String) {
    PLACED("Order placed", 0.10f, "g-receipt"),
    PREPARING("Being prepared", 0.30f, "g-fork-knife"),
    PACKED("Packed", 0.45f, "g-parcel"),
    ON_THE_WAY("On the way", 0.70f, "g-scooter"),
    ASSIGNED("Driver assigned", 0.15f, "g-steering"),
    PICKUP("Driver on the way", 0.30f, "ride-pickup"),
    AT_PICKUP("Driver is here", 0.45f, "g-pin"),
    ON_TRIP("On the trip", 0.60f, "ride-trip"),
    ARRIVING("Arriving", 0.92f, "g-home"),
    DONE("Done", 1f, "g-parcel-done"),
    CANCELLED("Cancelled", 0f, "g-receipt"),
}

/** The apps Live progress knows by name, matched by package or by the app's label. */
data class ProgressApp(
    val id: String,
    val name: String,
    val what: String,
    val packages: List<String>,
    /** Whole words in the app's label that mean this app. */
    val labels: List<String>,
    val icon: String,
    val kind: TripKind,
)

object ProgressApps {
    val ALL = listOf(
        ProgressApp("zomato", "Zomato", "Food delivery", listOf("com.application.zomato"), listOf("zomato"), "g-food-bag", TripKind.DELIVERY),
        ProgressApp("swiggy", "Swiggy / Instamart", "Food and groceries", listOf("in.swiggy.android"), listOf("swiggy", "instamart"), "g-scooter", TripKind.DELIVERY),
        ProgressApp("blinkit", "Blinkit", "Groceries in minutes", listOf("com.grofers.customerapp"), listOf("blinkit"), "g-cart", TripKind.DELIVERY),
        ProgressApp("zepto", "Zepto", "Groceries in minutes", listOf("com.zeptoconsumerapp"), listOf("zepto"), "g-grocery-basket", TripKind.DELIVERY),
        ProgressApp("bigbasket", "BigBasket", "Groceries", listOf("com.bigbasket.mobileapp"), listOf("bigbasket", "bb now"), "g-apple", TripKind.DELIVERY),
        ProgressApp("amazon", "Amazon", "Parcels", listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping"), listOf("amazon", "amazon shopping"), "g-delivery-van", TripKind.DELIVERY),
        ProgressApp("flipkart", "Flipkart", "Parcels", listOf("com.flipkart.android"), listOf("flipkart", "flipkart minutes"), "g-gift", TripKind.DELIVERY),
        ProgressApp("uber", "Uber", "Rides", listOf("com.ubercab", "com.ubercab.uberlite"), listOf("uber"), "g-car", TripKind.RIDE),
        ProgressApp("ola", "Ola", "Rides", listOf("com.olacabs.customer"), listOf("ola"), "g-taxi", TripKind.RIDE),
        ProgressApp("rapido", "Rapido", "Bike taxis and autos", listOf("com.rapido.passenger"), listOf("rapido"), "g-bike-taxi", TripKind.RIDE),
    )

    fun byId(id: String): ProgressApp? = ALL.firstOrNull { it.id == id }

    /**
     * The known app for a package or label: the package first; then the label's whole words ("Swiggy",
     * "Instamart", "Uber"), so a renamed or regional build still counts. Uber Eats is not Uber's rides.
     */
    fun match(pkg: String, label: String): ProgressApp? {
        ALL.firstOrNull { pkg in it.packages }?.let { return it }
        val words = label.lowercase().replace(Regex("[^a-z0-9 ]"), " ").trim().replace(Regex("\\s+"), " ")
        if (words.isEmpty() || "uber eats" in words) return null
        return ALL.firstOrNull { app ->
            app.labels.any { l -> words == l || words.startsWith("$l ") || words.endsWith(" $l") || " $l " in " $words " }
        }
    }
}

/** Order and ride stages from the words of a notification. */
object Stages {
    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val CANCELLED = r("""\b(cancel+ed|order failed|refund (has been )?initiated)\b""")
    private val DONE = listOf(
        r("""(?<!be |to be |been not )\bdelivered\b(?!\s+(by|before|between)\b)"""),
        r("""\b(trip|ride|order|delivery)\s+(is\s+|has\s+been\s+|was\s+)?(completed|complete|ended|finished)\b"""),
        r("""\benjoy your (meal|food|order|groceries)\b"""),
        r("""\bthanks? (you )?for (riding|travel+ing|choosing)\b"""),
        r("""\b(you('ve| have)\s+)?(reached|arrived at) your destination\b"""),
        r("""\bhanded over\b"""),
    )
    private val ARRIVING = listOf(
        r("""\b(arriving|arrives|reaching|delivering)\s+(now|shortly|soon|any (minute|moment))\b"""),
        r("""\balmost (there|here)\b"""),
        r("""\b(is|are)\s+(nearby|near you|close by|around the corner)\b"""),
        r("""\bnearby\b"""),
        r("""\b(is|are)\s+arriving\b(?!\s+(in|at|by|on|between|around)\b)"""),
        r("""\b(at|reached|outside) (your )?(door(step)?|location|gate|building|address|home)\b"""),
    )
    private val ARRIVED = r("""\b(has|have|had) arrived\b|\bis here\b|\bwaiting (for you )?(at|outside)\b""")
    private val ON_TRIP = r("""\b(trip|ride) (has )?(started|begun|in progress)\b|\bon (a|your|the) (trip|ride)\b|\benjoy your (ride|trip)\b|\b(arriving|reaching) (at )?(your )?destination\b""")
    private val ON_THE_WAY = r("""\bout for delivery\b|\bon (the|its|his|her|their) way\b|\ben ?route\b|\bpicked up\b|\bheading (to|towards|your way)\b|\bon the move\b""")
    private val ASSIGNED = r("""\b(driver|captain|rider|partner|executive|valet)\b[^.]{0,30}\b(assigned|allotted|found|accepted|is coming)\b|\b(ride|booking|trip) (is |has been )?(confirmed|booked)\b|\bfound (you )?a (driver|captain|ride)\b""")
    private val PACKED = r("""(?<!being )\bpacked\b|\bshipped\b|\bdispatched\b|\bready for (pick ?up|dispatch)\b|\b(order|food) is ready\b""")
    private val PREPARING = r("""\b(preparing|being prepared|cooking|in the kitchen|being packed|packing (your|the)|processing your|getting (your order )?ready)\b""")
    private val PLACED = r("""\border (placed|confirmed|received|accepted)\b|\b(placed|confirmed|received|accepted) your order\b""")

    private val AUTO = r("""\b(auto|autos|auto ?rickshaw|rickshaw|tuk ?tuk)\b""")
    private val BIKE = r("""\b(bike|bike taxi|moto|motorbike|two.wheeler)\b""")
    private val CAB = r("""\b(cab|car|sedan|suv|hatchback|prime|mini|uberx|uber go|premier)\b""")

    /** The vehicle a ride's words name: an auto, a bike or a cab (its icon id), or null. */
    fun vehicle(text: String): String? = when {
        AUTO.containsMatchIn(text) -> "g-auto"
        BIKE.containsMatchIn(text) -> "g-bike-taxi"
        CAB.containsMatchIn(text) -> "g-car"
        else -> null
    }

    /** The furthest stage the words say, for a delivery or a ride; null when they say none. */
    fun of(text: String, kind: TripKind): Stage? {
        if (text.isBlank()) return null
        if (CANCELLED.containsMatchIn(text)) return Stage.CANCELLED
        if (DONE.any { it.containsMatchIn(text) }) return Stage.DONE
        if (kind == TripKind.RIDE) {
            if (ON_TRIP.containsMatchIn(text)) return Stage.ON_TRIP
            if (ARRIVED.containsMatchIn(text)) return Stage.AT_PICKUP
            if (ARRIVING.any { it.containsMatchIn(text) }) return Stage.ARRIVING
            if (ON_THE_WAY.containsMatchIn(text)) return Stage.PICKUP
            if (ASSIGNED.containsMatchIn(text)) return Stage.ASSIGNED
            return null
        }
        if (ARRIVING.any { it.containsMatchIn(text) } || ARRIVED.containsMatchIn(text)) return Stage.ARRIVING
        if (ON_THE_WAY.containsMatchIn(text)) return Stage.ON_THE_WAY
        if (PACKED.containsMatchIn(text)) return Stage.PACKED
        if (PREPARING.containsMatchIn(text) || ASSIGNED.containsMatchIn(text)) return Stage.PREPARING
        if (PLACED.containsMatchIn(text)) return Stage.PLACED
        return null
    }
}

/**
 * Minutes until something arrives, from words like "arriving in 12 mins", "12 min away", "in 5 minutes",
 * "Arriving in 1 hr 5 min", "10-15 mins" (the later end), "ETA 7:45 PM", "by 7:45" and "between 7:30 - 7:45 PM".
 * A number needs a word that makes it a time to come ("in", "within", "away", "ETA", "by"…) unless [loose] (a
 * known delivery or ride app, where a bare "9 mins" means the same). Times past, waits, delays and codes
 * ("5 mins ago", "free waiting 3 min", "OTP expires in 10 minutes") never count.
 */
object Eta {
    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val UNIT_MIN = """(?:min|mins|minute|minutes|mnts|mts)\b"""
    private const val UNIT_HR = """(?:h|hr|hrs|hour|hours)\b"""
    private val DURATION = r(
        """(?:(\d{1,2})\s*$UNIT_HR(?:\s*(?:and\s*)?(\d{1,2})\s*$UNIT_MIN)?)|(?:(?:(\d{1,3})\s*(?:-|–|—|to)\s*)?(\d{1,3})\s*$UNIT_MIN)""",
    )
    private val CUE_BEFORE = r("""(?:\b(?:in|within|under|eta|approximately|about|around|of|estimated)\b|\b(?:approx|est)\b\.?|~)\s*:?\s*$""")
    private val CUE_AFTER = r("""^\s*(?:away|left|to go|remaining|more|to arrive|to reach|to (?:your )?(?:door|destination|pickup|location))\b""")
    private val NEVER_BEFORE = r("""\b(ago|wait\w*|delay\w*|late|valid|expir\w*|otp|code|cool ?down|retry|try again|refund|free|ordered|placed|booked|read|cancel\w*|after)\b[^.]{0,20}$""")
    private val NEVER_AFTER = r("""^\s*(?:ago|late|delay|read|walk|of waiting|wait)\b""")

    private val CLOCK = r("""(?<![\d:.])(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?\s?m\.?|p\.?\s?m\.?)?(?![\d:])""")
    private val CLOCK_CUE = r("""(?:\b(?:by|at|eta|before|around|expected|arriving|arrives|reach|reaches|reaching|delivery|till|until|between)\b|\best\b\.?|-|–|—|\bto\b)\s*:?\s*$""")

    fun minutes(text: String, now: Long, zone: ZoneId, loose: Boolean = false): Int? =
        relative(text, loose) ?: clockTime(text, now, zone)

    /** "in 12 mins" → 12; ranges give the later end; hours add up. */
    fun relative(text: String, loose: Boolean = false): Int? {
        for (m in DURATION.findAll(text)) {
            val before = text.substring(maxOf(0, m.range.first - 30), m.range.first)
            val after = text.substring(m.range.last + 1, minOf(text.length, m.range.last + 25))
            if (NEVER_BEFORE.containsMatchIn(before) || NEVER_AFTER.containsMatchIn(after)) continue
            if (!loose && !CUE_BEFORE.containsMatchIn(before) && !CUE_AFTER.containsMatchIn(after)) continue
            val g = m.groupValues
            val total = if (g[1].isNotEmpty()) g[1].toInt() * 60 + (g[2].ifEmpty { "0" }.toInt())
            else max(g[3].ifEmpty { "0" }.toInt(), g[4].toInt())
            if (total in 0..24 * 60) return total
        }
        return null
    }

    /** "ETA 7:45 PM", "by 7:45", "arriving at 19:45": minutes until the next such time (up to 10 min late counts as 0). */
    fun clockTime(text: String, now: Long, zone: ZoneId): Int? {
        val here = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        var best: Int? = null
        for (m in CLOCK.findAll(text)) {
            val g = m.groupValues
            val hasMinutes = g[2].isNotEmpty()
            val ampm = g[3].lowercase().replace(".", "").replace(" ", "")
            if (!hasMinutes && ampm.isEmpty()) continue
            val before = text.substring(maxOf(0, m.range.first - 25), m.range.first)
            if (NEVER_BEFORE.containsMatchIn(before)) continue
            if (!CLOCK_CUE.containsMatchIn(before)) continue
            val h = g[1].toInt()
            val min = g[2].ifEmpty { "0" }.toInt()
            if (min > 59 || h > 23) continue
            val candidates = when {
                ampm.startsWith("a") -> if (h in 1..12) listOf(h % 12) else continue
                ampm.startsWith("p") -> if (h in 1..12) listOf(h % 12 + 12) else continue
                h > 12 || h == 0 -> listOf(h)
                else -> listOf(h, (h + 12) % 24) // no am/pm: whichever comes next
            }
            val left = candidates.mapNotNull { hh -> minutesUntil(here, LocalTime.of(hh, min)) }.minOrNull() ?: continue
            if (best == null || left > best) best = left
        }
        return best
    }

    /** Minutes from [here] to the next [t] (today or tomorrow); a time up to 10 min ago is "now" (0). */
    private fun minutesUntil(here: ZonedDateTime, t: LocalTime): Int? {
        var at = here.with(t).withSecond(0).withNano(0)
        val diff = java.time.Duration.between(here, at).toMinutes()
        if (diff < -10) at = at.plusDays(1)
        val d = java.time.Duration.between(here, at).seconds
        if (d <= 0) return 0
        val mins = ceil(d / 60.0).toInt()
        return mins.takeIf { it <= 16 * 60 }
    }
}

/** The plain values of one notification that Live progress reads. */
data class ProgressSignals(
    /** A determinate progress bar (classic or Android 16 ProgressStyle), 0..1. */
    val bar: Float? = null,
    /** A progress bar with no value yet. */
    val barWaiting: Boolean = false,
    val chronometer: Boolean = false,
    val countDown: Boolean = false,
    val whenMs: Long = 0,
    /** Title, text, big text, sub text, the Live Update chip text. */
    val texts: List<String> = emptyList(),
    val kind: TripKind = TripKind.DELIVERY,
    /** A known delivery or ride app: a bare "9 mins" counts, and stage words are read. */
    val known: Boolean = false,
    /** Ongoing (a live tracking notification): its words are read even for an app we don't know. */
    val ongoing: Boolean = false,
)

enum class ProgressSource { BAR, COUNTDOWN, ETA, STAGE, WAITING }

data class ProgressReading(
    val source: ProgressSource,
    val bar: Float? = null,
    val minutesLeft: Int? = null,
    val stage: Stage? = null,
    /** A ride's vehicle when the words name one ("Your auto is arriving"): its icon id. */
    val vehicle: String? = null,
) {
    val done: Boolean get() = stage == Stage.DONE
    val cancelled: Boolean get() = stage == Stage.CANCELLED
    /** About to arrive: the ring pulses. */
    val arriving: Boolean get() = !done && !cancelled && ((minutesLeft != null && minutesLeft <= ARRIVING_MIN) || stage == Stage.ARRIVING || stage == Stage.AT_PICKUP)

    companion object {
        const val ARRIVING_MIN = 2
    }
}

object LiveProgress {
    /**
     * What [s] says, trying the sources in order: a progress bar, a countdown chronometer, the arrival time in
     * words, then the stage in words. Words are read only for known apps and ongoing notifications. Null when
     * the notification says nothing about progress.
     */
    fun read(s: ProgressSignals, now: Long, zone: ZoneId): ProgressReading? {
        val words = s.known || s.ongoing
        val text = if (words) s.texts.filter { it.isNotBlank() }.joinToString(" · ") else ""
        val stage = Stages.of(text, s.kind)
        if (stage == Stage.CANCELLED) return ProgressReading(ProgressSource.STAGE, stage = stage)
        val countdown = if (s.chronometer && s.countDown && s.whenMs > now) ceil((s.whenMs - now) / 60_000.0).toInt() else null
        val eta = if (words) Eta.minutes(text, now, zone, loose = s.known) else null
        val minutes = countdown ?: eta
        val source = when {
            s.bar != null -> ProgressSource.BAR
            countdown != null -> ProgressSource.COUNTDOWN
            eta != null -> ProgressSource.ETA
            stage != null -> ProgressSource.STAGE
            s.barWaiting -> ProgressSource.WAITING
            else -> return null
        }
        val vehicle = if (s.kind == TripKind.RIDE) Stages.vehicle(text) else null
        return ProgressReading(source, s.bar, if (stage == Stage.DONE) 0 else minutes, stage, vehicle)
    }

    /** A ride's two legs: the driver coming to you, then the trip. A delivery is one. */
    /**
     * A ring's span for one leg, and the shortest total it assumes ([usualMin]): an arrival time first seen at
     * "2 min" is nearly there, so its ring starts nearly full instead of empty.
     */
    enum class Leg(val from: Float, val to: Float, val usualMin: Int) { WHOLE(0f, 1f, 20), PICKUP(0f, 0.45f, 8), TRIP(0.5f, 1f, 15) }

    /** One order or ride followed over time: since when, the longest wait seen and where the ring stands. */
    data class Track(val since: Long, val leg: Leg, val totalMs: Long, val fraction: Float)

    /**
     * Where the ring stands after [r]. An arrival time alone has no total, so the total is the longest wait seen
     * since the leg began (time gone + time left), and at least the leg's usual length: the ring fills as the
     * minutes run down. A known stage never shows less than its own place (within the leg: a driver "arriving"
     * for the pickup stops short of the trip); a ring never goes back within a leg (except a real progress bar).
     */
    fun track(prev: Track?, r: ProgressReading, kind: TripKind, now: Long): Track {
        val leg = when {
            kind == TripKind.DELIVERY -> Leg.WHOLE
            r.stage == Stage.ON_TRIP || r.stage == Stage.DONE -> Leg.TRIP
            r.stage == Stage.ASSIGNED || r.stage == Stage.PICKUP || r.stage == Stage.AT_PICKUP -> Leg.PICKUP
            else -> prev?.leg ?: Leg.PICKUP
        }
        val same = prev != null && prev.leg == leg
        val since = if (same) prev!!.since else now
        if (r.done) return Track(since, leg, prev?.totalMs ?: 0, 1f)
        if (r.bar != null) return Track(since, leg, 0, r.bar.coerceIn(0f, 1f))
        var total = if (same) prev!!.totalMs else 0L
        var f = leg.from
        r.minutesLeft?.let { m ->
            val left = m * 60_000L
            val gone = (now - since).coerceAtLeast(0)
            total = max(max(total, gone + left), leg.usualMin * 60_000L)
            val within = 1f - left.toFloat() / total
            f = leg.from + (leg.to - leg.from) * within
        }
        r.stage?.let { f = max(f, it.fraction.coerceAtMost(leg.to)) }
        if (same) f = max(f, prev!!.fraction)
        return Track(since, leg, total, f.coerceIn(0f, 1f))
    }

    /**
     * The glyph in the middle when no minutes show: the stage's, or for an early stage the app's own icon; a
     * ride shows its stage (the car heading for the pin, then for the flag) or, arriving, the vehicle its words name ([vehicle]); done is a delivered parcel, or the finish flag for a ride.
     */
    fun icon(stage: Stage?, app: ProgressApp?, vehicle: String? = null): String? = when (stage) {
        null -> vehicle ?: app?.icon
        Stage.DONE -> if (app?.kind == TripKind.RIDE) "g-finish-flag" else stage.icon
        Stage.PREPARING, Stage.PLACED -> if (app != null && app.kind == TripKind.DELIVERY) app.icon else stage.icon
        // a car ride: the car heading for the pin, then for the flag; an auto or a bike taxi shows itself
        Stage.PICKUP, Stage.ON_TRIP -> (vehicle ?: app?.icon)?.takeIf { it in TWO_THREE_WHEELERS } ?: stage.icon
        // a ride's driver arriving is the car (or the auto, the bike) coming; a delivery arriving is home
        Stage.ARRIVING -> if (app?.kind == TripKind.RIDE) vehicle ?: app.icon else stage.icon
        Stage.ON_THE_WAY -> if (app?.kind == TripKind.DELIVERY && app.icon in RIDERS) app.icon else stage.icon
        else -> stage.icon
    }

    private val TWO_THREE_WHEELERS = setOf("g-auto", "g-bike-taxi")
    private val RIDERS = setOf("g-scooter", "g-car", "g-taxi", "g-bike-taxi", "g-auto")

    /** "12 min left · On the way" for the page. */
    fun summary(r: ProgressReading): String {
        val parts = ArrayList<String>()
        when {
            r.done -> parts += r.stage!!.label
            r.minutesLeft != null -> parts += if (r.minutesLeft <= 0) "Arriving now" else if (r.minutesLeft >= 120) "${r.minutesLeft / 60} h ${r.minutesLeft % 60} min left" else "${r.minutesLeft} min left"
            r.bar != null -> parts += "${(r.bar * 100).toInt()}%"
            r.source == ProgressSource.WAITING -> parts += "Starting"
        }
        r.stage?.takeIf { !r.done && !(it == Stage.ARRIVING && parts.firstOrNull() == "Arriving now") }?.let { parts += it.label }
        return parts.joinToString(" · ")
    }
}
