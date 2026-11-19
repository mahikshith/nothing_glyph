package com.dotforge.live

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    /** 18:30 local. */
    private val now = LocalDateTime.of(2026, 11, 19, 18, 30).atZone(zone).toInstant().toEpochMilli()
    private fun eta(t: String, loose: Boolean = false) = Eta.minutes(t, now, zone, loose)

    @Test fun relativeWordings() {
        val cases = mapOf(
            "Arriving in 12 mins" to 12,
            "arriving in 12 mins" to 12,
            "12 min away" to 12,
            "Your rider is 7 mins away" to 7,
            "in 5 minutes" to 5,
            "Delivery in 9 minutes" to 9,
            "Reaching in 8 min" to 8,
            "ETA 12 min" to 12,
            "ETA: 14 mins" to 14,
            "Arriving in 10-15 mins" to 15,
            "Arriving in 10 – 15 mins" to 15,
            "Arriving in 20 to 25 minutes" to 25,
            "Arriving in 1 hr 5 min" to 65,
            "in 2 hours" to 120,
            "in 1 hour and 10 minutes" to 70,
            "within 30 mins" to 30,
            "Your order will reach you in about 18 mins" to 18,
            "Driver is arriving in 3 min" to 3,
            "3 mins to go" to 3,
            "6 min left" to 6,
            "Reaching your destination in 22 mins" to 22,
            "Approx. 40 minutes" to 40,
        )
        for ((text, want) in cases) assertEquals(text, want, eta(text))
    }

    @Test fun clockWordings() {
        val cases = mapOf(
            "ETA 7:45 PM" to 75,
            "ETA 7:45 pm" to 75,
            "ETA 7:45 p.m." to 75,
            "by 7:45" to 75, // no am/pm: the next 7:45 is in the evening
            "Arriving by 19:45" to 75,
            "Arriving at 7:45pm" to 75,
            "Delivery by 8 PM" to 90,
            "Expected between 7:30 - 7:45 PM" to 75,
            "Arriving today by 9 PM" to 150,
            "Arriving at 18:25" to 0, // 5 min late is "now"
        )
        for ((text, want) in cases) assertEquals(text, want, eta(text))
    }

    @Test fun clockTimesRollToTheNextOneThatComes() {
        val late = LocalDateTime.of(2026, 11, 19, 23, 50).atZone(zone).toInstant().toEpochMilli()
        assertEquals(25, Eta.minutes("Arriving by 00:15", late, zone))
        assertEquals(25, Eta.minutes("by 12:15 AM", late, zone))
        // a morning time without am/pm in the afternoon means the evening
        val noon = LocalDateTime.of(2026, 11, 19, 13, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(45, Eta.minutes("by 1:45", noon, zone))
    }

    @Test fun numbersThatAreNotArrivals() {
        for (t in listOf(
            "Ordered 5 mins ago", "5 min read", "Free waiting time 3 min", "Your OTP expires in 10 minutes",
            "Delayed by 10 mins", "Running 5 mins late", "Code valid for 15 minutes", "You saved 20 min",
            "Order placed at 6:20 PM", "Booked at 6:10 pm", "Rate your order", "Room 7", "Call 9876543210",
            "Driver is 500 m away", "4.5 stars", "Hi! 2 items in your cart",
        )) assertNull(t, eta(t))
    }

    @Test fun bareNumbersCountOnlyForKnownApps() {
        assertNull(eta("15 mins"))
        assertEquals(15, eta("15 mins", loose = true))
        assertEquals(9, eta("Zepto · 9 mins", loose = true))
        // never, even for known apps
        assertNull(eta("10 mins ago", loose = true))
    }

    @Test fun relativeWinsOverAClockTime() {
        assertEquals(12, eta("Arriving in 12 mins (by 7:45 PM)"))
    }
}

class StagesTest {
    private fun d(t: String) = Stages.of(t, TripKind.DELIVERY)
    private fun r(t: String) = Stages.of(t, TripKind.RIDE)

