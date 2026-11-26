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
    private lateinit var tomorrowText: TextView
    private lateinit var tomorrowIcon: TextView
    private lateinit var wifiIcon: ImageView
    private lateinit var btIcon: ImageView
    private lateinit var wifiIndicator: TextView
    private lateinit var btIndicator: TextView
    private var apps: List<AppEntry> = emptyList()
    private var weatherLoading = false

    data class AppEntry(val label: String, val icon: Drawable, val pkg: String)
    data class WeatherInfo(val text: String, val icon: String, val tomorrowText: String = "", val tomorrowIcon: String = "🌤️")
    data class WeatherLocation(val latitude: Double, val longitude: Double, val city: String)
    private val locationPermissionRequest = 4102

    private fun tr(ru: String, en: String): String = if (Locale.getDefault().language == "ru") ru else en

    private val clockTick = object : Runnable {
        override fun run() {
            if (!::clock.isInitialized) return
            val now = Date()
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            date.text = SimpleDateFormat("EEEE\nd MMMM", Locale.getDefault()).format(now).uppercase(Locale.getDefault())
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
            contentDescription = tr("Ночной мост через Туру в Тюмени", "Night bridge over the Tura River in Tyumen")
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

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val timeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        clock = TextView(this).apply {
            text = "00:00"
            textSize = 58f
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            setTextColor(0xFFF3F1FF.toInt())
            gravity = Gravity.CENTER
            setShadowLayer(dp(5).toFloat(), 0f, 0f, 0x665A4B9B)
        }
        date = TextView(this).apply {
            textSize = 17f
            setTextColor(0xFFF3F1FF.toInt())
            setPadding(dp(15), 0, 0, 0)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        timeRow.isClickable = true
        timeRow.isFocusable = true
        timeRow.contentDescription = tr("Открыть приложение Часы", "Open Clock app")
        timeRow.setOnClickListener { openClockApp() }
        timeRow.addView(clock)
        timeRow.addView(View(this).apply { setBackgroundColor(0xCCFFFFFF.toInt()) },
            LinearLayout.LayoutParams(dp(1), dp(54)).apply { leftMargin = dp(17) })
        timeRow.addView(date)
        clock.setOnClickListener { openClockApp() }
        date.setOnClickListener { openClockApp() }
        top.addView(timeRow, LinearLayout.LayoutParams(0, -2, 1f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        wifiIcon = ImageView(this)
        wifiIndicator = TextView(this)
        right.addView(statusPill("wifi", tr("Wi-Fi", "Wi-Fi")), quickPillParams())
        btIcon = ImageView(this)
        btIndicator = TextView(this)
        right.addView(statusPill("bluetooth", tr("Bluetooth", "Bluetooth")), quickPillParams())
        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(0x88332B69.toInt(), 24, 0x44FFFFFF, 1)
            setPadding(dp(16), dp(10), dp(18), dp(10))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (focused) 0xCC5145A0.toInt() else 0x88332B69.toInt(),
                    24, if (focused) 0xCCFFFFFF.toInt() else 0x44FFFFFF, 1)
                v.scaleX = if (focused) 1.04f else 1f
                v.scaleY = if (focused) 1.04f else 1f
            }
            addView(ImageView(this@MainActivity).apply {
                setImageDrawable(drawIcon("gear", 0xFFF5F2FF.toInt()))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(25), dp(25)))
            addView(TextView(this@MainActivity).apply {
                text = tr("Настройки", "Settings")
                textSize = 17f
                setTextColor(0xFFF5F2FF.toInt())
                typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
                setPadding(dp(10), 0, 0, 0)
            })
            setOnClickListener { showMenu() }
        }
        right.addView(settings, LinearLayout.LayoutParams(-2, dp(52)).apply { leftMargin = dp(8) })
        top.addView(right)
        page.addView(top, LinearLayout.LayoutParams(-1, dp(70)))

        val dashboard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(10), 0, 0)
        }
        val weather = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(0x77332B69, 24, 0x44FFFFFF, 1)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            isFocusable = true
            isClickable = true
            contentDescription = tr("Обновить прогноз погоды", "Refresh weather forecast")
            setOnClickListener { loadWeather(true) }
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (focused) 0x995448A0.toInt() else 0x77332B69,
                    24, if (focused) 0xCCFFFFFF.toInt() else 0x44FFFFFF, 1)
            }
        }
        weather.addView(TextView(this).apply {
            text = tr("СЕГОДНЯ", "TODAY")
            textSize = 17f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setTextColor(0xFFF0EFFF.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        }, LinearLayout.LayoutParams(-1, dp(27)))
        val currentWeatherRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        weatherIcon = TextView(this).apply {
            text = prefs.getString("weather_icon", "☁️") ?: "☁️"
            textSize = 43f
            gravity = Gravity.CENTER
        }
        currentWeatherRow.addView(weatherIcon, LinearLayout.LayoutParams(dp(66), dp(62)))
        weatherText = TextView(this).apply {
            text = tr("Загрузка…", "Loading…")
            textSize = 23f
            setTextColor(0xFF8FEAFF.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
            maxLines = 2
        }
        currentWeatherRow.addView(weatherText, LinearLayout.LayoutParams(-2, -2))
        weather.addView(currentWeatherRow, LinearLayout.LayoutParams(-1, dp(66)))
        val tomorrowRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        tomorrowRow.addView(TextView(this@MainActivity).apply {
            text = tr("ЗАВТРА", "TOMORROW")
            textSize = 13f
            setTextColor(0xFFD9D3F0.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        })
        tomorrowIcon = TextView(this).apply {
            text = prefs.getString("weather_tomorrow_icon", "🌧️") ?: "🌧️"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(dp(9), 0, dp(4), 0)
        }
        tomorrowRow.addView(tomorrowIcon)
        tomorrowText = TextView(this).apply {
            text = "—"
            textSize = 17f
            setTextColor(0xFFF5F2FF.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        }
        tomorrowRow.addView(tomorrowText)
        weather.addView(tomorrowRow, LinearLayout.LayoutParams(-1, dp(34)))
        dashboard.addView(weather, LinearLayout.LayoutParams(dp(300), dp(190)))

        val calendarCard = buildCalendarCard()
        dashboard.addView(calendarCard, LinearLayout.LayoutParams(0, dp(190), 1f).apply {
            leftMargin = dp(18)
        })
        content.addView(dashboard, LinearLayout.LayoutParams(-1, dp(210)))
        page.addView(pageScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            background = rounded(0xAA17142F.toInt(), 22, 0x44FFFFFF, 1)
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
        val dockWidth = minOf((resources.displayMetrics.widthPixels * 0.82f).toInt(), dp(650))
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
            spacerParams.height = (pageScroll.height - dashboard.height - dockContainer.height).coerceAtLeast(0)
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

    private fun quickPillParams() = LinearLayout.LayoutParams(-2, dp(52)).apply { rightMargin = dp(8) }

    private fun statusPill(kind: String, label: String): View {
        val icon = if (kind == "wifi") wifiIcon else btIcon
        val indicator = if (kind == "wifi") wifiIndicator else btIndicator
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(0x88332B69.toInt(), 24, 0x44FFFFFF, 1)
            setPadding(dp(14), dp(8), dp(15), dp(8))
            isFocusable = true
            isClickable = true
            addView(icon, LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 16f
                setTextColor(0xFFF5F2FF.toInt())
                typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
                setPadding(dp(8), 0, dp(8), 0)
            })
            addView(indicator, LinearLayout.LayoutParams(dp(12), dp(12)))
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (focused) 0xCC5145A0.toInt() else 0x88332B69.toInt(),
                    24, if (focused) 0xCCFFFFFF.toInt() else 0x44FFFFFF, 1)
            }
            setOnClickListener {
                val action = if (kind == "wifi") Settings.ACTION_WIFI_SETTINGS else Settings.ACTION_BLUETOOTH_SETTINGS
                try { startActivity(Intent(action)) } catch (_: Exception) {
                    try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { }
                }
            }
        }
    }

    private fun buildCalendarCard(): View {
        val now = java.util.Calendar.getInstance()
        val monthTitle = SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(now.time)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0x77332B69, 24, 0x44FFFFFF, 1)
            setPadding(dp(18), dp(12), dp(18), dp(10))
        }
        card.addView(TextView(this).apply {
            text = monthTitle.uppercase(Locale.getDefault())
            textSize = 15f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTextColor(0xFFF0EFFF.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        }, LinearLayout.LayoutParams(-1, dp(25)))
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 7
            rowCount = 7
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        val weekdays = if (Locale.getDefault().language == "ru") arrayOf("П", "В", "С", "Ч", "П", "С", "В") else arrayOf("M", "T", "W", "T", "F", "S", "S")
        weekdays.forEach { day ->
            grid.addView(TextView(this).apply {
                text = day
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(0xFFC9C2E5.toInt())
            }, android.widget.GridLayout.LayoutParams().apply {
                width = 0; height = dp(20)
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            })
        }
        val calendar = now.clone() as java.util.Calendar
        calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
        val offset = (calendar.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
        val days = calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        for (cell in 0 until 42) {
            val dayNumber = cell - offset + 1
            val isCurrent = dayNumber in 1..days
            val isToday = isCurrent && dayNumber == now.get(java.util.Calendar.DAY_OF_MONTH)
            val dayView = TextView(this).apply {
                text = if (isCurrent) dayNumber.toString() else ""
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (isToday) Color.WHITE else 0xFFE7E2F7.toInt())
                if (isToday) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xFF6558B8.toInt())
                    }
                }
            }
            grid.addView(dayView, android.widget.GridLayout.LayoutParams().apply {
                width = 0; height = dp(17)
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            })
        }
        card.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        return card
    }

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
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
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
            addView(TextView(this@MainActivity).apply {
                text = tr("Версия $versionName", "Version $versionName")
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(0xFF9EACBF.toInt())
                setPadding(0, dp(6), 0, dp(2))
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
        val names = arrayOf(tr("Тюмень", "Tyumen"), tr("Закат в горах", "Mountain sunset"), tr("Космос", "Space"), tr("Шри-Ланка", "Sri Lanka"), "A M — " + tr("электричество", "Electricity"), tr("Мягкие волны", "Soft waves"))
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
        if (manual) weatherText.text = tr("Загрузка…", "Loading…")
        Thread {
            var result: WeatherInfo? = null
            try {
                val loc = resolveWeatherLocation()
                prefs.edit().putString("weather_city", loc.city).putFloat("weather_latitude", loc.latitude.toFloat()).putFloat("weather_longitude", loc.longitude.toFloat()).apply()
                result = fetchOpenMeteoWeather(loc)
            } catch (_: Exception) { }
            val value = result
            handler.post {
                weatherLoading = false
                if (!::weatherText.isInitialized) return@post
                if (value != null) {
                    weatherText.text = value.text
                    weatherIcon.text = value.icon
                    tomorrowText.text = value.tomorrowText
                    tomorrowIcon.text = value.tomorrowIcon
                    prefs.edit().putString("weather_text", value.text)
                        .putString("weather_icon", value.icon)
                        .putString("weather_tomorrow_text", value.tomorrowText)
                        .putString("weather_tomorrow_icon", value.tomorrowIcon).apply()
                } else {
                    weatherText.text = tr("Нет данных", "No data")
                    tomorrowText.text = prefs.getString("weather_tomorrow_text", "—") ?: "—"
                    tomorrowIcon.text = prefs.getString("weather_tomorrow_icon", "🌧️") ?: "🌧️"
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
            val text = signedTemp(temp) + "°\n" + weatherDescription(symbol)
            return WeatherInfo(text, icon)
        } finally { connection.disconnect() }
    }

    private fun fetchOpenMeteoWeather(location: WeatherLocation): WeatherInfo {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=" + location.latitude + "&longitude=" + location.longitude + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,is_day&daily=weather_code,temperature_2m_min,temperature_2m_max&forecast_days=2&timezone=auto"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 10000; requestMethod = "GET"
        }
        try {
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val current = response.getJSONObject("current")
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
            val daily = response.optJSONObject("daily")
            val tomorrowCode = daily?.optJSONArray("weather_code")?.optInt(1, 3) ?: 3
            val tomorrowMin = daily?.optJSONArray("temperature_2m_min")?.optDouble(1, Double.NaN) ?: Double.NaN
            val tomorrowMax = daily?.optJSONArray("temperature_2m_max")?.optDouble(1, Double.NaN) ?: Double.NaN
            val tomorrowRange = if (!tomorrowMin.isNaN() && !tomorrowMax.isNaN())
                signedTemp(tomorrowMin.toInt()) + "° — " + signedTemp(tomorrowMax.toInt()) + "°" else "—"
            val tomorrowWeatherIcon = weatherIconForCode(tomorrowCode, true)
            val text = signedTemp(temp) + "°\n" + desc
            return WeatherInfo(text, icon, tomorrowRange, tomorrowWeatherIcon)
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
        wifiIcon.setImageDrawable(drawIcon("wifi", if (wifi) 0xFF8FEAFF.toInt() else Color.WHITE))
        btIcon.setImageDrawable(drawIcon("bluetooth", if (bluetooth) 0xFF8FEAFF.toInt() else Color.WHITE))
        wifiIndicator.text = "●"
        btIndicator.text = "●"
        wifiIndicator.setTextColor(if (wifi) 0xFF37E7A5.toInt() else 0xFF9A94B8.toInt())
        btIndicator.setTextColor(if (bluetooth) 0xFF37E7A5.toInt() else 0xFF9A94B8.toInt())
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
        setColor(if (focused) 0x88564B9B.toInt() else if (large) 0xAA211C3D.toInt() else 0x99211D38.toInt())
        setStroke(dp(if (focused) 2 else 1), if (focused) 0xCCFFFFFF.toInt() else 0x55FFFFFF)
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
            label.contains("часы") || label.contains("clock") ||
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