package com.dotforge.automation

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What another app (or Tasker) can ask DotForge to do. */
sealed interface Command {
    /** Play a saved composition (its sound and lights), found by title or content URI. */
    data class PlayComposition(val composition: String) : Command

    /** Show a glyph style or saved design by name, or scroll [text], for [seconds]. */
    data class ShowGlyph(val glyph: String?, val text: String?, val seconds: Int) : Command

    data class Visualiser(val on: Boolean) : Command

    /** Switch the rule called (or with the id) [rule] on or off. */
    data class SetRule(val rule: String, val on: Boolean) : Command
}

sealed interface Parsed {
    data class Ok(val command: Command) : Parsed
    data class Rejected(val reason: String) : Parsed
}

/** Who may send commands. The app's own package always may. */
sealed interface Gate {
    data object Allowed : Gate
    /** Android didn't say who sent it, and no caller PendingIntent came with it. */
    data object UnknownSender : Gate
    /** A known app the user hasn't allowed (yet): it is listed on the Integrations page. */
    data class NotAllowed(val packageName: String) : Gate
}

/**
 * The automation API: broadcasts to `.automation.AutomationReceiver`, which holds the normal-level permission
 * [PERMISSION], from apps the user allowed on the Integrations page. The sender is who Android says sent it
 * (Android 14+, when the sender shares its identity) or the creator of the PendingIntent in [EXTRA_CALLER].
 *
 * Actions and extras:
 * - [ACTION_PLAY]: [EXTRA_COMPOSITION] (title or content URI)
 * - [ACTION_GLYPH]: [EXTRA_GLYPH] (style or design name) or [EXTRA_TEXT], [EXTRA_SECONDS] (1-60, default 5)
 * - [ACTION_VISUALISER]: [EXTRA_ON]
 * - [ACTION_RULE]: [EXTRA_RULE] (name or id), [EXTRA_ON]
 * Booleans and numbers may also be strings ("true", "on", "1", "10"), as Tasker sends them.
 */
object Automation {
    const val PERMISSION = "com.dotforge.permission.AUTOMATE"

    const val ACTION_PLAY = "com.dotforge.action.PLAY_COMPOSITION"
    const val ACTION_GLYPH = "com.dotforge.action.SHOW_GLYPH"
    const val ACTION_VISUALISER = "com.dotforge.action.VISUALISER"
    const val ACTION_RULE = "com.dotforge.action.SET_RULE"
    val ACTIONS = listOf(ACTION_PLAY, ACTION_GLYPH, ACTION_VISUALISER, ACTION_RULE)

    const val EXTRA_COMPOSITION = "composition"
    const val EXTRA_GLYPH = "glyph"
    const val EXTRA_TEXT = "text"
    const val EXTRA_SECONDS = "seconds"
    const val EXTRA_ON = "on"
    const val EXTRA_RULE = "rule"
    /** A PendingIntent the sender made (any kind); its creator package identifies the sender. */
    const val EXTRA_CALLER = "caller"

    const val DEFAULT_SECONDS = 5
    const val MAX_SECONDS = 60
    private const val MAX_TEXT = 64

