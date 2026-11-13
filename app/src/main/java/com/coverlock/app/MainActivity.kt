package com.coverlock.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * One screen, three states:
 *  1. Guided setup (only the steps that are still missing, one at a time)
 *  2. "You're all set" (once)
 *  3. Home (on/off, preview, a few simple settings)
 */
class MainActivity : Activity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var content: FrameLayout
    private var previewImg: ImageView? = null
    private val exec = Executors.newSingleThreadExecutor()
    private var curFocus = 50

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BLUE
        window.navigationBarColor = Ui.DEEP
        curFocus = LockArt.getFocus(this)

        val root = FrameLayout(this)
        root.addView(BackdropView(this), FrameLayout.LayoutParams(MATCH, MATCH))
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            try {
                NotificationListenerService.requestRebind(ComponentName(this, MediaWatcherService::class.java))
            } catch (_: Exception) {}
        }
        render()
    }

    override fun onDestroy() {
        exec.shutdown()
        super.onDestroy()
    }

    // ───────────── what to show ─────────────

    private fun hasAccess(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun batteryOk(): Boolean = try {
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    } catch (e: Exception) {
        true
    }

    private fun flag(key: String): Boolean = LockArt.prefs(this).getBoolean(key, false)
    private fun setFlag(key: String, value: Boolean) = LockArt.prefs(this).edit().putBoolean(key, value).apply()

    /** 0 = setup finished, otherwise the number of the step to show. */
    private fun currentStep(): Int = when {
        !hasAccess() -> 1
        !batteryOk() && !flag("skip_battery") -> 2
        !LockArt.hasNormalWallpaper(this) && !flag("skip_wallpaper") -> 3
        else -> 0
    }

    private fun render() {
        previewImg = null
        val step = currentStep()
        if (step != 0) wizard(step)
        else if (!flag("welcomed")) welcome()
        else home()
    }

    // ───────────── small building blocks ─────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    private fun box(fill: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpf(radiusDp)
            setColor(fill)
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun tv(
        text: String, sp: Float, color: Int,
        face: Typeface = Typeface.DEFAULT, spacing: Float = 0f
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        typeface = face
        letterSpacing = spacing
    }

    private fun lp(top: Int = 0, h: Int = WRAP): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH, h).apply { topMargin = dp(top) }

    /** Frosted-glass card on the blue background. */
    private fun glass(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = box(Ui.white(30), 22f, Ui.white(70), 1)
    }

    /** Light paper card. */
    private fun paper(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = box(Ui.PAPER, 24f)
    }

    private fun button(label: String, filled: Boolean, minH: Int = 58, onClick: () -> Unit): TextView {
        val t = tv(label, 13f, if (filled) Ui.INK else Color.WHITE, Ui.MONO, 0.12f)
        t.gravity = Gravity.CENTER
        t.minHeight = dp(minH)
        t.setPadding(dp(20), dp(10), dp(20), dp(10))
        val base = if (filled) box(Ui.PAPER, 32f) else box(0, 32f, Ui.white(150), 1)
        t.background = RippleDrawable(
            ColorStateList.valueOf(if (filled) Ui.black(50) else Ui.white(50)), base, null
        )
        t.setOnClickListener { onClick() }
        return t
    }

    private fun link(label: String, onClick: () -> Unit): TextView {
        val t = tv(label, 13f, Ui.white(230))
        t.gravity = Gravity.CENTER
        t.paintFlags = t.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        t.setPadding(0, dp(8), 0, dp(8))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun smallButton(label: String, onClick: () -> Unit): TextView {
        val t = tv(label, 12f, Color.WHITE, Ui.MONO, 0.12f)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(18), dp(10), dp(18), dp(10))
        t.background = box(Ui.BLUE, 20f)
        t.setOnClickListener { onClick() }
        return t
    }

    /** ON / OFF pill for paper cards. */
    private fun toggleChip(initial: Boolean, onChange: (Boolean) -> Unit): TextView {
        var state = initial
        val t = tv("", 12f, Color.WHITE, Ui.MONO, 0.12f)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(18), dp(10), dp(18), dp(10))
        fun paint() {
            t.text = if (state) "ON" else "OFF"
            t.setTextColor(if (state) Color.WHITE else Ui.INK)
            t.background = if (state) box(Ui.BLUE, 20f) else box(0, 20f, Ui.black(90), 1)
        }
        paint()
        t.setOnClickListener {
            state = !state
            paint()
            onChange(state)
        }
        return t
    }

    private fun topBar(rightLabel: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(SparkleView(this), LinearLayout.LayoutParams(dp(26), dp(26)))
        row.addView(
            tv("CoverLock", 24f, Color.WHITE, Ui.HEAVY, -0.02f),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(10) }
        )
        row.addView(tv(rightLabel, 11f, Ui.white(190), Ui.MONO, 0.1f))
        return row
    }

    /** Scrolling page; its children fade and slide in one after another. */
    private fun page(build: (LinearLayout) -> Unit) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(36))
        }
        build(col)
        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(col)
        }
        content.removeAllViews()
        content.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        for (i in 0 until col.childCount) {
            val v = col.getChildAt(i)
            v.alpha = 0f
            v.translationY = dpf(16f)
            v.animate().alpha(1f).translationY(0f).setStartDelay(60L * i).setDuration(380L).start()
        }
    }

    private fun stepsCard(items: List<String>): View {
        val card = glass()
        items.forEachIndexed { i, text ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val badge = tv("${i + 1}", 12f, Ui.INK, Ui.MONO)
            badge.gravity = Gravity.CENTER
            badge.background = box(Color.WHITE, 12f)
            row.addView(badge, LinearLayout.LayoutParams(dp(24), dp(24)))
            row.addView(
                tv(text, 15f, Color.WHITE),
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) }
            )
            card.addView(row, lp(if (i == 0) 0 else 12))
        }
        return card
    }

    private fun progress(step: Int): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 1..3) {
            val bar = View(this)
            bar.background = box(if (i <= step) Color.WHITE else Ui.white(70), 3f)
            row.addView(bar, LinearLayout.LayoutParams(0, dp(4), 1f).apply { if (i > 1) leftMargin = dp(6) })
        }
        return row
    }

    // ───────────── guided setup ─────────────

    private fun wizard(step: Int) {
        val title: String
        val body: String
        val how: List<String>
        when (step) {
            1 -> {
                title = "Let CoverLock see what's playing"
                body = "Android calls this “Notification access”. CoverLock only uses it to notice which song " +
                    "is playing and grab its cover. It doesn't save or send your notifications."
                how = listOf("Tap the button below", "Find CoverLock and switch it ON", "Tap Allow if Android asks")
            }
            2 -> {
                title = "Keep CoverLock awake"
                body = "Phones stop background apps to save battery. Allow CoverLock to run freely so your " +
                    "cover shows up every time you play music."
                how = listOf("Tap the button below", "Choose “Allow”")
            }
            else -> {
                title = "Pick your normal wallpaper"
                body = "When the music stops, CoverLock puts this picture back on your lock screen. " +
                    "Choose the one you normally use."
                how = listOf("Tap the button below", "Pick any picture from your gallery")
            }
        }

        page { col ->
            col.addView(topBar("SETUP  $step / 3"))
            col.addView(progress(step), lp(18))
            col.addView(HeroView(this, step), lp(14, dp(200)))
            col.addView(tv(title, 30f, Color.WHITE, Ui.HEAVY, -0.02f), lp(18))
            col.addView(tv(body, 16f, Ui.white(225)).apply { setLineSpacing(0f, 1.15f) }, lp(10))
            col.addView(stepsCard(how), lp(18))
            when (step) {
                1 -> {
                    col.addView(button("OPEN SETTINGS", true) { openListenerSettings() }, lp(22))
                    col.addView(
                        link("Switch greyed out or says “Restricted setting”?") {
                            helpDialog(
                                "Restricted setting",
                                "Android blocks some settings for apps installed outside the Play Store.\n\n" +
                                    "Open App info, tap ⋮ (top right), choose “Allow restricted settings”, " +
                                    "then come back and try again."
                            )
                        },
                        lp(8)
                    )
                }
                2 -> {
                    col.addView(button("ALLOW", true) { requestBattery() }, lp(22))
                    col.addView(
                        button("SKIP FOR NOW", false) {
                            setFlag("skip_battery", true)
                            render()
                        },
                        lp(10)
                    )
                    col.addView(
                        tv(
                            "On Vivo, Xiaomi, Oppo and Samsung phones you may also need to allow “Auto-start” " +
                                "or “Background activity” for CoverLock in your phone's battery settings.",
                            12f, Ui.white(190)
                        ).apply { gravity = Gravity.CENTER },
                        lp(14)
                    )
                }
                else -> {
                    col.addView(button("CHOOSE PICTURE", true) { pickWallpaper() }, lp(22))
                    col.addView(
                        button("SKIP — USE MY HOME WALLPAPER", false) {
                            setFlag("skip_wallpaper", true)
                            render()
                        },
                        lp(10)
                    )
                }
            }
        }
    }

    private fun welcome() {
        page { col ->
            col.addView(topBar("READY"))
            col.addView(HeroView(this, 4), lp(18, dp(200)))
            col.addView(tv("You're all set", 34f, Color.WHITE, Ui.HEAVY, -0.02f), lp(18))
            col.addView(
                tv(
                    "CoverLock works by itself from now on. The cover appears while music plays and goes " +
                        "away when you pause or close the player.",
                    16f, Ui.white(225)
                ).apply { setLineSpacing(0f, 1.15f) },
                lp(10)
            )
            col.addView(
                stepsCard(listOf("Play a song in any music app", "Lock your phone", "Enjoy the cover ✦")),
                lp(18)
            )
            col.addView(
                button("LET'S GO", true) {
                    setFlag("welcomed", true)
                    render()
                },
                lp(22)
            )
        }
    }

    private fun openListenerSettings() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(this, MediaWatcherService::class.java).flattenToString()
                )
                startActivity(i)
                return
            } catch (_: Exception) {
                // fall back to the general list below
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            openAppInfo()
        }
    }

    private fun requestBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                openAppInfo()
            }
        }
    }

    private fun openAppInfo() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
    }

    private fun helpDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Open app info") { _, _ -> openAppInfo() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun pickWallpaper() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(i, REQ_PICK)
    }

    // ───────────── home ─────────────

    private fun home() {
        page { col ->
            col.addView(topBar("v2.0"))
            col.addView(mainCard(), lp(18))
            col.addView(previewBlock(), lp(18))
            col.addView(focusCard(), lp(16))
            col.addView(hdCard(), lp(16))
            col.addView(wallpaperCard(), lp(12))
            col.addView(helpRow(), lp(20))
        }
    }

    private fun mainCard(): View {
        val card = paper()
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = tv("", 20f, Ui.INK, Ui.HEAVY)
        val sub = tv("", 13f, Ui.black(150))
        texts.addView(title)
        texts.addView(sub, lp(3))
        card.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))

        fun show(on: Boolean) {
            title.text = if (on) "CoverLock is ON" else "CoverLock is OFF"
            sub.text = if (on) "Play a song, then lock your phone" else "Your normal wallpaper stays"
        }
        show(LockArt.isEnabled(this))

        card.addView(toggleChip(LockArt.isEnabled(this)) { on ->
            LockArt.setEnabled(this@MainActivity, on)
            if (!on) Thread { LockArt.restore(this@MainActivity) }.start()
            show(on)
        })
        return card
    }

    private fun previewBlock(): View {
        val dm = resources.displayMetrics
        val aspect = dm.heightPixels.toFloat() / dm.widthPixels
        val pvH = dp(390)
        val pvW = (pvH / aspect).toInt()

        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val frame = FrameLayout(this).apply {
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = box(Ui.INK, 32f, Ui.white(190), 2)
        }
        val inner = FrameLayout(this).apply {
            background = box(Ui.INK, 27f)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        previewImg = img
        inner.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))

        // stand-ins for your phone's own clock and music card, so you can judge the layout
        val now = Date()
        val date = tv(SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now), 12f, Ui.white(220), Ui.MONO, 0.1f)
        date.gravity = Gravity.CENTER
        inner.addView(date, FrameLayout.LayoutParams(MATCH, WRAP).apply { topMargin = (pvH * 0.07f).toInt() })
        val time = tv(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), 48f, Color.WHITE, Ui.LIGHT)
        time.gravity = Gravity.CENTER
        inner.addView(time, FrameLayout.LayoutParams(MATCH, WRAP).apply { topMargin = (pvH * 0.105f).toInt() })
        val pill = View(this)
        pill.background = box(Ui.black(120), 22f, Ui.white(60), 1)
        inner.addView(
            pill,
            FrameLayout.LayoutParams((pvW * 0.8f).toInt(), (pvH * 0.085f).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                bottomMargin = (pvH * 0.07f).toInt()
            }
        )

        frame.addView(inner, FrameLayout.LayoutParams(pvW, pvH))
        block.addView(frame, LinearLayout.LayoutParams(WRAP, WRAP))

        val caption = if (LockArt.hasLastCover(this)) "LAST PLAYED COVER" else "SAMPLE · PLAY A SONG TO SEE YOURS"
        val cap = tv(caption, 11f, Ui.white(190), Ui.MONO, 0.1f)
        cap.gravity = Gravity.CENTER
        block.addView(cap, lp(12))

        refreshPreview()
        return block
    }

    private fun focusCard(): View {
        val card = glass()
        card.addView(tv("MOVE THE PICTURE", 11f, Ui.white(210), Ui.MONO, 0.14f))

        val seek = SeekBar(this)
        seek.max = 100
        seek.progress = curFocus
        seek.progressTintList = ColorStateList.valueOf(Color.WHITE)
        seek.progressBackgroundTintList = ColorStateList.valueOf(Ui.white(70))
        seek.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    curFocus = progress
                    refreshPreview()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                LockArt.setFocus(this@MainActivity, seekBar?.progress ?: curFocus)
            }
        })
        card.addView(seek, lp(6))

        val labels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        labels.addView(tv("◀ LEFT", 10f, Ui.white(170), Ui.MONO), LinearLayout.LayoutParams(0, WRAP, 1f))
        labels.addView(tv("RIGHT ▶", 10f, Ui.white(170), Ui.MONO))
        card.addView(labels)

        card.addView(
            tv(
                "A cover is square but your screen is tall, so the sides get cropped. " +
                    "Slide to keep the best part (a face, a title) in view.",
                13f, Ui.white(200)
            ).apply { setLineSpacing(0f, 1.1f) },
            lp(10)
        )
        return card
    }

    private fun hdCard(): View {
        val card = paper()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tv("Sharp HD covers", 18f, Ui.INK, Ui.HEAVY), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(toggleChip(LockArt.isHdEnabled(this)) { LockArt.setHdEnabled(this@MainActivity, it) })
        card.addView(head)
        card.addView(
            tv(
                "Finds a high-resolution version of the album art online (Apple iTunes, Deezer as backup) " +
                    "so it looks crisp full screen. Emoji, symbols and tags like [MC] or (Deluxe) are ignored " +
                    "when matching.",
                13f, Ui.black(170)
            ).apply { setLineSpacing(0f, 1.1f) },
            lp(8)
        )
        card.addView(
            tv("Privacy: only the cleaned song, artist and album name are sent.", 12f, Ui.black(130)),
            lp(6)
        )
        return card
    }

    private fun wallpaperCard(): View {
        val has = LockArt.hasNormalWallpaper(this)
        val card = paper()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tv("Normal wallpaper", 18f, Ui.INK, Ui.HEAVY), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(smallButton(if (has) "CHANGE" else "SET") { pickWallpaper() })
        card.addView(head)
        card.addView(
            tv(
                if (has) "Saved ✓  It comes back when the music stops."
                else "Not set. The lock screen will follow your home wallpaper when the music stops.",
                13f, Ui.black(170)
            ),
            lp(8)
        )
        return card
    }

    private fun helpRow(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            button("NOT WORKING? CHECKLIST", false, 50) {
                helpDialog(
                    "Checklist",
                    "1. Notification access must be ON for CoverLock.\n\n" +
                        "2. Battery: set CoverLock to “Unrestricted” (or “Don't optimise”).\n\n" +
                        "3. Switch greyed out? Open App info, tap ⋮, choose “Allow restricted settings”.\n\n" +
                        "4. Vivo, Xiaomi, Oppo, Samsung: also allow “Auto-start” / “Background activity”.\n\n" +
                        "5. Be online for HD covers."
                )
            },
            lp()
        )
        col.addView(
            button("HOW IT WORKS", false, 50) {
                setFlag("welcomed", false)
                render()
            },
            lp(10)
        )
        col.addView(
            button("RESTORE MY WALLPAPER NOW", false, 50) {
                Thread { LockArt.restore(this@MainActivity) }.start()
                Toast.makeText(this@MainActivity, "Restoring your wallpaper…", Toast.LENGTH_SHORT).show()
            },
            lp(10)
        )
        val foot = tv("CoverLock · v2.0", 11f, Ui.white(150), Ui.MONO, 0.12f)
        foot.gravity = Gravity.CENTER
        col.addView(foot, lp(18))
        return col
    }

    private fun refreshPreview() {
        val img = previewImg ?: return
        if (isDestroyed) return
        val focus = curFocus
        exec.execute {
            try {
                val bmp = LockArt.renderPreview(this, focus)
                runOnUiThread { if (previewImg === img) img.setImageBitmap(bmp) }
            } catch (e: Exception) {
                // preview is optional
            }
        }
    }

    @Deprecated("Fine for a simple one-screen app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data
            val ok = uri != null && LockArt.saveUserWallpaper(this, uri)
            Toast.makeText(this, if (ok) "Saved" else "Couldn't read that image", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REQ_PICK = 1
    }
}
