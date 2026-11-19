package com.dotforge.setup

import com.dotforge.rules.RuleEngine
import com.dotforge.rules.SimpleRules
import com.dotforge.setup.FirstRunFlow.Step
import com.dotforge.ui.AppEntry
import com.dotforge.ui.home.HomeStatus
import com.dotforge.ui.home.permissionBanner
import com.dotforge.ui.settings.PermissionItem
import com.dotforge.ui.settings.PermissionState
import com.dotforge.ui.settings.permissionsSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** First run's order and skip logic, the Calls card, Pick apps, and the Home banner's count. */
class FirstRunFlowTest {
    private val phone3 = FirstRunFlow.order(phone3 = true, restored = false)
    private val none = Perms()
    private val all = Perms(access = true, screening = true, contacts = true, keepRunning = true, callerName = true)

    // ---------------------------------------------------------------- order and skip logic

    @Test fun theOrderIsWelcomeTheThreeCoreCardsCallerNamePickAppsDone() {
        assertEquals(
            listOf(Step.WELCOME, Step.NOTIFICATIONS, Step.CALLS, Step.KEEP_RUNNING, Step.CALLER_NAME, Step.PICK_APPS, Step.DONE),
            phone3,
        )
    }

    @Test fun anotherPhoneHasNoCallerNameCardAndARestoreHasNoPickApps() {
        assertFalse(Step.CALLER_NAME in FirstRunFlow.order(phone3 = false, restored = false))
        val restored = FirstRunFlow.order(phone3 = true, restored = true)
        assertFalse(Step.PICK_APPS in restored)
        assertTrue(Step.CALLER_NAME in restored)
    }

    @Test fun notNowMovesOnOneCardAtATimeWhenNothingIsAllowed() {
        var s = Step.WELCOME
        val seen = mutableListOf<Step>()
        while (s != Step.DONE) { s = FirstRunFlow.next(s, phone3, none); seen += s }
        assertEquals(listOf(Step.NOTIFICATIONS, Step.CALLS, Step.KEEP_RUNNING, Step.CALLER_NAME, Step.PICK_APPS, Step.DONE), seen)
    }

    @Test fun cardsAlreadyAllowedArePassedOver() {
        assertEquals(Step.CALLS, FirstRunFlow.next(Step.WELCOME, phone3, none.copy(access = true)))
        // Calls needs both the role and Contacts
        assertEquals(Step.CALLS, FirstRunFlow.next(Step.NOTIFICATIONS, phone3, none.copy(screening = true)))
        assertEquals(Step.KEEP_RUNNING, FirstRunFlow.next(Step.NOTIFICATIONS, phone3, none.copy(screening = true, contacts = true)))
        // the caller name card is optional and comes up unless the phone says it's done
        assertEquals(Step.CALLER_NAME, FirstRunFlow.next(Step.WELCOME, phone3, all.copy(callerName = null)))
        assertEquals(Step.PICK_APPS, FirstRunFlow.next(Step.WELCOME, phone3, all))
        // Pick apps is never passed over
        assertEquals(Step.PICK_APPS, FirstRunFlow.next(Step.KEEP_RUNNING, phone3, all))
    }

    @Test fun aRestoreStartsAtTheFirstCardStillToDo() {
        val order = FirstRunFlow.order(phone3 = true, restored = true)
        assertEquals(Step.WELCOME, FirstRunFlow.start(phone3, restored = false, p = none))
        assertEquals(Step.NOTIFICATIONS, FirstRunFlow.start(order, restored = true, p = none))
        assertEquals(Step.KEEP_RUNNING, FirstRunFlow.start(order, restored = true, p = none.copy(access = true, screening = true, contacts = true)))
        assertEquals(Step.DONE, FirstRunFlow.start(order, restored = true, p = all))
    }

    @Test fun backGoesToThePreviousStepAndNotFromWelcome() {
        assertNull(FirstRunFlow.back(Step.WELCOME, phone3))
        assertEquals(Step.NOTIFICATIONS, FirstRunFlow.back(Step.CALLS, phone3))
        assertEquals(Step.KEEP_RUNNING, FirstRunFlow.back(Step.PICK_APPS, FirstRunFlow.order(phone3 = false, restored = false)))
    }

    @Test fun positionCountsTheStepsBetweenWelcomeAndDone() {
        assertNull(FirstRunFlow.position(Step.WELCOME, phone3))
        assertNull(FirstRunFlow.position(Step.DONE, phone3))
        assertEquals(1 to 5, FirstRunFlow.position(Step.NOTIFICATIONS, phone3))
        assertEquals(4 to 4, FirstRunFlow.position(Step.PICK_APPS, FirstRunFlow.order(phone3 = false, restored = false)))
    }

    @Test fun firstRunNeverAsksToPostNotifications() {
        // POST_NOTIFICATIONS is asked by the feature that first needs it (the timer), never as a card
        assertEquals(listOf(Step.NOTIFICATIONS, Step.CALLS, Step.KEEP_RUNNING, Step.CALLER_NAME), FirstRunFlow.CARDS)
        assertTrue(Step.entries.none { it.name.contains("POST") })
    }

    // ---------------------------------------------------------------- the Calls card