    @Test fun deliveryStages() {
        val cases = mapOf(
            "Order placed" to Stage.PLACED,
            "Restaurant has accepted your order" to Stage.PLACED,
            "Your food is being prepared" to Stage.PREPARING,
            "Preparing your order" to Stage.PREPARING,
            "Delivery partner assigned" to Stage.PREPARING,
            "Order is being packed" to Stage.PREPARING,
            "Your order is packed" to Stage.PACKED,
            "Shipped" to Stage.PACKED,
            "Order picked up" to Stage.ON_THE_WAY,
            "Out for delivery" to Stage.ON_THE_WAY,
            "Rahul is on the way" to Stage.ON_THE_WAY,
            "Your order is on its way" to Stage.ON_THE_WAY,
            "Rider is nearby" to Stage.ARRIVING,
            "Almost there!" to Stage.ARRIVING,
            "Arriving now" to Stage.ARRIVING,
            "Delivery partner has arrived" to Stage.ARRIVING,
            "Rider reached your location" to Stage.ARRIVING,
            "Delivered" to Stage.DONE,
            "Your order has been delivered" to Stage.DONE,
            "Order delivered. Enjoy your meal!" to Stage.DONE,
            "Order cancelled" to Stage.CANCELLED,
        )
        for ((text, want) in cases) assertEquals(text, want, d(text))
    }

    @Test fun futureDeliveryIsNotDone() {
        assertEquals(null, d("Will be delivered by 9 PM"))
        assertEquals(null, d("To be delivered tomorrow"))
        assertEquals(Stage.ON_THE_WAY, d("Out for delivery, will be delivered by 9 PM"))
    }

    @Test fun rideStages() {
        val cases = mapOf(
            "Driver assigned" to Stage.ASSIGNED,
            "Your ride is confirmed" to Stage.ASSIGNED,
            "Captain is on the way" to Stage.PICKUP,
            "Your driver has arrived" to Stage.AT_PICKUP,
            "Captain is waiting at pickup" to Stage.AT_PICKUP,
            "Trip started" to Stage.ON_TRIP,
            "Enjoy your ride" to Stage.ON_TRIP,
            "Reaching your destination in 12 min" to Stage.ON_TRIP,
            "Trip completed" to Stage.DONE,
            "Thanks for riding with us" to Stage.DONE,
            "Ride cancelled by driver" to Stage.CANCELLED,
        )
        for ((text, want) in cases) assertEquals(text, want, r(text))
    }

    @Test fun nothingSaidIsNull() {
        assertNull(d(""))
        assertNull(d("50% off on your next order"))
        assertNull(r("Rate your trip"))
    }
}

class ProgressAppsTest {
    @Test fun matchByPackage() {
        assertEquals("zomato", ProgressApps.match("com.application.zomato", "")?.id)
        assertEquals("swiggy", ProgressApps.match("in.swiggy.android", "Whatever")?.id)
        assertEquals("rapido", ProgressApps.match("com.rapido.passenger", "")?.id)
    }

    @Test fun matchByLabel() {
        assertEquals("swiggy", ProgressApps.match("x.y", "Instamart")?.id)
        assertEquals("swiggy", ProgressApps.match("x.y", "Swiggy")?.id)
        assertEquals("blinkit", ProgressApps.match("x.y", "blinkit")?.id)
        assertEquals("amazon", ProgressApps.match("x.y", "Amazon Shopping")?.id)
        assertEquals("uber", ProgressApps.match("x.y", "Uber")?.id)
        assertEquals("ola", ProgressApps.match("x.y", "Ola")?.id)
        assertEquals("bigbasket", ProgressApps.match("x.y", "bb now")?.id)
    }

    @Test fun noFalseFriends() {
        assertNull(ProgressApps.match("x.y", "Uber Eats"))
        assertNull(ProgressApps.match("x.y", "Granola"))
        assertNull(ProgressApps.match("x.y", "Olaf"))
        assertNull(ProgressApps.match("x.y", ""))
    }

    @Test fun everyPresetHasAnIconAndAKind() {
        assertEquals(10, ProgressApps.ALL.size)
        assertEquals(ProgressApps.ALL.size, ProgressApps.ALL.map { it.id }.toSet().size)
        assertTrue(ProgressApps.ALL.all { it.icon.startsWith("g-") && it.packages.isNotEmpty() && it.labels.isNotEmpty() })
        assertEquals(setOf("uber", "ola", "rapido"), ProgressApps.ALL.filter { it.kind == TripKind.RIDE }.map { it.id }.toSet())
    }
}

class LiveProgressReadTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 11, 19, 18, 30).atZone(zone).toInstant().toEpochMilli()
    private fun read(s: ProgressSignals) = LiveProgress.read(s, now, zone)

    @Test fun barFirst() {
        val r = read(ProgressSignals(bar = 0.4f, texts = listOf("Arriving in 12 mins"), known = true))!!
        assertEquals(ProgressSource.BAR, r.source)
        assertEquals(0.4f, r.bar)
        assertEquals(12, r.minutesLeft)
    }

    @Test fun countdownBeatsWords() {
        val r = read(ProgressSignals(chronometer = true, countDown = true, whenMs = now + 4 * 60_000 + 10_000, texts = listOf("Arriving in 12 mins"), known = true))!!
        assertEquals(ProgressSource.COUNTDOWN, r.source)
        assertEquals(5, r.minutesLeft)
    }

    @Test fun countUpChronometerIsNotACountdown() {
        assertNull(read(ProgressSignals(chronometer = true, countDown = false, whenMs = now - 60_000)))
    }

    @Test fun etaThenStage() {
        val r = read(ProgressSignals(texts = listOf("Zomato", "Rahul is on the way · arriving in 9 mins"), known = true))!!
        assertEquals(ProgressSource.ETA, r.source)
        assertEquals(9, r.minutesLeft)
        assertEquals(Stage.ON_THE_WAY, r.stage)
        val s = read(ProgressSignals(texts = listOf("Your food is being prepared"), known = true))!!
        assertEquals(ProgressSource.STAGE, s.source)
        assertNull(s.minutesLeft)
    }

    @Test fun wordsOnlyForKnownAppsOrOngoing() {
        assertNull(read(ProgressSignals(texts = listOf("Arriving in 12 mins"))))
        assertEquals(12, read(ProgressSignals(texts = listOf("Arriving in 12 mins"), ongoing = true))!!.minutesLeft)
    }

    @Test fun waitingBar() {
        assertEquals(ProgressSource.WAITING, read(ProgressSignals(barWaiting = true))!!.source)
    }

    @Test fun doneAndCancelled() {
        val d = read(ProgressSignals(texts = listOf("Delivered"), known = true))!!
        assertTrue(d.done)
        assertFalse(d.arriving)
        assertTrue(read(ProgressSignals(texts = listOf("Order cancelled"), known = true))!!.cancelled)
    }

    @Test fun arrivingWhenTwoMinutesOrNearby() {
        assertTrue(read(ProgressSignals(texts = listOf("2 mins away"), known = true))!!.arriving)
        assertFalse(read(ProgressSignals(texts = listOf("3 mins away"), known = true))!!.arriving)
        assertTrue(read(ProgressSignals(texts = listOf("Rider is nearby"), known = true))!!.arriving)
    }

    @Test fun nothingAtAll() {
        assertNull(read(ProgressSignals(texts = listOf("New offers for you"), known = true)))
    }
}

class LiveProgressTrackTest {
    private val t0 = 1_000_000L
    private fun eta(m: Int, stage: Stage? = null) = ProgressReading(ProgressSource.ETA, minutesLeft = m, stage = stage)

    @Test fun etaFillsAsTheMinutesRunDown() {
        var t = LiveProgress.track(null, eta(20), TripKind.DELIVERY, t0)
        assertEquals(0f, t.fraction, 0.001f)
        t = LiveProgress.track(t, eta(10), TripKind.DELIVERY, t0 + 10 * 60_000)
        assertEquals(0.5f, t.fraction, 0.01f)
        t = LiveProgress.track(t, eta(1), TripKind.DELIVERY, t0 + 19 * 60_000)
        assertEquals(0.95f, t.fraction, 0.01f)
    }

    @Test fun aLongerWaitNeverPullsTheRingBack() {
        var t = LiveProgress.track(null, eta(10), TripKind.DELIVERY, t0)
        t = LiveProgress.track(t, eta(5), TripKind.DELIVERY, t0 + 5 * 60_000)
        val before = t.fraction
        t = LiveProgress.track(t, eta(15), TripKind.DELIVERY, t0 + 6 * 60_000)
        assertTrue(t.fraction >= before)
    }

    @Test fun stageIsAFloor() {
        val t = LiveProgress.track(null, eta(20, Stage.ON_THE_WAY), TripKind.DELIVERY, t0)
        assertEquals(Stage.ON_THE_WAY.fraction, t.fraction, 0.001f)
    }

