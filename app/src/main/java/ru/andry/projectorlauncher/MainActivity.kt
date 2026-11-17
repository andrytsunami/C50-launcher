package ru.andry.projectorlauncher

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
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
    private lateinit var wifiIcon: ImageView
    private lateinit var btIcon: ImageView
    private var apps: List<AppEntry> = emptyList()
    private var weatherLoading = false

    data class AppEntry(val label: String, val icon: Drawable, val pkg: String)

    private val clockTick = object : Runnable {
        override fun run() {
            if (!::clock.isInitialized) return
            val now = Date()
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            date.text = SimpleDateFormat("EEEE\nd MMMM", Locale("ru")).format(now)
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

    private fun favorites(): MutableList<String> {
        val saved = prefs.getString("favorites", null)
        if (saved != null) return saved.split("|").filter { it.isNotBlank() }.take(4).toMutableList()
        return apps.take(4).map { it.pkg }.toMutableList()
    }

    private fun buildHome() {
        val root = FrameLayout(this)
        val wallpaper = ImageView(this).apply {
            setImageResource(R.drawable.tyumen_bridge_wallpaper)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Ночной мост через Туру в Тюмени"
        }
        root.addView(wallpaper, FrameLayout.LayoutParams(-1, -1))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(18), dp(28), dp(8))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

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
        timeRow.addView(clock)
        timeRow.addView(View(this).apply { setBackgroundColor(0xCCFFFFFF.toInt()) },
            LinearLayout.LayoutParams(dp(1), dp(48)).apply { leftMargin = dp(15) })
        timeRow.addView(date)
        left.addView(timeRow)

        val weather = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(0x77202C50, 14, 0x55FFFFFF, 1)
            setPadding(dp(10), dp(6), dp(14), dp(6))
            isFocusable = true; isClickable = true
            contentDescription = "Обновить погоду нажатием"
            setOnClickListener { loadWeather(true) }
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (focused) 0xAA0756A6.toInt() else 0x77202C50,
                    14, if (focused) 0xFF64D9FF.toInt() else 0x55FFFFFF, if (focused) 2 else 1)
            }
        }
        weather.addView(TextView(this).apply {
            text = "☀"; textSize = 35f; setTextColor(0xFFFFD45C.toInt()); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(52), dp(48)))
        weatherText = TextView(this).apply {
            text = prefs.getString("weather_text", "Тюмень  •  Нажмите для обновления") ?: "Тюмень"
            textSize = 16f; setTextColor(Color.WHITE); setPadding(dp(10), 0, 0, 0); maxLines = 3
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
            setImageDrawable(drawIcon("gear", Color.WHITE)); contentDescription = "Настройки"
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(0x88202C50.toInt(), 17, 0x66FFFFFF, 1)
            isFocusable = true; isClickable = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (focused) 0xEE0756A6.toInt() else 0x88202C50,
                    17, if (focused) 0xFF54D8FF.toInt() else 0x66FFFFFF, if (focused) 3 else 1)
                v.scaleX = if (focused) 1.06f else 1f; v.scaleY = if (focused) 1.06f else 1f
            }
            setOnClickListener { showSettings() }
        }
        right.addView(settings, LinearLayout.LayoutParams(dp(54), dp(54)).apply { leftMargin = dp(10) })
        top.addView(right)
        content.addView(top)

        content.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = rounded(0xDD09152F.toInt(), 25, 0x778FCBFF, 1)
            setPadding(dp(9), dp(8), dp(9), dp(8)); elevation = dp(12).toFloat()
        }
        val selected = favorites()
        for (slot in 0..3) {
            val app = apps.firstOrNull { it.pkg == selected.getOrNull(slot) }
            dock.addView(appTile(app, true) {
                if (app != null) launch(app.pkg) else chooseDockApp(slot)
            }, LinearLayout.LayoutParams(0, dp(98), 1f).apply {
                leftMargin = dp(5); rightMargin = dp(5)
            })
        }
        val dockWidth = (resources.displayMetrics.widthPixels * 0.67f).toInt()
        content.addView(dock, LinearLayout.LayoutParams(dockWidth, dp(114)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = rounded(0xDD09152F.toInt(), 22, 0x668FCBFF, 1)
            setPadding(dp(5), dp(5), dp(5), dp(5)); elevation = dp(10).toFloat()
        }
        val actions = listOf(
            Triple("music", "Музыка", { launchByKeywords(listOf("музык", "music")) }),
            Triple("gallery", "Галерея", { launchByKeywords(listOf("галере", "фото", "gallery")) }),
            Triple("movie", "Кино", { launchByKeywords(listOf("кино", "видео", "movie", "video")) }),
            Triple("browser", "Браузер", { launchByKeywords(listOf("брауз", "browser")) }),
            Triple("gear", "Настройки", { showSettings() }),
            Triple("apps", "Все приложения", { showAllApps() }),
            Triple("edit", "Настроить", { showSettings() })
        )
        actions.forEach { item ->
            bottom.addView(bottomAction(item.first, item.second, item.third),
                LinearLayout.LayoutParams(0, dp(72), 1f))
        }
        content.addView(bottom, LinearLayout.LayoutParams(-1, dp(82)).apply { topMargin = dp(10) })
        content.addView(TextView(this).apply {
            text = "⌄"; textSize = 25f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(-1, dp(20)))

        setContentView(root)
        clockTick.run()
        updateNetwork()
        dock.getChildAt(0)?.requestFocus()
    }

    private fun appTile(app: AppEntry?, large: Boolean, action: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            background = tileBackground(false, large)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            setOnFocusChangeListener { v, focused ->
                v.background = tileBackground(focused, large)
                v.scaleX = if (focused) 1.035f else 1f; v.scaleY = if (focused) 1.035f else 1f
            }
            setOnClickListener { action() }
            if (app != null) {
                val frame = FrameLayout(this@MainActivity).apply {
                    background = rounded(0x332D4774, 13, 0x338FCBFF, 1)
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                }
                frame.addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(app.icon); scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                }, FrameLayout.LayoutParams(-1, -1))
                addView(frame, LinearLayout.LayoutParams(dp(if (large) 58 else 38), dp(if (large) 58 else 38)))
                addView(TextView(this@MainActivity).apply {
                    text = app.label; textSize = if (large) 14f else 11f
                    gravity = Gravity.CENTER; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.WHITE); setPadding(0, dp(3), 0, 0)
                }, LinearLayout.LayoutParams(-1, dp(21)))
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
        }
        return BitmapDrawable(resources, bitmap)
    }

    private fun chooseDockApp(slot: Int) {
        val current = favorites()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        var firstAction: TextView? = null
        apps.forEach { app ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(4), dp(8), dp(4))
                addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(app.icon); scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(TextView(this@MainActivity).apply {
                    text = app.label; textSize = 15f; setTextColor(Color.WHITE)
                    setPadding(dp(12), 0, 0, 0); maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
                val add = TextView(this@MainActivity).apply {
                    text = if (app.pkg in current) "✓" else "+"
                    textSize = 27f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(if (app.pkg in current) 0xFF75F0B2.toInt() else Color.WHITE)
                    background = pickerBackground(false); isFocusable = true; isFocusableInTouchMode = true
                    contentDescription = if (app.pkg in current) "Уже в доке" else "Добавить в док"
                    setOnFocusChangeListener { v, focused -> v.background = pickerBackground(focused) }
                    setOnClickListener {
                        val updated = favorites()
                        val old = updated.indexOf(app.pkg)
                        if (old >= 0) updated.removeAt(old)
                        while (updated.size <= slot) updated.add("")
                        updated[slot] = app.pkg
                        prefs.edit().putString("favorites", updated.filter { it.isNotBlank() }.distinct().take(4).joinToString("|")).apply()
                        buildHome()
                    }
                }
                if (firstAction == null) firstAction = add
                addView(add, LinearLayout.LayoutParams(dp(48), dp(48)))
            }
            list.addView(row)
            list.addView(View(this).apply { setBackgroundColor(0x447FA8D8) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        scroll.addView(list)
        AlertDialog.Builder(this).setTitle("Приложение для позиции " + (slot + 1))
            .setView(scroll).setNegativeButton("Закрыть", null).create().also { dialog ->
                dialog.setOnShowListener { firstAction?.requestFocus() }; dialog.show()
            }
    }

    private fun showSettings() {
        AlertDialog.Builder(this).setTitle("Настройки ProjectorHome")
            .setItems(arrayOf("Изменить приложение 1 в доке", "Изменить приложение 2 в доке",
                "Изменить приложение 3 в доке", "Изменить приложение 4 в доке", "Сменить обои")) { _, which ->
                if (which < 4) chooseDockApp(which) else chooseWallpaper()
            }.setNegativeButton("Закрыть", null).show()
    }

    private fun showAllApps() {
        AlertDialog.Builder(this).setTitle("Все приложения")
            .setItems(apps.map { it.label }.toTypedArray()) { _, which -> apps.getOrNull(which)?.let { launch(it.pkg) } }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun chooseWallpaper() {
        AlertDialog.Builder(this).setTitle("Обои")
            .setItems(arrayOf("Тюмень — ночной мост")) { _, _ -> buildHome() }
            .setNegativeButton("Отмена", null).show()
    }

    private fun launchByKeywords(words: List<String>) {
        val app = apps.firstOrNull { entry ->
            val label = entry.label.lowercase(Locale("ru"))
            words.any { label.contains(it) }
        }
        if (app != null) launch(app.pkg) else showAllApps()
    }

    private fun loadWeather(manual: Boolean) {
        if (weatherLoading) return
        weatherLoading = true
        if (manual) weatherText.text = "Тюмень  •  Обновляем погоду…"
        Thread {
            var result: String? = null
            try { result = fetchMetWeather() } catch (_: Exception) {
                try { result = fetchOpenMeteoWeather() } catch (_: Exception) { }
            }
            val value = result
            handler.post {
                weatherLoading = false
                if (!::weatherText.isInitialized) return@post
                if (value != null) {
                    weatherText.text = value
                    prefs.edit().putString("weather_text", value).apply()
                } else {
                    val cached = prefs.getString("weather_text", null)
                    weatherText.text = if (cached != null) "$cached\nНе удалось обновить" else "Тюмень  •  Погода недоступна"
                }
            }
        }.start()
    }

    private fun fetchMetWeather(): String {
        val connection = (URL("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=57.1522&lon=65.5272").openConnection() as HttpURLConnection).apply {
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
            return "Тюмень   " + signedTemp(temp) + "°C\n" + weatherDescription(symbol) +
                "  •  Ветер " + wind + " м/с\nВлажность " + humidity + "%  •  MET Norway"
        } finally { connection.disconnect() }
    }

    private fun fetchOpenMeteoWeather(): String {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=57.1522&longitude=65.5272&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m&timezone=Asia%2FYekaterinburg"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 10000; requestMethod = "GET"
        }
        try {
            val current = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
            val temp = current.getDouble("temperature_2m").toInt()
            val feels = current.getDouble("apparent_temperature").toInt()
            val wind = current.getDouble("wind_speed_10m").toInt()
            val humidity = current.getInt("relative_humidity_2m")
            val desc = when (current.getInt("weather_code")) {
                0 -> "Ясно"; 1, 2 -> "Переменная облачность"; 3 -> "Пасмурно"
                45, 48 -> "Туман"; 51, 53, 55 -> "Морось"; 56, 57 -> "Ледяная морось"
                61, 63, 65 -> "Дождь"; 66, 67 -> "Ледяной дождь"; 71, 73, 75, 77, 85, 86 -> "Снег"
                80, 81, 82 -> "Ливень"; 95, 96, 99 -> "Гроза"; else -> "Погодные условия"
            }
            return "Тюмень   " + signedTemp(temp) + "°C\n" + desc + "  •  Ощущается " + feels +
                "°C\nВетер " + wind + " м/с  •  Влажность " + humidity + "%\nИсточник: Open-Meteo"
        } finally { connection.disconnect() }
    }

    private fun signedTemp(value: Int) = if (value > 0) "+$value" else value.toString()

    private fun weatherDescription(symbol: String): String {
        val s = symbol.lowercase(Locale.US)
        return when {
            s.contains("thunder") -> "Гроза"; s.contains("snow") -> "Снег"
            s.contains("sleet") -> "Мокрый снег"; s.contains("rain") -> "Дождь"
            s.contains("fog") -> "Туман"; s.contains("cloudy") -> "Облачно"
            s.contains("fair") -> "Переменная облачность"; s.contains("clearsky") -> "Ясно"
            else -> "Погодные условия"
        }
    }

    private fun updateNetwork() {
        if (!::wifiIcon.isInitialized) return
        val wifi = try {
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            manager.isWifiEnabled && manager.connectionInfo?.supplicantState == android.net.wifi.SupplicantState.COMPLETED
        } catch (_: Exception) { false }
        val bluetooth = try { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true } catch (_: Exception) { false }
        wifiIcon.alpha = if (wifi) 1f else 0.42f
        btIcon.alpha = if (bluetooth) 1f else 0.42f
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
        cornerRadius = dp(if (large) 23 else 17).toFloat()
        setColor(if (focused) 0xEE0756A6.toInt() else if (large) 0xAA142C55.toInt() else 0x99202B49.toInt())
        setStroke(dp(if (focused) 3 else 1), if (focused) 0xFF40D9FF.toInt() else 0x887FCBFF.toInt())
    }

    private fun launch(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