    @Test fun callsAsksTheRoleThenContactsAndSaysWhenTheRoleIsRefused() {
        assertEquals(CallsCard.Action.ASK_ROLE, CallsCard.of(false, false, 0).action)
        assertEquals("Allow", CallsCard.of(false, false, 0).button)
        val refused = CallsCard.of(false, true, 1)
        assertEquals(CallsCard.Action.ASK_ROLE, refused.action)
        assertEquals("Try again", refused.button)
        assertTrue(refused.line!!.startsWith("Android didn't let DotForge screen calls"))
        // Android stops showing its dialog: Default apps instead
        assertEquals(CallsCard.Action.OPEN_DEFAULT_APPS, CallsCard.of(false, false, CallsCard.ROLE_TRIES).action)
        assertEquals(CallsCard.Action.ASK_CONTACTS, CallsCard.of(true, false, 0).action)
        assertEquals(CallsCard.Action.NEXT, CallsCard.of(true, true, 3).action)
        assertNull(CallsCard.of(true, true, 0).line)
    }

    // ---------------------------------------------------------------- Pick apps

    private val whatsapp = AppEntry("com.whatsapp", "WhatsApp")
    private val messages = AppEntry("com.google.android.apps.messaging", "Messages")
    private val phone = AppEntry("com.google.android.dialer", "Phone")
    private val telegram = AppEntry("org.telegram.messenger", "Telegram")
    private val maps = AppEntry("com.google.android.apps.maps", "Maps")

    @Test fun onlyInstalledMessagingAndCallAppsAreOffered() {
        val offered = PickApps.offered(listOf(maps, telegram, phone, whatsapp, messages))
        assertEquals(listOf(whatsapp, messages, phone, telegram), offered)
        assertEquals(listOf(phone), PickApps.offered(listOf(maps, phone)))
        // the phone's own SMS app is offered even when it isn't a known one
        val sms = AppEntry("com.example.sms", "SMS")
        assertEquals(listOf(sms), PickApps.offered(listOf(maps, sms), defaultSms = sms.packageName))
    }

    @Test fun whatsAppMessagesAndPhoneArePreTickedOnlyWhenInstalled() {
        assertEquals(setOf(whatsapp.packageName, messages.packageName, phone.packageName), PickApps.preTicked(PickApps.offered(listOf(whatsapp, messages, phone, telegram))))
        assertEquals(setOf(phone.packageName), PickApps.preTicked(PickApps.offered(listOf(phone, maps))))
        assertEquals(emptySet<String>(), PickApps.preTicked(PickApps.offered(listOf(telegram))))
        val sms = AppEntry("com.example.sms", "SMS")
        assertEquals(setOf(sms.packageName), PickApps.preTicked(PickApps.offered(listOf(sms), defaultSms = sms.packageName), defaultSms = sms.packageName))
    }

    @Test fun pickingWritesNormalAppRowsAndThePhoneCallsRow() {
        val rules = RuleEngine.presets()
        val offered = listOf(whatsapp, phone, telegram)
        val out = PickApps.apply(rules, offered, setOf(whatsapp.packageName, phone.packageName))
        // Phone calls is already on: nothing to change; WhatsApp gets its own row with its icon
        assertEquals(1, out.size)
        val wa = out.single()
        assertTrue(SimpleRules.isAppRule(wa))
        assertEquals(listOf("com.whatsapp"), wa.packages)
        assertEquals("WhatsApp", wa.name)
        assertEquals(SimpleRules.newApp("x", "x").glyph, wa.glyph)
        // an app already in Alerts isn't added twice
        assertTrue(PickApps.apply(rules + wa, offered, setOf(whatsapp.packageName, phone.packageName)).isEmpty())
    }

    @Test fun unpickingPhoneTurnsThePhoneCallsRowOffAndPickingBringsItBack() {
        val rules = RuleEngine.presets()
        val off = PickApps.apply(rules, listOf(phone), emptySet()).single()
        assertEquals(SimpleRules.PHONE_CALLS, off.id)
        assertFalse(off.enabled)
        val back = PickApps.apply(rules.filterNot { it.id == SimpleRules.PHONE_CALLS }, listOf(phone), setOf(phone.packageName)).single()
        assertEquals(SimpleRules.PHONE_CALLS, back.id)
        assertTrue(back.enabled)
        // no Phone app offered: the Phone calls row is left alone
        assertTrue(PickApps.apply(rules, listOf(whatsapp), emptySet()).isEmpty())
    }

    // ---------------------------------------------------------------- Home's banner and Profile's row

    @Test fun theBannerShowsOnlyWhenACorePermissionIsMissing() {
        assertNull(permissionBanner(HomeStatus().missing))
        // the microphone, a picked app or double lights are not core permissions
        assertNull(permissionBanner(HomeStatus(microphone = false, appsPicked = false, doubleLights = false).missing))
        assertEquals("1 thing needs permission", permissionBanner(HomeStatus(keepRunning = false).missing))
        assertEquals("2 things need permission", permissionBanner(HomeStatus(access = false, contacts = false).missing))
        assertEquals(4, HomeStatus(access = false, screening = false, contacts = false, keepRunning = false).missing)
        assertEquals(0, all.missingCore)
        assertEquals(4, none.missingCore)
    }

    @Test fun profileSaysHowManyNeedPermission() {
        fun item(s: PermissionState) = PermissionItem("x", "y", s) {}
        assertEquals("All set", permissionsSummary(listOf(item(PermissionState.ALLOWED), item(PermissionState.OPTIONAL))))
        assertEquals("1 needs permission", permissionsSummary(listOf(item(PermissionState.NEEDED), item(PermissionState.OPTIONAL))))
        assertEquals("2 need permission", permissionsSummary(listOf(item(PermissionState.NEEDED), item(PermissionState.NEEDED))))
    }
}
