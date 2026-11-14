package com.coverlock.app

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.Executors

/**
 * Runs in the background (once Notification access is granted), watches every media session
 * on the phone, and puts the album cover on the lock screen while something plays.
 *
 * It also cleans up after itself: if a player is closed or crashes while still "playing",
 * the cover is removed and your normal wallpaper comes back.
 */
class MediaWatcherService : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()   // wallpaper changes
    private val net = Executors.newSingleThreadExecutor()      // HD cover lookups and downloads

    private var current: List<MediaController> = emptyList()
    private val tracked = ArrayList<Pair<MediaController, MediaController.Callback>>()

    @Volatile private var currentKey: String? = null   // the song playing right now
    @Volatile private var lastKey: String? = null      // the song whose cover is on the lock screen
    @Volatile private var pendingKey: String? = null   // a song we are searching a cover for online

    /** A song whose player still says "playing" but no sound is coming out (player closed / crashed). */
    private var staleKey: String? = null
    private var silentTicks = 0
    private var restoreRetries = 0
    private var receiverRegistered = false

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list ?: emptyList()) }

    private fun doRestore() {
        worker.execute {
            val ok = try { LockArt.restore(this) } catch (e: Exception) { Log.w(TAG, "restore failed", e); false }
            if (ok) {
                restoreRetries = 0
                lastKey = null
                currentKey = null
                pendingKey = null
            } else if (restoreRetries < 3) {
                // The wallpaper could not be put back (yet). Try again shortly instead of leaving the cover stuck.
                restoreRetries++
                main.postDelayed({ doRestore() }, 4000)
            }
        }
    }

    private val restoreRunnable = Runnable { doRestore() }

    // When the user changes a setting in the app, redraw right away.
    private val reapplyRunnable = Runnable {
        lastKey = null
        pendingKey = null
        evaluate()
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key in LockArt.REDRAW_KEYS) {
            main.removeCallbacks(reapplyRunnable)
            main.postDelayed(reapplyRunnable, 700)
        }
    }

    // Watchdog: every few seconds, double-check that "playing" really means sound is coming out.
    private val watchdog = object : Runnable {
        override fun run() {
            try { tick() } catch (e: Exception) { Log.w(TAG, "tick failed", e) }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    // Screen on / off / unlock: a good moment to make sure the lock screen matches reality.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            main.post {
                try { tick() } catch (e: Exception) { Log.w(TAG, "tick failed", e) }
                evaluate()
            }
        }
    }

    override fun onListenerConnected() {
        try {
            LockArt.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
            val msm = getSystemService(MediaSessionManager::class.java)
            val me = ComponentName(this, MediaWatcherService::class.java)
            msm.addOnActiveSessionsChangedListener(sessionListener, me, main)
            attach(msm.getActiveSessions(me))
            registerScreenReceiver()
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, WATCHDOG_MS)
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification access yet", e)
        }
    }

    override fun onListenerDisconnected() {
        shutdown()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Some apps deliver the cover through the notification a moment after the metadata.
        main.post { evaluate() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // A player's media notification disappearing usually means the player was closed.
        val isMedia = sbn?.notification?.extras?.getString(Notification.EXTRA_TEMPLATE)
            ?.contains("MediaStyle") == true
        main.post {
            if (isMedia && findPlaying() != null) silentTicks = STALE_TICKS - 1   // check quickly
            refreshSessions()
            main.postDelayed({ tick() }, 1500)
        }
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun shutdown() {
        try {
            getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
        try { LockArt.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener) } catch (_: Exception) {}
        if (receiverRegistered) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        detachAll()
        main.removeCallbacks(restoreRunnable)
        main.removeCallbacks(reapplyRunnable)
        main.removeCallbacks(watchdog)
        worker.execute { try { LockArt.restore(this) } catch (_: Exception) {} }
    }

    private fun detachAll() {
        tracked.forEach { (c, cb) -> try { c.unregisterCallback(cb) } catch (_: Exception) {} }
        tracked.clear()
    }

    private fun refreshSessions() {
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            attach(msm.getActiveSessions(ComponentName(this, MediaWatcherService::class.java)))
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed", e)
        }
    }

    private fun attach(list: List<MediaController>) {
        detachAll()
        current = list
        list.forEach { controller ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (state?.state != PlaybackState.STATE_PLAYING) {
                        staleKey = null
                        silentTicks = 0
                    }
                    evaluate()
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) = evaluate()

                override fun onSessionDestroyed() {
                    // The player went away: look at the sessions again right now.
                    main.post { refreshSessions() }
                }
            }
            controller.registerCallback(cb, main)
            tracked.add(controller to cb)
        }
        evaluate()
    }

    private fun scheduleRestore(delayMs: Long) {
        main.removeCallbacks(restoreRunnable)
        if (delayMs <= 0) main.post(restoreRunnable) else main.postDelayed(restoreRunnable, delayMs)
    }

    private fun keyOf(c: MediaController): String {
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        return listOf(c.packageName, title, album, artist).joinToString("|")
    }

    /** The player that is playing right now. Players that only *claim* to play are skipped unless [includeStale]. */
    private fun findPlaying(includeStale: Boolean = false): MediaController? = current.firstOrNull {
        it.packageName != packageName &&
            it.playbackState?.state == PlaybackState.STATE_PLAYING &&
            (includeStale || keyOf(it) != staleKey)
    }

    /**
     * Every few seconds: if a player says "playing" but no audio is coming out for a while, it was closed
     * or crashed without telling Android. Then we treat it as stopped so the wallpaper goes back.
     */
    private fun tick() {
        val raw = findPlaying(includeStale = true)
        if (raw == null) {
            silentTicks = 0
            staleKey = null
            if (LockArt.isCovering(this)) evaluate()   // nothing is playing but our cover is still up: clean up
            return
        }
        val am = getSystemService(AudioManager::class.java)
        val remote = raw.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
        if (am.isMusicActive || remote) {
            silentTicks = 0
            if (staleKey != null) {
                staleKey = null   // sound is back
                evaluate()
            }
            return
        }
        val key = keyOf(raw)
        if (staleKey == key) return
        silentTicks++
        if (silentTicks >= STALE_TICKS) {
            staleKey = key
            silentTicks = 0
            evaluate()
        }
    }

    private fun evaluate() {
        if (!LockArt.isEnabled(this)) {
            scheduleRestore(0)
            return
        }

        val playing = findPlaying()
        if (playing == null) {
            // Short delay so switching tracks doesn't flash the normal wallpaper
            scheduleRestore(1500)
            return
        }
        main.removeCallbacks(restoreRunnable)

        val md = playing.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val key = listOf(playing.packageName, title, album, artist).joinToString("|")
        currentKey = key
        if (key == lastKey) return

        val art = artFromMetadata(md) ?: artFromNotification(playing.packageName)
        val uris = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
        ).mapNotNull { md?.getString(it) }.filter { it.startsWith("http") }
        val hdOn = LockArt.isHdEnabled(this)

        if (art == null) {
            // The player gave no cover (yet). Search for one online; the artist's photo is the last resort.
            if (!hdOn || pendingKey == key) return
            pendingKey = key
            net.execute {
                val found = HdArt.fetch(this, title, artist, album, uris, 0, true)
                if (found != null) {
                    worker.execute {
                        // only if the same song is still playing and no real cover arrived meanwhile
                        if (currentKey == key && lastKey != key) {
                            lastKey = key
                            try {
                                LockArt.applyCover(this, found)
                            } catch (e: Exception) {
                                Log.w(TAG, "apply failed", e)
                            }
                        }
                    }
                }
            }
            return
        }

        lastKey = key
        worker.execute {
            // If we already downloaded a sharp cover for this song, use it straight away.
            val cached = if (hdOn) HdArt.cached(this, title, artist, album, art.width) else null
            try {
                LockArt.applyCover(this, cached ?: art)
            } catch (e: Exception) {
                Log.w(TAG, "apply failed", e)
            }
            if (hdOn && cached == null) {
                // Show the player's cover now; swap in the sharp one when it arrives.
                net.execute {
                    val hd = HdArt.fetch(this, title, artist, album, uris, art.width, false)
                    if (hd != null) {
                        worker.execute {
                            if (key == lastKey) {
                                try {
                                    LockArt.applyCover(this, hd)
                                } catch (e: Exception) {
                                    Log.w(TAG, "HD apply failed", e)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun artFromMetadata(md: MediaMetadata?): Bitmap? {
        if (md == null) return null
        md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { return it }
        md.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { return it }
        md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let { return it }
        val uris = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
        )
        for (k in uris) {
            val s = md.getString(k) ?: continue
            loadUri(s)?.let { return it }
        }
        return null
    }

    private fun loadUri(s: String): Bitmap? = try {
        val uri = Uri.parse(s)
        if (uri.scheme == "http" || uri.scheme == "https") null
        else contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

    /** Fallback: use the big picture from the player's media notification. */
    private fun artFromNotification(pkg: String): Bitmap? = try {
        val sbn = activeNotifications.firstOrNull {
            it.packageName == pkg &&
                it.notification.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true
        }
        val drawable = sbn?.notification?.getLargeIcon()?.loadDrawable(this)
        when (drawable) {
            null -> null
            is BitmapDrawable -> drawable.bitmap
            else -> {
                val bmp = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                drawable.setBounds(0, 0, bmp.width, bmp.height)
                drawable.draw(Canvas(bmp))
                bmp
            }
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "CoverLock"
        private const val WATCHDOG_MS = 4000L
        private const val STALE_TICKS = 5   // about 20 seconds of "playing" without any sound
    }
}