    @Test fun ridesHaveTwoLegs() {
        var t = LiveProgress.track(null, eta(4, Stage.PICKUP), TripKind.RIDE, t0)
        assertEquals(LiveProgress.Leg.PICKUP, t.leg)
        t = LiveProgress.track(t, eta(2, Stage.PICKUP), TripKind.RIDE, t0 + 2 * 60_000)
        assertTrue(t.fraction <= LiveProgress.Leg.PICKUP.to + 0.001f)
        t = LiveProgress.track(t, eta(20, Stage.ON_TRIP), TripKind.RIDE, t0 + 5 * 60_000)
        assertEquals(LiveProgress.Leg.TRIP, t.leg)
        assertEquals(Stage.ON_TRIP.fraction, t.fraction, 0.001f)
        t = LiveProgress.track(t, eta(10, Stage.ON_TRIP), TripKind.RIDE, t0 + 15 * 60_000)
        assertEquals(0.75f, t.fraction, 0.01f)
    }

    @Test fun aShortFirstWaitStartsNearlyFull() {
        val t = LiveProgress.track(null, eta(2), TripKind.DELIVERY, t0)
        assertEquals(0.9f, t.fraction, 0.01f)
        val long = LiveProgress.track(null, eta(45), TripKind.DELIVERY, t0)
        assertEquals(0f, long.fraction, 0.001f)
    }

    @Test fun aDriverArrivingForThePickupStopsShortOfTheTrip() {
        val t = LiveProgress.track(null, eta(2, Stage.ARRIVING), TripKind.RIDE, t0)
        assertEquals(LiveProgress.Leg.PICKUP, t.leg)
        assertEquals(LiveProgress.Leg.PICKUP.to, t.fraction, 0.001f)
        assertEquals(Stage.ARRIVING, Stages.of("Your driver is arriving", TripKind.RIDE))
        assertEquals(null, Stages.of("Your order is arriving in 12 mins", TripKind.DELIVERY))
    }

    @Test fun doneIsFullAndABarIsTakenAsIs() {
        assertEquals(1f, LiveProgress.track(null, ProgressReading(ProgressSource.STAGE, stage = Stage.DONE), TripKind.DELIVERY, t0).fraction)
        var t = LiveProgress.track(null, ProgressReading(ProgressSource.BAR, bar = 0.8f), TripKind.DELIVERY, t0)
        t = LiveProgress.track(t, ProgressReading(ProgressSource.BAR, bar = 0.3f), TripKind.DELIVERY, t0 + 1)
        assertEquals(0.3f, t.fraction)
    }

    @Test fun iconsAndSummary() {
        val zomato = ProgressApps.byId("zomato")
        assertEquals("g-food-bag", LiveProgress.icon(Stage.PREPARING, zomato))
        assertEquals("g-parcel-done", LiveProgress.icon(Stage.DONE, zomato))
        assertEquals("g-finish-flag", LiveProgress.icon(Stage.DONE, ProgressApps.byId("uber")))
        assertEquals("g-auto", LiveProgress.icon(Stage.PICKUP, ProgressApps.byId("rapido"), Stages.vehicle("Your auto is arriving")))
        assertEquals("g-bike-taxi", Stages.vehicle("Captain on bike is 3 min away"))
        assertEquals(null, Stages.vehicle("Your driver is arriving"))
        // each known app has its own picture, and none is the packed parcel or another app's
        assertEquals(ProgressApps.ALL.size, ProgressApps.ALL.map { it.icon }.toSet().size)
        assertTrue(ProgressApps.ALL.none { it.icon == Stage.PACKED.icon })
        assertEquals("g-car", LiveProgress.icon(Stage.ON_TRIP, ProgressApps.byId("uber")))
        assertEquals("g-bike-taxi", LiveProgress.icon(Stage.PICKUP, ProgressApps.byId("rapido")))
        assertEquals("12 min left · On the way", LiveProgress.summary(eta(12, Stage.ON_THE_WAY)))
        assertEquals("Arriving now", LiveProgress.summary(eta(0)))
        assertEquals("Done", LiveProgress.summary(ProgressReading(ProgressSource.STAGE, stage = Stage.DONE)))
        assertEquals("40%", LiveProgress.summary(ProgressReading(ProgressSource.BAR, bar = 0.4f)))
    }
}
