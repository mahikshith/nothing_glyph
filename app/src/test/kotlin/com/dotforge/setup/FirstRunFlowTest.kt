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
    private val phone3 = FirstRunFlow.order(phone3 = true, resumed = false)
    private val none = Perms()
    private val all = Perms(access = true, screening = true, contacts = true, keepRunning = true, callerName = true)

    // ---------------------------------------------------------------- order and skip logic

    @Test fun theOrderIsWelcomeTheThreeCoreCardsCallerNamePickAppsDone() {
        assertEquals(
            listOf(Step.WELCOME, Step.NOTIFICATIONS, Step.CALLS, Step.KEEP_RUNNING, Step.CALLER_NAME, Step.PICK_APPS, Step.DONE),
            phone3,
        )
    }

    @Test fun anotherPhoneHasNoCallerNameCardAndAResumedInstallSeesOnlyTheCoreCards() {
        assertFalse(Step.CALLER_NAME in FirstRunFlow.order(phone3 = false, resumed = false))
        // after a restore or on an existing install: no Welcome, no caller name card, never Pick apps
        assertEquals(listOf(Step.NOTIFICATIONS, Step.CALLS, Step.KEEP_RUNNING, Step.DONE), FirstRunFlow.order(phone3 = true, resumed = true))
        assertEquals(listOf(Step.NOTIFICATIONS, Step.CALLS, Step.KEEP_RUNNING, Step.DONE), FirstRunFlow.order(phone3 = false, resumed = true))
    }

    @Test fun anInstallIsExistingWhenItHasItsOwnRulesDesignsSettingsOrWasUpdated() {
        assertFalse(FirstRunFlow.isExisting(ownRules = false, designs = false, settings = false, firstInstallMs = 5, lastUpdateMs = 5))
        assertTrue(FirstRunFlow.isExisting(ownRules = true, designs = false, settings = false, firstInstallMs = 5, lastUpdateMs = 5))
        assertTrue(FirstRunFlow.isExisting(ownRules = false, designs = true, settings = false, firstInstallMs = 5, lastUpdateMs = 5))
        assertTrue(FirstRunFlow.isExisting(ownRules = false, designs = false, settings = true, firstInstallMs = 5, lastUpdateMs = 5))
        // the update that brings First run to someone who already uses DotForge
        assertTrue(FirstRunFlow.isExisting(ownRules = false, designs = false, settings = false, firstInstallMs = 5, lastUpdateMs = 9))
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

    @Test fun aResumedInstallStartsAtTheFirstCoreCardStillToDo() {
        val order = FirstRunFlow.order(phone3 = true, resumed = true)
        assertEquals(Step.WELCOME, FirstRunFlow.start(phone3, resumed = false, p = none))
        assertEquals(Step.NOTIFICATIONS, FirstRunFlow.start(order, resumed = true, p = none))
        assertEquals(Step.KEEP_RUNNING, FirstRunFlow.start(order, resumed = true, p = none.copy(access = true, screening = true, contacts = true)))
        // the caller name card is not asked again: all core allowed is Done, whatever the ringtone lights say
        assertEquals(Step.DONE, FirstRunFlow.start(order, resumed = true, p = all.copy(callerName = null)))
        // and from the last core card it goes straight to Done, never Pick apps
        assertEquals(Step.DONE, FirstRunFlow.next(Step.KEEP_RUNNING, order, none))
    }

    @Test fun backGoesToThePreviousStepAndNotFromWelcome() {
        assertNull(FirstRunFlow.back(Step.WELCOME, phone3))
        assertEquals(Step.NOTIFICATIONS, FirstRunFlow.back(Step.CALLS, phone3))
        assertEquals(Step.KEEP_RUNNING, FirstRunFlow.back(Step.PICK_APPS, FirstRunFlow.order(phone3 = false, resumed = false)))
    }

    @Test fun positionCountsTheStepsBetweenWelcomeAndDone() {
        assertNull(FirstRunFlow.position(Step.WELCOME, phone3))
        assertNull(FirstRunFlow.position(Step.DONE, phone3))
        assertEquals(1 to 5, FirstRunFlow.position(Step.NOTIFICATIONS, phone3))
        assertEquals(4 to 4, FirstRunFlow.position(Step.PICK_APPS, FirstRunFlow.order(phone3 = false, resumed = false)))
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
        assertNull(CallsCard.of(false, false, 0).warning)
        val refused = CallsCard.of(false, true, 1)
        assertEquals(CallsCard.Action.ASK_ROLE, refused.action)
        assertEquals("Try again", refused.button)
        // says what is off, without blaming Android for the user's own No
        assertEquals("Call screening is off, so spam calls won't be blocked.", refused.warning)
        assertNull(refused.line)
        // Android stops showing its dialog: Default apps instead, with the steps Android's page shows
        val twice = CallsCard.of(false, false, CallsCard.ROLE_TRIES)
        assertEquals(CallsCard.Action.OPEN_DEFAULT_APPS, twice.action)
        assertEquals("Open Default apps", twice.button)
        assertEquals("Tap Caller ID & spam app, then pick DotForge.", twice.line)
        // a phone without the role goes there too
        assertEquals(CallsCard.Action.OPEN_DEFAULT_APPS, CallsCard.of(false, false, 0, roleMissing = true).action)
        assertNull(CallsCard.of(false, false, 0, roleMissing = true).warning)
        assertEquals(CallsCard.Action.ASK_CONTACTS, CallsCard.of(true, false, 0).action)
        assertEquals(CallsCard.Action.NEXT, CallsCard.of(true, true, 3).action)
        assertNull(CallsCard.of(true, true, 0).line)
    }

    @Test fun contactsRefusedTwiceExplainsAppInfoBeforeOpeningIt() {
        val blocked = CallsCard.of(true, false, 0, contactsBlocked = true)
        assertEquals(CallsCard.Action.OPEN_APP_INFO, blocked.action)
        assertEquals("Open App info", blocked.button)
        assertEquals("Android won't ask again. In App info, tap Permissions, then Contacts.", blocked.line)
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
        // the phone's own SMS app wins over Google Messages when both are installed
        val samsung = AppEntry("com.samsung.android.messaging", "Samsung Messages")
        val both = PickApps.offered(listOf(messages, samsung), defaultSms = samsung.packageName)
        assertEquals(setOf(samsung.packageName), PickApps.preTicked(both, defaultSms = samsung.packageName))
        assertEquals(setOf(messages.packageName), PickApps.preTicked(both))
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

    @Test fun aPhoneCallsRowSomeoneChangedIsNeverTouched() {
        val mine = SimpleRules.phoneCalls().copy(designId = "heart", enabled = false)
        val rules = RuleEngine.presets().filterNot { it.id == SimpleRules.PHONE_CALLS } + mine
        assertTrue(PickApps.apply(rules, listOf(phone), setOf(phone.packageName)).isEmpty())
        val on = mine.copy(enabled = true)
        assertTrue(PickApps.apply(rules.filterNot { it.id == SimpleRules.PHONE_CALLS } + on, listOf(phone), emptySet()).isEmpty())
    }

    // ---------------------------------------------------------------- Home's banner and Profile's row

    @Test fun theBannerShowsOnlyWhenACorePermissionIsMissing() {
        assertNull(permissionBanner(HomeStatus().missing))
        // the microphone, a picked app or double lights are not core permissions
        assertNull(permissionBanner(HomeStatus(microphone = false, appsPicked = false, doubleLights = false).missing))
        // two or fewer: named
        assertEquals("Keep running needs permission", permissionBanner(HomeStatus(keepRunning = false).missing))
        assertEquals("Notification access and Contacts need permission", permissionBanner(HomeStatus(access = false, contacts = false).missing))
        // skipping only the Calls card leaves two
        assertEquals("Call screening and Contacts need permission", permissionBanner(HomeStatus(screening = false, contacts = false).missing))
        assertEquals("3 things need permission", permissionBanner(HomeStatus(access = false, screening = false, contacts = false).missing))
        assertEquals(4, HomeStatus(access = false, screening = false, contacts = false, keepRunning = false).missing.size)
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
