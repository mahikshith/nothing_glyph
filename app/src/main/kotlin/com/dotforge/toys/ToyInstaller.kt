package com.dotforge.toys

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Installs a downloaded toy APK with a PackageInstaller session. Android asks the user to confirm (and, the
 * first time, to allow DotForge to install apps, which the Toys page asks for first, with Not now).
 */
object ToyInstaller {
    sealed interface State {
        data object Idle : State
        data class Downloading(val name: String, val progress: Float) : State
        data class Installing(val name: String) : State
        data class Installed(val name: String) : State
        data class Failed(val name: String, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun set(s: State) {
        _state.value = s
    }

    @Volatile private var justInstalled = false

    /** True once after a successful install (the Toys page then opens Nothing's toy manager). */
    fun takeJustInstalled(): Boolean = justInstalled.also { justInstalled = false }

    private const val ACTION_RESULT = "com.dotforge.toys.INSTALL_RESULT"
    private const val EXTRA_NAME = "toy_name"

    /** Writes [apk] into a new session and commits it; the result arrives at [InstallResultReceiver]. Blocking. */
    fun install(ctx: Context, apk: File, name: String) {
        _state.value = State.Installing(name)
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("toy.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = Intent(ACTION_RESULT).setClass(ctx, InstallResultReceiver::class.java).putExtra(EXTRA_NAME, name)
            // mutable: the installer adds the status and, when needed, the confirmation intent
            val pi = PendingIntent.getBroadcast(ctx, id, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pi.intentSender)
        }
    }

    internal fun onResult(ctx: Context, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    _state.value = State.Failed(name, "Android didn't ask to confirm the install.")
                } else {
                    runCatching { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { _state.value = State.Failed(name, "Couldn't show Android's install screen.") }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                justInstalled = true
                _state.value = State.Installed(name)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = State.Idle
            else -> _state.value = State.Failed(name, message(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
        }
    }

    private fun message(status: Int, detail: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "A different version of this app is installed (signed by someone else)."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This toy doesn't work on this phone."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage."
        PackageInstaller.STATUS_FAILURE_INVALID -> "The download isn't a valid app."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the install."
        else -> detail?.takeIf { it.isNotBlank() } ?: "The install didn't finish."
    }
}

/** Receives PackageInstaller's answers for [ToyInstaller]. Not exported; only our own PendingIntent reaches it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ToyInstaller.onResult(context, intent)
}
