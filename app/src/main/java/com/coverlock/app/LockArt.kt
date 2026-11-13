package com.coverlock.app

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import kotlin.math.max

/**
 * Handles everything about the lock screen wallpaper:
 *  - remembering the user's normal lock wallpaper
 *  - drawing the album cover as the full-screen lock wallpaper
 *  - putting the normal wallpaper back
 */
object LockArt {
    private const val PREFS = "coverlock"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_COVERING = "covering"
    const val KEY_FOCUS = "focus"
    const val KEY_HD = "hd"

    /** Changing any of these should re-draw the wallpaper right away. */
    val REDRAW_KEYS = setOf(KEY_FOCUS, KEY_HD)

    private const val USER_FILE = "normal_lock_user.img"     // picked by the user in the app
    private const val BACKUP_FILE = "normal_lock_backup.img" // auto-copied (when Android allows it)
    private const val LAST_COVER_FILE = "last_cover.jpg"     // for the in-app preview

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, true)
    fun setEnabled(c: Context, value: Boolean) = prefs(c).edit().putBoolean(KEY_ENABLED, value).apply()

    fun getFocus(c: Context): Int = prefs(c).getInt(KEY_FOCUS, 50)
    fun setFocus(c: Context, pct: Int) = prefs(c).edit().putInt(KEY_FOCUS, pct).apply()

    fun isHdEnabled(c: Context) = prefs(c).getBoolean(KEY_HD, true)
    fun setHdEnabled(c: Context, value: Boolean) = prefs(c).edit().putBoolean(KEY_HD, value).apply()

    fun hasNormalWallpaper(c: Context) =
        File(c.filesDir, USER_FILE).exists() || File(c.filesDir, BACKUP_FILE).exists()

    /** Saves the image the user picked as "my normal lock screen wallpaper". */
    fun saveUserWallpaper(c: Context, uri: Uri): Boolean = try {
        c.contentResolver.openInputStream(uri)?.use { input ->
            File(c.filesDir, USER_FILE).outputStream().use { input.copyTo(it) }
        }
        true
    } catch (e: Exception) {
        false
    }

    /** Best-effort copy of the current lock wallpaper. Android often blocks this; that's fine. */
    private fun backupCurrentLock(c: Context) {
        val backup = File(c.filesDir, BACKUP_FILE)
        try {
            val wm = WallpaperManager.getInstance(c)
            val pfd = wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)
            if (pfd == null) {
                // No separate lock wallpaper: lock screen follows the home wallpaper.
                backup.delete()
            } else {
                pfd.use {
                    FileInputStream(it.fileDescriptor).use { input ->
                        backup.outputStream().use { out -> input.copyTo(out) }
                    }
                }
            }
        } catch (e: Exception) {
            // keep whatever backup we had
        }
    }

    fun screenSize(c: Context): Pair<Int, Int> {
        val dm = c.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    @Synchronized
    fun applyCover(c: Context, artIn: Bitmap) {
        // Canvas drawing needs a normal (non-hardware) bitmap
        val art = if (artIn.config == Bitmap.Config.HARDWARE) artIn.copy(Bitmap.Config.ARGB_8888, false) else artIn
        saveLastCover(c, art)

        val p = prefs(c)
        if (!p.getBoolean(KEY_COVERING, false)) {
            backupCurrentLock(c)
            p.edit().putBoolean(KEY_COVERING, true).apply()
        }
        val (w, h) = screenSize(c)
        val wallpaper = CoverStyles.render(art, w, h, getFocus(c))
        WallpaperManager.getInstance(c).setBitmap(wallpaper, null, true, WallpaperManager.FLAG_LOCK)
    }

    /** True while our cover is on the lock screen (so the normal wallpaper still needs to come back). */
    fun isCovering(c: Context) = prefs(c).getBoolean(KEY_COVERING, false)

    fun hasLastCover(c: Context) = File(c.filesDir, LAST_COVER_FILE).exists()

    /**
     * Puts the normal wallpaper back. Returns false if that failed, and in that case the
     * "covering" flag is kept so the next attempt tries again (and the cover can't get stuck for good).
     */
    @Synchronized
    fun restore(c: Context): Boolean {
        val p = prefs(c)
        if (!p.getBoolean(KEY_COVERING, false)) return true
        val wm = WallpaperManager.getInstance(c)
        val saved = listOf(USER_FILE, BACKUP_FILE).map { File(c.filesDir, it) }.firstOrNull { it.exists() }
        var ok = false
        try {
            if (saved != null) {
                FileInputStream(saved).use { wm.setStream(it, null, true, WallpaperManager.FLAG_LOCK) }
            } else {
                // Lock screen goes back to following the home screen wallpaper.
                wm.clear(WallpaperManager.FLAG_LOCK)
            }
            ok = true
        } catch (e: Exception) {
            try {
                wm.clear(WallpaperManager.FLAG_LOCK)
                ok = true
            } catch (_: Exception) {}
        }
        if (ok) p.edit().putBoolean(KEY_COVERING, false).apply()
        return ok
    }

    // ───────────── in-app preview ─────────────

    private fun saveLastCover(c: Context, art: Bitmap) {
        try {
            val scale = 768f / max(art.width, art.height)
            val small = if (scale < 1f)
                Bitmap.createScaledBitmap(
                    art,
                    (art.width * scale).toInt().coerceAtLeast(1),
                    (art.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            else art
            File(c.filesDir, LAST_COVER_FILE).outputStream().use {
                small.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
        } catch (e: Exception) {
            // preview just falls back to the sample cover
        }
    }

    /** Last cover that actually played, or a colorful sample if nothing has played yet. */
    private fun previewCover(c: Context): Bitmap {
        val f = File(c.filesDir, LAST_COVER_FILE)
        val bm = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        return bm ?: sampleCover()
    }

    private fun sampleCover(): Bitmap {
        val n = 512
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            0f, 0f, n.toFloat(), n.toFloat(),
            Color.rgb(255, 61, 119), Color.rgb(61, 90, 254), Shader.TileMode.CLAMP
        )
        cv.drawRect(0f, 0f, n.toFloat(), n.toFloat(), p)
        p.shader = null
        p.color = Color.argb(70, 255, 255, 255)
        cv.drawCircle(n * 0.5f, n * 0.5f, n * 0.28f, p)
        return b
    }

    fun renderPreview(c: Context, focus: Int): Bitmap {
        val (sw, sh) = screenSize(c)
        val w = 360
        val h = (360f * sh / sw).toInt()
        return CoverStyles.render(previewCover(c), w, h, focus)
    }
}