    private fun text(v: Any?): String? = v?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    fun bool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> when (v.trim().lowercase()) {
            "true", "on", "1", "yes", "start" -> true
            "false", "off", "0", "no", "stop" -> false
            else -> null
        }
        else -> null
    }

    fun int(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    fun parse(action: String?, extras: Map<String, Any?>): Parsed = when (action) {
        ACTION_PLAY -> text(extras[EXTRA_COMPOSITION])?.let { Parsed.Ok(Command.PlayComposition(it)) }
            ?: Parsed.Rejected("Say which composition: extra \"$EXTRA_COMPOSITION\".")
        ACTION_GLYPH -> {
            val seconds = (int(extras[EXTRA_SECONDS]) ?: DEFAULT_SECONDS).coerceIn(1, MAX_SECONDS)
            Parsed.Ok(Command.ShowGlyph(text(extras[EXTRA_GLYPH]), text(extras[EXTRA_TEXT])?.take(MAX_TEXT), seconds))
        }
        ACTION_VISUALISER -> bool(extras[EXTRA_ON])?.let { Parsed.Ok(Command.Visualiser(it)) }
            ?: Parsed.Rejected("Say on or off: extra \"$EXTRA_ON\".")
        ACTION_RULE -> {
            val rule = text(extras[EXTRA_RULE])
            val on = bool(extras[EXTRA_ON])
            if (rule == null || on == null) Parsed.Rejected("Say which rule and on or off: extras \"$EXTRA_RULE\" and \"$EXTRA_ON\".")
            else Parsed.Ok(Command.SetRule(rule, on))
        }
        null -> Parsed.Rejected("No action.")
        else -> Parsed.Rejected("Unknown action $action.")
    }

    fun check(sender: String?, allowed: Set<String>, self: String): Gate = when {
        sender.isNullOrBlank() -> Gate.UnknownSender
        sender == self || sender in allowed -> Gate.Allowed
        else -> Gate.NotAllowed(sender)
    }

    /** A short line for logs and the plugin's blurb. */
    fun describe(c: Command): String = when (c) {
        is Command.PlayComposition -> "Play ${c.composition}"
        is Command.ShowGlyph -> "Show ${c.text?.let { "\"$it\"" } ?: c.glyph ?: "a glyph"} for ${c.seconds} s"
        is Command.Visualiser -> if (c.on) "Start the visualiser" else "Stop the visualiser"
        is Command.SetRule -> "Turn ${c.rule} ${if (c.on) "on" else "off"}"
    }
}

/**
 * The Tasker / Locale plugin's saved settings. Written from Locale's public plugin documentation
 * (EDIT_SETTING / FIRE_SETTING with the settings in a Bundle extra); no plugin library code is used.
 *
 * The plugin's receiver must be exported, so any app could send it a FIRE_SETTING. Settings made by
 * DotForge's own edit screen carry an HMAC with a key that never leaves the phone; anything else is ignored.
 */
object PluginBundle {
    const val ACTION_EDIT = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    const val ACTION_FIRE = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"

    const val KEY_ACTION = "com.dotforge.plugin.ACTION"
    const val KEY_SIG = "com.dotforge.plugin.SIG"

    private fun canonical(values: Map<String, Any?>): String =
        values.filterKeys { it != KEY_SIG }.toSortedMap().entries.joinToString("\n") { (k, v) ->
            "$k=${when (v) { is Boolean -> "b"; is Int, is Long -> "i"; null -> "n"; else -> "s" }}:$v"
        }

    fun signature(values: Map<String, Any?>, key: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(canonical(values).toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** The plugin settings for [command], signed with [key]. */
    fun of(command: Command, key: ByteArray): Map<String, Any?> {
        val values: Map<String, Any?> = when (command) {
            is Command.PlayComposition -> mapOf(KEY_ACTION to Automation.ACTION_PLAY, Automation.EXTRA_COMPOSITION to command.composition)
            is Command.ShowGlyph -> buildMap {
                put(KEY_ACTION, Automation.ACTION_GLYPH)
                command.glyph?.let { put(Automation.EXTRA_GLYPH, it) }
                command.text?.let { put(Automation.EXTRA_TEXT, it) }
                put(Automation.EXTRA_SECONDS, command.seconds)
            }
            is Command.Visualiser -> mapOf(KEY_ACTION to Automation.ACTION_VISUALISER, Automation.EXTRA_ON to command.on)
            is Command.SetRule -> mapOf(KEY_ACTION to Automation.ACTION_RULE, Automation.EXTRA_RULE to command.rule, Automation.EXTRA_ON to command.on)
        }
        return values + (KEY_SIG to signature(values, key))
    }

    /** The command in signed plugin settings; rejected when unsigned, changed or signed with another key. */
    fun read(values: Map<String, Any?>, key: ByteArray): Parsed {
        val sig = values[KEY_SIG] as? String ?: return Parsed.Rejected("Not made by DotForge's plugin screen.")
        if (!java.security.MessageDigest.isEqual(sig.toByteArray(), signature(values, key).toByteArray())) {
            return Parsed.Rejected("The plugin settings were changed or made on another phone; edit the action again.")
        }
        return Automation.parse(values[KEY_ACTION] as? String, values)
    }
}
