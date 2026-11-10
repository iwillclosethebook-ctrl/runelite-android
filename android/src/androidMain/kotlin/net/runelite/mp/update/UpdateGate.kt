package net.runelite.mp.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface UpdateState
{
    data object None : UpdateState
    data class Available(val release: UpdateChecker.Release) : UpdateState
    data class Downloading(val release: UpdateChecker.Release, val progress: Float) : UpdateState
    /** Handed to the installer; kept so the user can reopen it if they backed out. */
    data class Ready(val release: UpdateChecker.Release, val apk: java.io.File) : UpdateState
    data class Failed(val release: UpdateChecker.Release, val message: String) : UpdateState
}

/**
 * Runs the update check ahead of the client boot. When a newer release exists, startup
 * halts: it is downloaded and handed to the installer straight away, with the splash
 * showing progress, and the boot waits for it — booting an old client while a new one
 * downloads only raced the two. A failed download, or an install that's rejected or
 * cancelled, falls through to booting the current build.
 */
internal object UpdateGate
{
    private const val TAG = "UpdateGate"
    private const val CHECK_TIMEOUT_MS = 4_000L
    // Watch wifi is slow to wake; give the check longer since an update there is unattended.
    private const val WATCH_CHECK_TIMEOUT_MS = 10_000L

    var state: UpdateState by mutableStateOf(UpdateState.None)
    var bootStarted: Boolean by mutableStateOf(false)
        private set
    /** Updates install without the card; the splash shows their progress instead. */
    var autoUpdate: Boolean by mutableStateOf(false)
        private set

    /** Boot held back while an auto-update is with the installer; run if the install fails. */
    private var deferredBoot: (() -> Unit)? = null

    fun isWatch(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)

    suspend fun checkThenBoot(context: Context, scope: CoroutineScope, boot: () -> Unit)
    {
        if (bootStarted) return
        watchMode = isWatch(context)
        autoUpdate = true
        // Blocking IO can't be cancelled, so time out the await rather than the request.
        val check = scope.async(Dispatchers.IO) { UpdateChecker.newerRelease() }
        val release = withTimeoutOrNull(if (isWatch(context)) WATCH_CHECK_TIMEOUT_MS else CHECK_TIMEOUT_MS) { check.await() }
        if (release != null && autoInstall(context, scope, release, boot)) return
        startBoot(boot)
    }

    /** Why the last automatic install didn't go through (shown on the update card), or null. */
    var failMessage: String? by mutableStateOf(null)
        private set

    /** True while the boot is held for the user to install or skip a failed auto-update. */
    val canSkip: Boolean get() = deferredBoot != null

    private var lastApk: java.io.File? = null
    private var watchMode = false

    /**
     * Downloads and installs; true means the boot is held (installing, or waiting for the
     * user after a failure). A failure no longer boots the old build silently: the card
     * shows the reason with Install (the system installer, which also walks through the
     * "install unknown apps" permission) and Skip.
     */
    private suspend fun autoInstall(context: Context, scope: CoroutineScope, release: UpdateChecker.Release, boot: () -> Unit): Boolean
    {
        state = UpdateState.Downloading(release, 0f)
        deferredBoot = boot
        return try
        {
            val apk = withContext(Dispatchers.IO) {
                UpdateChecker.download(context, release) { p ->
                    scope.launch { state = UpdateState.Downloading(release, p) }
                }
            }
            lastApk = apk
            state = UpdateState.Ready(release, apk)
            if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls())
            {
                throw IllegalStateException("Allow \"Install unknown apps\" for RuneLite, then tap Install")
            }
            withContext(Dispatchers.IO) { UpdateChecker.installSession(context, apk) }
            true
        }
        catch (t: Throwable)
        {
            Log.w(TAG, "auto-update failed", t)
            showFailure(release, t.message ?: t.javaClass.simpleName)
            true
        }
    }

    /** The installer rejected or the user cancelled the auto-update: show why, let them retry or skip. */
    fun onInstallFailed(message: String)
    {
        val release = when (val s = state)
        {
            is UpdateState.Ready -> s.release
            is UpdateState.Failed -> s.release
            is UpdateState.Downloading -> s.release
            else -> null
        }
        if (release == null)
        {
            skip()
            return
        }
        showFailure(release, message)
    }

    private fun showFailure(release: UpdateChecker.Release, message: String)
    {
        failMessage = message
        try
        {
            net.runelite.client.plugins.humanizer.SessionLog.INSTANCE.line("ERROR", "update to ${release.tag} failed: $message")
        }
        catch (ignored: Throwable)
        {
        }
        if (watchMode)
        {
            // No room for the card on a watch: carry on with this build as before.
            state = UpdateState.Failed(release, message)
            skip()
            return
        }
        autoUpdate = false
        val apk = lastApk
        state = if (apk != null && apk.isFile) UpdateState.Ready(release, apk) else UpdateState.Failed(release, message)
    }

    /** Boot the current build without updating (the card's Skip). */
    fun skip()
    {
        failMessage = null
        val boot = deferredBoot ?: return
        deferredBoot = null
        startBoot(boot)
    }

    private fun startBoot(boot: () -> Unit)
    {
        if (bootStarted) return
        bootStarted = true
        boot()
    }

    /** Boot reached login: an update the user didn't act on goes away; one they started stays. */
    fun onBootComplete()
    {
        if (state is UpdateState.Available || state is UpdateState.Failed) state = UpdateState.None
    }
}
