package ru.andry.projectorlauncher

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var network: TextView
    private lateinit var weatherView: TextView
    private lateinit var root: FrameLayout
    private var apps: List<AppEntry> = emptyList()

    data class AppEntry(val label: String, val icon: android.graphics.drawable.Drawable, val pkg: String)

    private val clockTick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            date.text = SimpleDateFormat("EEEE\nd MMMM", Locale("ru")).format(now)
            updateNetwork()
            handler.postDelayed(this, 30_000)
        }
    }

    private val weatherRefresh = object : Runnable {
        override fun run() {
            loadWeather()
            handler.postDelayed(this, 30 * 60 * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        loadApps()
        buildHome()
    }

    override fun onResume() {
        super.onResume()
        if (::clock.isInitialized) handler.post(clockTick)
        if (::weatherView.isInitialized) handler.post(weatherRefresh)
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

    private fun favorites(): MutableList<String> {
        val saved = prefs.getString("favorites", null)
        if (saved != null) return saved.split("|").filter { it.isNotBlank() }.take(4).toMutableList()
        return apps.take(4).map { it.pkg }.toMutableList()
    }

    private fun buildHome() {
        root = FrameLayout(this)
        val wallpaper = ImageView(this).apply {
            setImageResource(R.drawable.tyumen_bridge_wallpaper)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Ночной мост через Туру в Тюмени"
        }
        root.addView(wallpaper, FrameLayout.LayoutParams(-1, -1))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(34), dp(22), dp(34), dp(18))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val timeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        clock = TextView(this).apply {
            text = "00:00"
            textSize = 52f
            typeface = android.graphics.Typeface.create("sans-serif-light", 0)
            setTextColor(Color.WHITE)
            background = rounded(0xCC101A35.toInt(), 16, 0xAAFFFFFF.toInt(), 1)
            setPadding(dp(15), dp(0), dp(15), dp(4))
            elevation = dp(5).toFloat()
        }
        date = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(18), 0, 0, 0)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        timeRow.addView(clock)
        timeRow.addView(date)
        left.addView(timeRow)

        weatherView = TextView(this).apply {
            text = "ТЮМЕНЬ  •  Загрузка погоды…"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = rounded(0x99202C50.toInt(), 14, 0x66FFFFFF.toInt(), 1)
            setPadding(dp(14), dp(9), dp(18), dp(9))
        }
        left.addView(weatherView, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        top.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        val right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP or Gravity.END }
        network = NetworkIconsView(this).apply {
            contentDescription = "Состояние Wi-Fi и Bluetooth"
            background = rounded(0x88202C50.toInt(), 20, 0x66FFFFFF.toInt(), 1)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val settings = focusButton("⚙") { showSettings() }.apply {
            textSize = 28f
            contentDescription = "Настройки"
            setPadding(dp(17), dp(7), dp(17), dp(7))
        }
        right.addView(network)
        right.addView(settings, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        top.addView(right)
        content.addView(top)

        val spacer = Space(this)
        content.addView(spacer, LinearLayout.LayoutParams(1, 0, 2f))

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(0xDD09152F.toInt(), 24, 0xCC8FCBFF.toInt(), 2)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            elevation = dp(12).toFloat()
        }
        val selected = favorites()
        for (slot in 0..3) {
            val app = apps.firstOrNull { it.pkg == selected.getOrNull(slot) }
            val tile = appTile(app, true) {
                if (app != null) launch(app.pkg) else chooseDockApp(slot)
            }
            dock.addView(tile, LinearLayout.LayoutParams(0, dp(104), 1f).apply {
                leftMargin = dp(5); rightMargin = dp(5)
            })
        }
        content.addView(dock, LinearLayout.LayoutParams(-1, dp(124)))

        val allTitle = TextView(this).apply {
            text = "ВСЕ ПРИЛОЖЕНИЯ  ↓"
            textSize = 14f
            setTextColor(0xFFEAF3FF.toInt())
            setPadding(dp(4), dp(12), 0, dp(5))
        }
        content.addView(allTitle)
        val rest = apps.filter { it.pkg !in selected }
        val grid = GridLayout(this).apply { columnCount = 6; useDefaultMargins = false }
        rest.forEach { app ->
            grid.addView(appTile(app, false) { launch(app.pkg) }, GridLayout.LayoutParams().apply {
                width = 0; height = dp(86)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(3), dp(4), dp(3))
            })
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            isVerticalScrollBarEnabled = false
            addView(grid)
        }
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        clockTick.run()
        loadWeather()
        dock.getChildAt(0)?.requestFocus()
    }

    private fun appTile(app: AppEntry?, large: Boolean, action: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            background = tileBackground(false, large)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            setOnFocusChangeListener { v, focused -> v.background = tileBackground(focused, large) }
            setOnClickListener { action() }
            if (app != null) {
                val icon = ImageView(this@MainActivity).apply {
                    setImageDrawable(app.icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                addView(icon, LinearLayout.LayoutParams(if (large) dp(58) else dp(42), if (large) dp(58) else dp(42)))
                addView(TextView(this@MainActivity).apply {
                    text = app.label
                    textSize = if (large) 15f else 12f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(3), 0, 0)
                }, LinearLayout.LayoutParams(-1, dp(23)))
            } else {
                addView(TextView(this@MainActivity).apply {
                    text = "+"
                    textSize = 40f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                })
            }
        }
    }

    private fun chooseDockApp(slot: Int) {
        val chosen = favorites()
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        apps.forEach { app ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(5), dp(10), dp(5))
                val icon = ImageView(this@MainActivity).apply { setImageDrawable(app.icon) }
                addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
                addView(TextView(this@MainActivity).apply {
                    text = app.label; textSize = 16f; setTextColor(Color.WHITE)
                    setPadding(dp(12), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(TextView(this@MainActivity).apply {
                    text = if (app.pkg in chosen) "✓" else "+"
                    textSize = 28f
                    setTextColor(0xFF75D8FF.toInt())
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        val current = favorites()
                        val old = current.indexOf(app.pkg)
                        if (old >= 0) current.removeAt(old)
                        while (current.size <= slot) current.add("")
                        current[slot] = app.pkg
                        val compact = current.filter { it.isNotBlank() }.distinct().take(4)
                        prefs.edit().putString("favorites", compact.joinToString("|")).apply()
                        buildHome()
                    }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            }
            list.addView(row)
            val line = View(this).apply { setBackgroundColor(0x447FA8D8) }
            list.addView(line, LinearLayout.LayoutParams(-1, dp(1)))
        }
        scroll.addView(list)
        rows.addView(scroll)
        AlertDialog.Builder(this).setTitle("Добавить приложение в док")
            .setView(rows).setNegativeButton("Закрыть", null).show()
    }

    private fun showSettings() {
        AlertDialog.Builder(this).setTitle("Настройки рабочего стола")
            .setItems(arrayOf("Настроить 4 приложения в доке", "Сменить обои")) { _, which ->
                if (which == 0) chooseDockApp(0) else chooseWallpaper()
            }.setNegativeButton("Закрыть", null).show()
    }

    private fun chooseWallpaper() {
        AlertDialog.Builder(this).setTitle("Обои")
            .setItems(arrayOf("Тюмень — ночной мост")) { _, index ->
                prefs.edit().putInt("wallpaper", index).apply()
                buildHome()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun loadWeather() {
        Thread {
            try {
                val url = java.net.URL("https://api.open-meteo.com/v1/forecast?latitude=57.1522&longitude=65.5272&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m&timezone=Asia%2FYekaterinburg")
                val connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "GET"
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val current = org.json.JSONObject(body).getJSONObject("current")
                val temp = current.getDouble("temperature_2m").toInt()
                val feels = current.getDouble("apparent_temperature").toInt()
                val wind = current.getDouble("wind_speed_10m").toInt()
                val humidity = current.getInt("relative_humidity_2m")
                val code = current.getInt("weather_code")
                val description = when (code) {
                    0 -> "Ясно"
                    1, 2 -> "Переменная облачность"
                    3 -> "Пасмурно"
                    45, 48 -> "Туман"
                    51, 53, 55 -> "Морось"
                    56, 57 -> "Ледяная морось"
                    61, 63, 65 -> "Дождь"
                    66, 67 -> "Ледяной дождь"
                    71, 73, 75, 77, 85, 86 -> "Снег"
                    80, 81, 82 -> "Ливень"
                    95, 96, 99 -> "Гроза"
                    else -> "Погодные условия"
                }
                handler.post {
                    if (::weatherView.isInitialized) {
                        weatherView.text = "ТЮМЕНЬ   $temp°C\n$description  •  Ощущается $feels°C\nВетер $wind м/с  •  Влажность $humidity%\nИсточник: Open-Meteo"
                    }
                }
            } catch (_: Exception) {
                handler.post {
                    if (::weatherView.isInitialized) weatherView.text = "ТЮМЕНЬ  •  Погода временно недоступна"
                }
            }
        }.start()
    }

    private fun updateNetwork() {
        val wifi = try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wm.isWifiEnabled) "Wi‑Fi ●" else "Wi‑Fi ○"
        } catch (_: Exception) { "Wi‑Fi ?" }
        val bt = try {
            if (BluetoothAdapter.getDefaultAdapter()?.isEnabled == true) "  BT ●" else "  BT ○"
        } catch (_: Exception) { "  BT ?" }
        network.text = "$wifi$bt"
    }

    private fun focusButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = rounded(0xAA142B55.toInt(), 18, 0xAAFFFFFF.toInt(), 1)
        isFocusable = true
        setOnFocusChangeListener { v, focused ->
            v.background = rounded(if (focused) 0xEE0756A6.toInt() else 0xAA142B55.toInt(), 18,
                if (focused) 0xFF54D8FF.toInt() else 0xAAFFFFFF.toInt(), if (focused) 3 else 1)
        }
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int, strokeWidth: Int) =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat()
            setStroke(dp(strokeWidth), stroke)
        }

    private fun tileBackground(focused: Boolean, large: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(if (large) 19 else 14).toFloat()
        setColor(if (focused) 0xEE0B4F9A.toInt() else if (large) 0xAA142C55.toInt() else 0x99202B49.toInt())
        setStroke(dp(if (focused) 3 else 1), if (focused) 0xFF40D9FF.toInt() else 0x887FCBFF.toInt())
    }

    private fun launch(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

}
