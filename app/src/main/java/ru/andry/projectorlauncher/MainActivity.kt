package ru.andry.projectorlauncher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var weatherText: TextView
    private lateinit var weatherIcon: TextView
    private lateinit var wifiIcon: ImageView
    private lateinit var btIcon: ImageView
    private var apps: List<AppEntry> = emptyList()
    private var weatherLoading = false

    data class AppEntry(val label: String, val icon: Drawable, val pkg: String)
    data class WeatherInfo(val text: String, val icon: String)
    data class WeatherLocation(val latitude: Double, val longitude: Double, val city: String)
    private val locationPermissionRequest = 4102

    private fun tr(ru: String, en: String): String = if (Locale.getDefault().language == "ru") ru else en

    private val clockTick = object : Runnable {
        override fun run() {
            if (!::clock.isInitialized) return
            val now = Date()
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            date.text = SimpleDateFormat("EEEE\nd MMMM", Locale.getDefault()).format(now)
            updateNetwork()
            handler.postDelayed(this, 30_000)
        }
    }
    private val weatherRefresh = object : Runnable {
        override fun run() {
            loadWeather(false)
            handler.postDelayed(this, 30 * 60 * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        loadApps()
        applyInitialDockDefaults()
        buildHome()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(clockTick)
        clockTick.run()
        handler.removeCallbacks(weatherRefresh)
        handler.post(weatherRefresh)
    }

    override fun onPause() {
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(weatherRefresh)
        super.onPause()
    }

    private fun loadApps() {
        apps = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).mapNotNull { info ->
            val pkg = info.activityInfo.packageName
            if (pkg == packageName) null else AppEntry(
                info.loadLabel(packageManager).toString(), info.loadIcon(packageManager), pkg
            )
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun applyInitialDockDefaults() {
        if (prefs.getBoolean("dock_defaults_v2", false)) return
        val vkVideo = apps.firstOrNull {
            it.label.contains("VK Видео", ignoreCase = true) ||
                (it.label.contains("видео", ignoreCase = true) && it.pkg.contains("vk", ignoreCase = true)) ||
                it.pkg.contains("vkvideo", ignoreCase = true)
        }
        prefs.edit()
            .putString("favorites", vkVideo?.pkg ?: "")
            .putBoolean("dock_defaults_v2", true)
            .apply()
    }

    private fun favorites(): MutableList<String> {
        val saved = prefs.getString("favorites", null)
        if (saved != null) return saved.split("|").filter { it.isNotBlank() }.take(5).toMutableList()
        val vkVideo = apps.firstOrNull {
            it.label.contains("VK Видео", ignoreCase = true) ||
                (it.label.contains("видео", ignoreCase = true) && it.pkg.contains("vk", ignoreCase = true)) ||
                it.pkg.contains("vkvideo", ignoreCase = true)
        }
        return listOfNotNull(vkVideo?.pkg).toMutableList()
    }

    private fun buildHome() {
        val root = FrameLayout(this)
        val wallpaper = ImageView(this).apply {
            setImageResource(when (prefs.getString("wallpaper", "pixel")) {
                "pixel" -> R.drawable.wallpaper_pixel_sunset
                "space" -> R.drawable.wallpaper_space
                "srilanka" -> R.drawable.wallpaper_sri_lanka
                "am_lightning" -> R.drawable.wallpaper_am_lightning
                "soft_waves" -> R.drawable.wallpaper_soft_waves
                else -> R.drawable.tyumen_bridge_wallpaper
            })
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Ночной мост через Туру в Тюмени"
        }
        root.addView(wallpaper, FrameLayout.LayoutParams(-1, -1))

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(18), dp(28), dp(8))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val pageScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isSmoothScrollingEnabled = true
        }
        pageScroll.addView(content, FrameLayout.LayoutParams(-1, -2))

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val timeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        clock = TextView(this).apply {
            text = "00:00"; textSize = 52f
            typeface = android.graphics.Typeface.create("sans-serif-light", 0)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = rounded(0xCC101A35.toInt(), 15, 0x557FCBFF, 1)
            setPadding(dp(12), 0, dp(12), dp(2))
        }
        date = TextView(this).apply {
            textSize = 18f; setTextColor(Color.WHITE)
            setPadding(dp(16), 0, 0, 0)
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        }
        timeRow.isClickable = true
        timeRow.isFocusable = true
        timeRow.contentDescription = tr("Открыть приложение Часы", "Open Clock app")
        timeRow.setOnClickListener { openClockApp() }
        timeRow.addView(clock)
        timeRow.addView(View(this).apply { setBackgroundColor(0xCCFFFFFF.toInt()) },
            LinearLayout.LayoutParams(dp(1), dp(48)).apply { leftMargin = dp(15) })
        timeRow.addView(date)
        clock.isClickable = true
        date.isClickable = true
        clock.setOnClickListener { openClockApp() }
        date.setOnClickListener { openClockApp() }
        left.addView(timeRow)

        val weather = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(0x77202C50, 14, 0x55FFFFFF, 1)
            setPadding(dp(10), dp(6), dp(14), dp(6))
            isFocusable = true; isClickable = true
            contentDescription = tr("Обновить погоду нажатием", "Tap to refresh weather")
            setOnClickListener { loadWeather(true) }
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (focused) 0xAA0756A6.toInt() else 0x77202C50,
                    14, if (focused) 0xFF64D9FF.toInt() else 0x55FFFFFF, if (focused) 2 else 1)
            }
        }
        weatherIcon = TextView(this).apply {
            text = prefs.getString("weather_icon", "🌙") ?: "🌙"
            textSize = 34f
            gravity = Gravity.CENTER
        }
        weather.addView(weatherIcon, LinearLayout.LayoutParams(dp(56), dp(52)))
        weatherText = TextView(this).apply {
            text = prefs.getString("weather_text", (prefs.getString("weather_city", tr("Тюмень", "Tyumen")) ?: "Тюмень") + "  •  " + tr("Нажмите для обновления", "Tap to refresh")) ?: "Тюмень"
            textSize = 15f; setTextColor(Color.WHITE); setPadding(dp(8), 0, 0, 0); maxLines = 3
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        }
        weather.addView(weatherText)
        left.addView(weather, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        top.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        val right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP or Gravity.END }
        wifiIcon = statusIcon("wifi", "Wi-Fi")
        btIcon = statusIcon("bluetooth", "Bluetooth")
        right.addView(wifiIcon, iconParams())
        right.addView(btIcon, iconParams())
        val settings = ImageView(this).apply {
            setImageDrawable(drawIcon("menu", Color.WHITE)); contentDescription = tr("Меню", "Menu")
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(0x88202C50.toInt(), 17, 0x66FFFFFF, 1)
            isFocusable = true; isClickable = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (focused) 0xEE0756A6.toInt() else 0x88202C50.toInt(),
                    17, if (focused) 0xFF54D8FF.toInt() else 0x66FFFFFF, if (focused) 3 else 1)
                v.scaleX = if (focused) 1.06f else 1f; v.scaleY = if (focused) 1.06f else 1f
            }
            setOnClickListener { showMenu() }
        }
        right.addView(settings, LinearLayout.LayoutParams(dp(54), dp(54)).apply { leftMargin = dp(10) })
        top.addView(right)
        page.addView(top, LinearLayout.LayoutParams(-1, -2))
        page.addView(pageScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            background = rounded(0xAA09152F.toInt(), 19, 0x668FCBFF, 1)
            setPadding(dp(9), dp(10), dp(9), dp(10)); elevation = dp(12).toFloat()
        }
        lateinit var dockTitle: TextView
        val selected = favorites()
        for (slot in 0..4) {
            val app = apps.firstOrNull { it.pkg == selected.getOrNull(slot) }
            dock.addView(appTile(app, true, { focusedApp ->
                dockTitle.text = focusedApp?.label ?: tr("Избранные приложения", "Favorite apps")
            }) {
                if (app != null) launch(app.pkg) else chooseDockApps()
            }, LinearLayout.LayoutParams(0, dp(68), 1f).apply {
                leftMargin = dp(6); rightMargin = dp(6)
                topMargin = dp(4); bottomMargin = dp(4)
            })
        }
        val dockWidth = minOf((resources.displayMetrics.widthPixels * 0.82f).toInt(), dp(528))
        dockTitle = TextView(this).apply {
            text = tr("Избранные приложения", "Favorite apps")
            textSize = 13f
            setTextColor(0xFFEAF5FF.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(5))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val dockContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(dockTitle, LinearLayout.LayoutParams(-1, dp(24)))
            addView(dock, LinearLayout.LayoutParams(-1, dp(100)))
        }
        val scrollTopSpacer = View(this)
        content.addView(scrollTopSpacer, LinearLayout.LayoutParams(1, 0))
        content.addView(dockContainer, LinearLayout.LayoutParams(dockWidth, dp(124)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        val appsGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(8))
        }
        val columns = (resources.displayMetrics.widthPixels / resources.displayMetrics.density / 112f)
            .toInt().coerceIn(5, 8)
        apps.chunked(columns).forEach { rowApps ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            rowApps.forEach { app ->
                row.addView(appTile(app, false) { launch(app.pkg) },
                    LinearLayout.LayoutParams(0, dp(72), 1f).apply {
                        leftMargin = dp(3); rightMargin = dp(3); topMargin = dp(3); bottomMargin = dp(3)
                    })
            }
            repeat(columns - rowApps.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(72), 1f).apply {
                    leftMargin = dp(3); rightMargin = dp(3)
                })
            }
            appsGrid.addView(row, LinearLayout.LayoutParams(-1, dp(78)))
        }
        content.addView(appsGrid, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })

        setContentView(root)
        pageScroll.post {
            val spacerParams = scrollTopSpacer.layoutParams as LinearLayout.LayoutParams
            spacerParams.height = (pageScroll.height - dockContainer.height).coerceAtLeast(0)
            scrollTopSpacer.layoutParams = spacerParams
            pageScroll.scrollTo(0, 0)
        }
        clockTick.run()
        updateNetwork()
        dock.post { dock.getChildAt(0)?.requestFocus() }
    }

    private fun appTile(
        app: AppEntry?,
        large: Boolean,
        onFocusChange: (AppEntry?) -> Unit = {},
        action: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            background = tileBackground(false, large)
            setPadding(dp(if (large) 4 else 2), dp(if (large) 4 else 2), dp(if (large) 4 else 2), dp(if (large) 4 else 2))
            setOnFocusChangeListener { v, focused ->
                v.background = tileBackground(focused, large)
                v.elevation = if (focused) dp(14).toFloat() else dp(2).toFloat()
                v.scaleX = if (focused) 1.02f else 1f; v.scaleY = if (focused) 1.02f else 1f
                onFocusChange(if (focused) app else null)
            }
            setOnClickListener { action() }
            if (app != null) {
                val icon = app.icon.constantState?.newDrawable(resources)?.mutate() ?: app.icon
                addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(if (large) 40 else 30), dp(if (large) 40 else 30)))
                if (!large) addView(TextView(this@MainActivity).apply {
                    text = app.label; textSize = 9.5f
                    gravity = Gravity.CENTER; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.WHITE); setPadding(0, dp(3), 0, 0)
                }, LinearLayout.LayoutParams(-1, dp(17)))
            } else addView(TextView(this@MainActivity).apply {
                text = "+"; textSize = 38f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            })
        }
    }

    private fun bottomAction(kind: String, label: String, action: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            isFocusable = true; isClickable = true; background = tileBackground(false, false)
            setOnFocusChangeListener { v, focused ->
                v.background = tileBackground(focused, false)
                v.scaleX = if (focused) 1.04f else 1f; v.scaleY = if (focused) 1.04f else 1f
            }
            setOnClickListener { action() }
            addView(ImageView(this@MainActivity).apply {
                setImageDrawable(drawIcon(kind, Color.WHITE)); scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(27), dp(27)))
            addView(TextView(this@MainActivity).apply {
                text = label; textSize = 10.5f; gravity = Gravity.CENTER; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.WHITE); setPadding(0, dp(3), 0, 0)
            }, LinearLayout.LayoutParams(-1, dp(22)))
        }
    }

    private fun statusIcon(kind: String, description: String) = ImageView(this).apply {
        setImageDrawable(drawIcon(kind, Color.WHITE)); contentDescription = description
        scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(11), dp(11), dp(11), dp(11))
        isFocusable = true; background = rounded(0x55202C50, 16, 0x00FFFFFF, 0)
        setOnFocusChangeListener { v, focused ->
            v.background = rounded(if (focused) 0xAA0756A6.toInt() else 0x55202C50,
                16, if (focused) 0xFF54D8FF.toInt() else 0x00FFFFFF, if (focused) 2 else 0)
        }
        setOnClickListener {
            val action = if (kind == "wifi") Settings.ACTION_WIFI_SETTINGS else Settings.ACTION_BLUETOOTH_SETTINGS
            try { startActivity(Intent(action)) } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { }
            }
        }
    }

    private fun iconParams() = LinearLayout.LayoutParams(dp(50), dp(50)).apply { rightMargin = dp(5) }

    private fun drawIcon(kind: String, color: Int): Drawable {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        when (kind) {
            "wifi" -> {
                canvas.drawArc(RectF(10f, 12f, 86f, 88f), 225f, 90f, false, p)
                canvas.drawArc(RectF(25f, 28f, 71f, 74f), 225f, 90f, false, p)
                p.style = Paint.Style.FILL; canvas.drawCircle(48f, 78f, 5f, p)
            }
            "bluetooth" -> canvas.drawPath(Path().apply {
                moveTo(48f, 7f); lineTo(77f, 34f); lineTo(25f, 70f)
                moveTo(48f, 7f); lineTo(48f, 89f); lineTo(77f, 62f); lineTo(25f, 26f)
            }, p)
            "gear" -> {
                for (i in 0 until 8) {
                    canvas.save(); canvas.rotate(i * 45f, 48f, 48f)
                    canvas.drawRoundRect(RectF(41f, 5f, 55f, 25f), 3f, 3f, p); canvas.restore()
                }
                canvas.drawCircle(48f, 48f, 31f, p)
                p.style = Paint.Style.FILL; canvas.drawCircle(48f, 48f, 11f, p)
            }
            "music" -> {
                canvas.drawPath(Path().apply { moveTo(59f, 14f); lineTo(78f, 10f); lineTo(78f, 64f); moveTo(59f, 14f); lineTo(59f, 65f) }, p)
                p.style = Paint.Style.FILL
                canvas.drawOval(RectF(34f, 61f, 61f, 82f), p); canvas.drawOval(RectF(53f, 59f, 80f, 80f), p)
            }
            "gallery" -> {
                canvas.drawRoundRect(RectF(10f, 15f, 86f, 81f), 9f, 9f, p)
                p.style = Paint.Style.FILL; canvas.drawCircle(31f, 34f, 7f, p)
                canvas.drawPath(Path().apply { moveTo(15f, 72f); lineTo(39f, 47f); lineTo(53f, 60f); lineTo(65f, 47f); lineTo(82f, 70f) }, p)
            }
            "movie" -> {
                canvas.drawRoundRect(RectF(10f, 24f, 86f, 82f), 8f, 8f, p); canvas.drawRect(RectF(10f, 12f, 86f, 27f), p)
                for (x in 22..78 step 18) canvas.drawLine(x.toFloat(), 12f, (x - 7).toFloat(), 27f, p)
                p.style = Paint.Style.FILL
                canvas.drawPath(Path().apply { moveTo(42f, 40f); lineTo(42f, 68f); lineTo(66f, 54f); close() }, p)
            }
            "browser" -> {
                canvas.drawCircle(48f, 48f, 37f, p); canvas.drawOval(RectF(31f, 11f, 65f, 85f), p)
                canvas.drawLine(12f, 48f, 84f, 48f, p); canvas.drawArc(RectF(15f, 27f, 81f, 69f), 180f, 180f, false, p)
            }
            "apps" -> {
                p.style = Paint.Style.FILL
                for (y in 0..2) for (x in 0..2) canvas.drawCircle(25f + x * 23f, 25f + y * 23f, 6f, p)
            }
            "edit" -> {
                canvas.drawLine(22f, 72f, 70f, 24f, p); canvas.drawLine(17f, 80f, 28f, 77f, p)
                canvas.drawLine(70f, 24f, 79f, 33f, p); canvas.drawLine(17f, 80f, 22f, 72f, p)
            }
            "menu" -> {
                p.strokeWidth = 9f
                canvas.drawLine(15f, 25f, 81f, 25f, p)
                canvas.drawLine(15f, 48f, 81f, 48f, p)
                canvas.drawLine(15f, 71f, 81f, 71f, p)
            }
        }
        return BitmapDrawable(resources, bitmap)
    }

    private fun chooseDockApps() {
        val selected = favorites().toMutableList()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(list)
            layoutParams = ViewGroup.LayoutParams(-1, dp(320))
        }
        fun refreshRows() {
            list.removeAllViews()
            apps.forEach { app ->
                val selectedIndex = selected.indexOf(app.pkg)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isFocusable = true
                    isClickable = true
                    background = pickerBackground(false)
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    addView(ImageView(this@MainActivity).apply {
                        setImageDrawable(app.icon)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }, LinearLayout.LayoutParams(dp(42), dp(42)))
                    addView(TextView(this@MainActivity).apply {
                        text = app.label
                        textSize = 15f
                        setTextColor(Color.WHITE)
                        setPadding(dp(10), 0, 0, 0)
                    }, LinearLayout.LayoutParams(0, dp(48), 1f))
                    addView(TextView(this@MainActivity).apply {
                        text = if (selectedIndex >= 0) "✓  ${selectedIndex + 1}" else "+"
                        textSize = 17f
                        gravity = Gravity.CENTER
                        setTextColor(if (selectedIndex >= 0) 0xFF75F0B2.toInt() else Color.WHITE)
                    }, LinearLayout.LayoutParams(dp(54), dp(44)))
                    setOnFocusChangeListener { v, focused -> v.background = pickerBackground(focused) }
                    setOnClickListener {
                        val index = selected.indexOf(app.pkg)
                        if (index >= 0) selected.removeAt(index)
                        else if (selected.size < 5) selected.add(app.pkg)
                        else Toast.makeText(this@MainActivity, tr("В доке максимум 5 приложений", "The dock supports up to 5 apps"), Toast.LENGTH_SHORT).show()
                        prefs.edit().putString("favorites", selected.joinToString("|")).apply()
                        refreshRows()
                    }
                }
                list.addView(row)
                list.addView(View(this).apply { setBackgroundColor(0x447FA8D8) }, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
        refreshRows()
        AlertDialog.Builder(this)
            .setTitle(tr("Док: выберите до 5 приложений", "Dock: choose up to 5 apps"))
            .setMessage(tr("Нажмите, чтобы добавить. Нажмите ещё раз, чтобы убрать. Цифры показывают порядок.", "Tap to add or tap again to remove. Numbers show the order."))
            .setView(scroll)
            .setPositiveButton(tr("Готово", "Done"), null)
            .setNegativeButton(tr("Закрыть", "Close"), null)
            .setOnDismissListener { buildHome() }
            .show()
    }

    private fun showMenu() {
        val items = arrayOf(
            tr("Настройки проектора", "Projector settings"),
            tr("Звук", "Sound"),
            tr("Оформление", "Appearance"),
            tr("Приложения", "Apps"),
            tr("Настроить док", "Customize dock"),
            tr("Система", "System"),
            tr("О ProjectorHome", "About ProjectorHome")
        )
        AlertDialog.Builder(this).setTitle("ProjectorHome")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openSystemScreen("com.zhiying.settings", "com.zhiying.settings.MainActivity")
                    1 -> openSystemScreen("com.android.tv.settings", "com.android.tv.settings.device.sound.SoundActivity")
                    2 -> chooseWallpaper()
                    3 -> openSystemScreen("com.zhiying.settings", "com.zhiying.settings.application.ApplicationActivity")
                    4 -> chooseDockApps()
                    5 -> showSystemMenu()
                    6 -> showAboutProjectorHome()
                }
            }.setNegativeButton(tr("Закрыть", "Close"), null).show()
    }

    private fun showSystemMenu() {
        val items = arrayOf(
            tr("Приложения и кэш", "Apps and cache"),
            tr("Все настройки Android", "All Android settings"),
            tr("Сделать ProjectorHome главным экраном", "Set ProjectorHome as the home screen")
        )
        AlertDialog.Builder(this).setTitle(tr("Система", "System"))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openAndroidSettings(Settings.ACTION_APPLICATION_SETTINGS, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, Settings.ACTION_SETTINGS)
                    1 -> openAndroidSettings(Settings.ACTION_SETTINGS)
                    2 -> {
                        try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                        catch (_: Exception) {
                            try { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                        }
                    }
                }
            }.setNegativeButton(tr("Назад", "Back"), null).show()
    }

    private fun openAndroidSettings(vararg actions: String) {
        for (action in actions) {
            try {
                startActivity(Intent(action))
                return
            } catch (_: Exception) { }
        }
        Toast.makeText(this, tr("Этот раздел Android недоступен в прошивке", "This Android section is unavailable in this firmware"), Toast.LENGTH_SHORT).show()
    }

    private fun openSystemScreen(packageName: String, className: String) {
        try {
            startActivity(Intent().setClassName(packageName, className))
        } catch (_: Exception) {
            Toast.makeText(this, tr("Этот раздел настроек не удалось открыть", "Could not open this settings section"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAboutProjectorHome() {
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.am_play_logo)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "AM PLAY"
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(4))
            addView(logo, LinearLayout.LayoutParams(-1, dp(150)))
            addView(TextView(this@MainActivity).apply {
                text = tr("Лёгкий домашний экран для проектора", "A lightweight home screen for your projector")
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(0xFFCCCCCC.toInt())
                setPadding(0, dp(8), 0, dp(2))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle(tr("О ProjectorHome", "About ProjectorHome"))
            .setView(holder)
            .setPositiveButton(tr("Закрыть", "Close"), null)
            .show()
    }

    private fun showAllApps() {
        AlertDialog.Builder(this).setTitle(tr("Все приложения", "All apps"))
            .setItems(apps.map { it.label }.toTypedArray()) { _, which -> apps.getOrNull(which)?.let { launch(it.pkg) } }
            .setNegativeButton(tr("Закрыть", "Close"), null).show()
    }

    private fun chooseWallpaper() {
        val names = arrayOf("Тюмень", tr("Закат в горах", "Mountain sunset"), tr("Космос", "Space"), tr("Шри-Ланка", "Sri Lanka"), "A M — электричество", tr("Мягкие волны", "Soft waves"))
        val values = arrayOf("tyumen", "pixel", "space", "srilanka", "am_lightning", "soft_waves")
        val current = prefs.getString("wallpaper", "pixel")
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle(tr("Выберите обои", "Choose wallpaper"))
            .setSingleChoiceItems(names, checked) { dialog, which ->
                prefs.edit().putString("wallpaper", values[which]).apply()
                dialog.dismiss()
                buildHome()
            }
            .setNegativeButton(tr("Отмена", "Cancel"), null).show()
    }

    private fun launchByKeywords(words: List<String>) {
        val app = apps.firstOrNull { entry ->
            val label = entry.label.lowercase(Locale.getDefault())
            words.any { label.contains(it) }
        }
        if (app != null) launch(app.pkg) else showAllApps()
    }

    private fun loadWeather(manual: Boolean) {
        if (weatherLoading) return
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("location_permission_asked", false)) {
            prefs.edit().putBoolean("location_permission_asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), locationPermissionRequest)
            return
        }
        weatherLoading = true
        if (manual) weatherText.text = tr("Определяем местоположение…", "Detecting location…")
        Thread {
            var result: WeatherInfo? = null
            try {
                val loc = resolveWeatherLocation()
                prefs.edit().putString("weather_city", loc.city).putFloat("weather_latitude", loc.latitude.toFloat()).putFloat("weather_longitude", loc.longitude.toFloat()).apply()
                result = try { fetchMetWeather(loc) } catch (_: Exception) { fetchOpenMeteoWeather(loc) }
            } catch (_: Exception) { }
            val value = result
            handler.post {
                weatherLoading = false
                if (!::weatherText.isInitialized) return@post
                if (value != null) {
                    weatherText.text = value.text
                    weatherIcon.text = value.icon
                    prefs.edit().putString("weather_text", value.text)
                        .putString("weather_icon", value.icon).apply()
                } else {
                    val cached = prefs.getString("weather_text", null)
                    weatherText.text = if (cached != null) "$cached\n" + tr("Нет связи с погодным сервисом", "No connection to weather service") else tr("Погода недоступна", "Weather unavailable")
                }
            }
        }.start()
    }

    private fun resolveWeatherLocation(): WeatherLocation {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    val last = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    if (last != null) {
                        var city = tr("Ваше местоположение", "Your location")
                        try {
                            @Suppress("DEPRECATION")
                            val addresses = Geocoder(this, Locale.getDefault()).getFromLocation(last.latitude, last.longitude, 1)
                            if (!addresses.isNullOrEmpty()) city = addresses[0].locality ?: addresses[0].subAdminArea ?: addresses[0].adminArea ?: city
                        } catch (_: Exception) { }
                        return WeatherLocation(last.latitude, last.longitude, city)
                    }
                }
            } catch (_: SecurityException) { }
        }
        val connection = (URL("https://ipwho.is/").openConnection() as HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 10000; requestMethod = "GET" }
        try {
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (!json.optBoolean("success", false)) throw IllegalStateException("IP location unavailable")
            val city = json.optString("city", "").ifBlank { json.optString("region", tr("Ваш регион", "Your region")) }
            return WeatherLocation(json.getDouble("latitude"), json.getDouble("longitude"), city)
        } finally { connection.disconnect() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationPermissionRequest) loadWeather(true)
    }

    private fun fetchMetWeather(location: WeatherLocation): WeatherInfo {
        val connection = (URL("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=" + location.latitude + "&lon=" + location.longitude).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 10000; requestMethod = "GET"
            setRequestProperty("User-Agent", "ProjectorHome/1.0 (https://github.com/andrytsunami/C50-launcher)")
        }
        try {
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val first = json.getJSONObject("properties").getJSONArray("timeseries").getJSONObject(0)
            val details = first.getJSONObject("data").getJSONObject("instant").getJSONObject("details")
            val temp = details.getDouble("air_temperature").toInt()
            val wind = details.optDouble("wind_speed", 0.0).toInt()
            val humidity = details.optDouble("relative_humidity", 0.0).toInt()
            val symbol = first.getJSONObject("data").optJSONObject("next_1_hours")
                ?.optJSONObject("summary")?.optString("symbol_code", "") ?: ""
            val icon = weatherIconFor(symbol, null)
            val text = location.city + "   " + signedTemp(temp) + "°C\n" + weatherDescription(symbol) +
                "  •  " + tr("Ветер", "Wind") + " " + wind + " " + tr("м/с", "m/s") + "\n" + tr("Влажность", "Humidity") + " " + humidity + "%"
            return WeatherInfo(text, icon)
        } finally { connection.disconnect() }
    }

    private fun fetchOpenMeteoWeather(location: WeatherLocation): WeatherInfo {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=" + location.latitude + "&longitude=" + location.longitude + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,is_day&timezone=auto"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 10000; requestMethod = "GET"
        }
        try {
            val current = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
            val temp = current.getDouble("temperature_2m").toInt()
            val feels = current.getDouble("apparent_temperature").toInt()
            val wind = current.getDouble("wind_speed_10m").toInt()
            val humidity = current.getInt("relative_humidity_2m")
            val code = current.getInt("weather_code")
            val desc = when (code) {
                0 -> tr("Ясно", "Clear"); 1, 2 -> tr("Переменная облачность", "Partly cloudy"); 3 -> tr("Пасмурно", "Overcast")
                45, 48 -> tr("Туман", "Fog"); 51, 53, 55 -> tr("Морось", "Drizzle"); 56, 57 -> tr("Ледяная морось", "Freezing drizzle")
                61, 63, 65 -> tr("Дождь", "Rain"); 66, 67 -> tr("Ледяной дождь", "Freezing rain"); 71, 73, 75, 77, 85, 86 -> tr("Снег", "Snow")
                80, 81, 82 -> tr("Ливень", "Rain showers"); 95, 96, 99 -> tr("Гроза", "Thunderstorm"); else -> tr("Погодные условия", "Weather conditions")
            }
            val isDay = current.optInt("is_day", 1) == 1
            val icon = weatherIconForCode(code, isDay)
            val text = location.city + "   " + signedTemp(temp) + "°C\n" + desc + "  •  " + tr("Ощущается", "Feels like") + " " + feels +
                "°C\n" + tr("Ветер", "Wind") + " " + wind + " " + tr("м/с", "m/s") + "  •  " + tr("Влажность", "Humidity") + " " + humidity + "%"
            return WeatherInfo(text, icon)
        } finally { connection.disconnect() }
    }

    private fun weatherIconFor(symbol: String, isDayOverride: Boolean?): String {
        val s = symbol.lowercase(Locale.US)
        val day = isDayOverride ?: !s.contains("night") && !s.contains("polartwilight")
        return when {
            s.contains("thunder") -> "⛈️"
            s.contains("snow") || s.contains("sleet") -> "🌨️"
            s.contains("rain") -> if (s.contains("showers")) "🌦️" else "🌧️"
            s.contains("fog") -> "🌫️"
            s.contains("partlycloudy") -> if (day) "🌤️" else "☁️"
            s.contains("cloudy") -> "☁️"
            s.contains("clearsky") || s.contains("fair") -> if (day) "☀️" else "🌙"
            else -> if (day) "🌤️" else "🌙"
        }
    }

    private fun weatherIconForCode(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1, 2 -> if (isDay) "🌤️" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌧️"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "🌨️"
        95, 96, 99 -> "⛈️"
        else -> if (isDay) "🌤️" else "🌙"
    }

    private fun signedTemp(value: Int) = if (value > 0) "+$value" else value.toString()

    private fun weatherDescription(symbol: String): String {
        val s = symbol.lowercase(Locale.US)
        return when {
            s.contains("thunder") -> tr("Гроза", "Thunderstorm"); s.contains("snow") -> tr("Снег", "Snow")
            s.contains("sleet") -> tr("Мокрый снег", "Sleet"); s.contains("rain") -> tr("Дождь", "Rain")
            s.contains("fog") -> tr("Туман", "Fog"); s.contains("partlycloudy") -> tr("Переменная облачность", "Partly cloudy")
            s.contains("cloudy") -> tr("Облачно", "Cloudy"); s.contains("fair") -> tr("Переменная облачность", "Partly cloudy")
            s.contains("clearsky") -> tr("Ясно", "Clear")
            else -> tr("Погодные условия", "Weather conditions")
        }
    }

    private fun updateNetwork() {
        if (!::wifiIcon.isInitialized) return
        val wifi = try {
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            manager.isWifiEnabled && manager.connectionInfo?.supplicantState == android.net.wifi.SupplicantState.COMPLETED
        } catch (_: Exception) { false }
        val bluetooth = try { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true } catch (_: Exception) { false }
        wifiIcon.setImageDrawable(drawIcon("wifi", if (wifi) 0xFF38D9FF.toInt() else Color.WHITE))
        btIcon.setImageDrawable(drawIcon("bluetooth", if (bluetooth) 0xFF38D9FF.toInt() else Color.WHITE))
        wifiIcon.alpha = 1f
        btIcon.alpha = 1f
    }

    private fun pickerBackground(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(if (focused) 0xFF0756A6.toInt() else 0x55202C50)
        setStroke(dp(if (focused) 3 else 1), if (focused) 0xFFFFD45C.toInt() else 0x99A9DFFF.toInt())
    }

    private fun rounded(color: Int, radius: Int, stroke: Int, strokeWidth: Int) =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat()
            if (strokeWidth > 0) setStroke(dp(strokeWidth), stroke)
        }

    private fun tileBackground(focused: Boolean, large: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(if (large) 13 else 9).toFloat()
        setColor(if (focused) 0x5536C9E8 else if (large) 0x66142C55 else 0x99202B49.toInt())
        setStroke(dp(if (focused) 2 else 1), if (focused) 0xCCFFE36A.toInt() else 0x667FCBFF)
    }

    private fun openClockApp() {
        val intents = listOf(
            Intent("android.intent.action.SHOW_ALARMS"),
            Intent("android.intent.action.SET_ALARM")
        )
        for (intent in intents) {
            try {
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    return
                }
            } catch (_: Exception) { }
        }
        val clockApp = apps.firstOrNull {
            val label = it.label.lowercase(Locale("ru"))
            val pkg = it.pkg.lowercase(Locale.US)
            label.contains("часы") || label == "clock" ||
                pkg.contains("deskclock") || pkg.contains("clock")
        }
        if (clockApp != null) {
            launch(clockApp.pkg)
        } else {
            Toast.makeText(this, tr("Приложение часов не найдено", "Clock app not found"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun launch(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
