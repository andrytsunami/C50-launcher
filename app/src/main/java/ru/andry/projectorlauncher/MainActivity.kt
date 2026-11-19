package ru.andry.projectorlauncher

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
            val wallpaperRes = when (prefs.getString("wallpaper", "pixel")) {
                "pixel" -> R.drawable.wallpaper_pixel_sunset
                "space" -> R.drawable.wallpaper_space
                "srilanka" -> R.drawable.wallpaper_sri_lanka
                else -> R.drawable.tyumen_bridge_wallpaper
            }
            setImageBitmap(loadPortraitWallpaper(wallpaperRes))
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Обои PhoneHome, портретный формат 9:16"
        }
        root.addView(wallpaper, FrameLayout.LayoutParams(-1, -1))

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val timeBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        clock = TextView(this).apply {
            text = "00:00"; textSize = 42f
            typeface = android.graphics.Typeface.create("sans-serif-light", 0)
            setTextColor(Color.WHITE)
        }
        date = TextView(this).apply {
            textSize = 13f; setTextColor(0xFFE2E9F5.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        }
        timeBlock.addView(clock)
        timeBlock.addView(date)
        header.addView(timeBlock, LinearLayout.LayoutParams(0, -2, 1f))

        val settings = ImageView(this).apply {
            setImageDrawable(drawIcon("menu", Color.WHITE))
            contentDescription = "Меню PhoneHome"
            background = rounded(0x88202C50.toInt(), 18, 0x66FFFFFF, 1)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            isClickable = true
            setOnClickListener { showMenu() }
        }
        header.addView(settings, LinearLayout.LayoutParams(dp(48), dp(48)))
        page.addView(header, LinearLayout.LayoutParams(-1, -2))

        val weather = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0x88202C50.toInt(), 18, 0x55FFFFFF, 1)
            setPadding(dp(12), dp(9), dp(14), dp(9))
            isClickable = true
            contentDescription = "Обновить погоду"
            setOnClickListener { loadWeather(true) }
        }
        weatherIcon = TextView(this).apply {
            text = prefs.getString("weather_icon", "🌙") ?: "🌙"
            textSize = 30f; gravity = Gravity.CENTER
        }
        weather.addView(weatherIcon, LinearLayout.LayoutParams(dp(48), dp(48)))
        weatherText = TextView(this).apply {
            text = prefs.getString("weather_text", "Тюмень • Нажмите для обновления") ?: "Тюмень"
            textSize = 13f; setTextColor(Color.WHITE); setPadding(dp(8), 0, 0, 0); maxLines = 3
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        }
        weather.addView(weatherText, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(weather, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        val foldersScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
        }
        val foldersGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, dp(12))
        }
        val ordered = orderedFolders()
        ordered.chunked(2).forEach { rowFolders ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            rowFolders.forEach { folder ->
                row.addView(folderTile(folder) { showFolder(folder) },
                    LinearLayout.LayoutParams(0, dp(112), 1f).apply {
                        leftMargin = dp(4); rightMargin = dp(4); topMargin = dp(5); bottomMargin = dp(5)
                    })
            }
            if (rowFolders.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(112), 1f))
            foldersGrid.addView(row, LinearLayout.LayoutParams(-1, dp(122)))
        }
        foldersScroll.addView(foldersGrid, ViewGroup.LayoutParams(-1, -2))
        page.addView(foldersScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(0xCC09152F.toInt(), 24, 0x668FCBFF, 1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            elevation = dp(8).toFloat()
        }
        val selected = favorites()
        for (slot in 0..4) {
            val app = apps.firstOrNull { it.pkg == selected.getOrNull(slot) }
            dock.addView(phoneDockTile(app) {
                if (app != null) launch(app.pkg) else chooseDockApps()
            }, LinearLayout.LayoutParams(0, dp(66), 1f).apply {
                leftMargin = dp(3); rightMargin = dp(3)
            })
        }
        page.addView(dock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        clockTick.run()
        updateNetwork()
    }

    private data class AppFolder(val id: String, val title: String, val icon: String, val apps: List<AppEntry>)

    private fun orderedFolders(): List<AppFolder> {
        val buckets = linkedMapOf(
            "communication" to mutableListOf<AppEntry>(),
            "media" to mutableListOf<AppEntry>(),
            "work" to mutableListOf<AppEntry>(),
            "tools" to mutableListOf<AppEntry>(),
            "games" to mutableListOf<AppEntry>(),
            "other" to mutableListOf<AppEntry>()
        )
        apps.forEach { app ->
            val label = app.label.lowercase(Locale("ru"))
            val pkg = app.pkg.lowercase(Locale.US)
            val category = try { packageManager.getApplicationInfo(app.pkg, 0).category } catch (_: Exception) { -1 }
            val id = when {
                category == android.content.pm.ApplicationInfo.CATEGORY_GAME ||
                    listOf("game", "игр").any { label.contains(it) || pkg.contains(it) } -> "games"
                listOf("telegram", "whatsapp", "viber", "messenger", "сообщ", "звон", "контакт", "vk", "discord")
                    .any { label.contains(it) || pkg.contains(it) } -> "communication"
                listOf("youtube", "видео", "video", "music", "музык", "player", "плеер", "gallery", "галере", "фото", "camera", "камера", "spotify")
                    .any { label.contains(it) || pkg.contains(it) } -> "media"
                listOf("office", "word", "excel", "docs", "документ", "почт", "gmail", "outlook", "calendar", "календар", "drive", "диск")
                    .any { label.contains(it) || pkg.contains(it) } -> "work"
                listOf("настрой", "settings", "файл", "file", "калькулятор", "calculator", "browser", "браузер", "chrome", "clock", "часы", "погода", "weather")
                    .any { label.contains(it) || pkg.contains(it) } -> "tools"
                else -> "other"
            }
            buckets[id]?.add(app)
        }
        val defs = listOf(
            Triple("communication", "Общение", "message-circle"),
            Triple("media", "Медиа", "play-circle"),
            Triple("work", "Работа", "briefcase"),
            Triple("tools", "Инструменты", "wrench"),
            Triple("games", "Игры", "gamepad-2"),
            Triple("other", "Другое", "grid-3x3")
        )
        val built = defs.map { (id, title, icon) -> AppFolder(id, title, icon, buckets[id].orEmpty().toList()) }
            .filter { it.apps.isNotEmpty() }
        val order = prefs.getString("folder_order", "")!!.split("|").filter { it.isNotBlank() }
        return built.sortedBy { folder -> order.indexOf(folder.id).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    private fun folderTile(folder: AppFolder, action: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            background = rounded(0x77202B49, 19, 0x667FCBFF, 1)
            setPadding(dp(8), dp(10), dp(8), dp(8))
            setOnClickListener { action() }
            addView(ImageView(this@MainActivity).apply {
                setImageDrawable(drawIcon(folder.icon, Color.WHITE))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(TextView(this@MainActivity).apply {
                text = folder.title
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, dp(5), 0, 0)
            }, LinearLayout.LayoutParams(-1, dp(22)))
            addView(TextView(this@MainActivity).apply {
                text = folder.apps.size.toString() + " приложений"
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(0xFFD6E5F5.toInt())
            }, LinearLayout.LayoutParams(-1, dp(17)))
        }
    }

    private fun showFolder(folder: AppFolder) {
        AlertDialog.Builder(this)
            .setTitle(folder.title + " · " + folder.apps.size)
            .setItems(folder.apps.map { it.label }.toTypedArray()) { _, which ->
                folder.apps.getOrNull(which)?.let { launch(it.pkg) }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun manageFolderOrder() {
        val order = orderedFolders().toMutableList()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(list)
            layoutParams = ViewGroup.LayoutParams(-1, dp(360))
        }
        fun refresh() {
            list.removeAllViews()
            order.forEachIndexed { index, folder ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(4), dp(4), dp(4))
                    addView(TextView(this@MainActivity).apply {
                        text = folder.title
                        textSize = 15f
                        setTextColor(Color.WHITE)
                    }, LinearLayout.LayoutParams(0, dp(46), 1f))
                    addView(TextView(this@MainActivity).apply {
                        text = "↑"; textSize = 22f; gravity = Gravity.CENTER
                        setTextColor(if (index > 0) Color.WHITE else 0x66777777)
                        setPadding(dp(12), 0, dp(12), 0)
                        setOnClickListener {
                            if (index > 0) { val item = order.removeAt(index); order.add(index - 1, item); refresh() }
                        }
                    }, LinearLayout.LayoutParams(dp(48), dp(46)))
                    addView(TextView(this@MainActivity).apply {
                        text = "↓"; textSize = 22f; gravity = Gravity.CENTER
                        setTextColor(if (index < order.lastIndex) Color.WHITE else 0x66777777)
                        setPadding(dp(12), 0, dp(12), 0)
                        setOnClickListener {
                            if (index < order.lastIndex) { val item = order.removeAt(index); order.add(index + 1, item); refresh() }
                        }
                    }, LinearLayout.LayoutParams(dp(48), dp(46)))
                }
                list.addView(row)
                list.addView(View(this).apply { setBackgroundColor(0x447FA8D8) }, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
        refresh()
        AlertDialog.Builder(this)
            .setTitle("Порядок папок")
            .setMessage("Перемещайте папки стрелками вверх и вниз.")
            .setView(scroll)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.edit().putString("folder_order", order.joinToString("|") { it.id }).apply()
                buildHome()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun phoneAppTile(app: AppEntry, action: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            background = rounded(0x66202B49, 17, 0x557FCBFF, 1)
            setPadding(dp(4), dp(7), dp(4), dp(5))
            setOnClickListener { action() }
            val icon = app.icon.constantState?.newDrawable(resources)?.mutate() ?: app.icon
            addView(ImageView(this@MainActivity).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(TextView(this@MainActivity).apply {
                text = app.label
                textSize = 10.5f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.WHITE)
                setPadding(0, dp(5), 0, 0)
            }, LinearLayout.LayoutParams(-1, dp(20)))
        }
    }

    private fun phoneDockTile(app: AppEntry?, action: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { action() }
            if (app == null) {
                addView(TextView(this@MainActivity).apply {
                    text = "+"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(-1, -1))
            } else {
                val icon = app.icon.constantState?.newDrawable(resources)?.mutate() ?: app.icon
                addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(42), dp(42)))
            }
        }
    }

    private fun appTile(app: AppEntry?, large: Boolean, action: () -> Unit): View {
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
                v.scaleX = if (focused) 1.07f else 1f; v.scaleY = if (focused) 1.07f else 1f
            }
            setOnClickListener { action() }
            if (app != null) {
                val icon = app.icon.constantState?.newDrawable(resources)?.mutate() ?: app.icon
                addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(if (large) 46 else 30), dp(if (large) 46 else 30)))
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
            "message-circle", "play-circle", "briefcase", "wrench", "gamepad-2", "grid-3x3" -> {
                p.style = Paint.Style.STROKE
                when (kind) {
                    "message-circle" -> { canvas.drawCircle(48f, 43f, 31f, p); canvas.drawLine(28f, 66f, 20f, 82f, p); canvas.drawLine(20f, 82f, 40f, 73f, p) }
                    "play-circle" -> { canvas.drawCircle(48f, 48f, 36f, p); p.style = Paint.Style.FILL; canvas.drawPath(Path().apply { moveTo(39f, 28f); lineTo(39f, 68f); lineTo(70f, 48f); close() }, p) }
                    "briefcase" -> { canvas.drawRoundRect(RectF(12f, 30f, 84f, 78f), 8f, 8f, p); canvas.drawRoundRect(RectF(34f, 17f, 62f, 36f), 5f, 5f, p); canvas.drawLine(12f, 49f, 84f, 49f, p) }
                    "wrench" -> { canvas.drawLine(29f, 70f, 68f, 31f, p); canvas.drawCircle(25f, 75f, 10f, p); canvas.drawArc(RectF(51f, 9f, 88f, 46f), 180f, 180f, false, p) }
                    "gamepad-2" -> { canvas.drawRoundRect(RectF(9f, 31f, 87f, 76f), 17f, 17f, p); canvas.drawLine(29f, 43f, 29f, 64f, p); canvas.drawLine(18f, 54f, 40f, 54f, p); p.style = Paint.Style.FILL; canvas.drawCircle(67f, 49f, 5f, p); canvas.drawCircle(77f, 61f, 4f, p) }
                    else -> { for (y in 0..2) for (x in 0..2) canvas.drawRoundRect(RectF(15f+x*23f,15f+y*23f,32f+x*23f,32f+y*23f),4f,4f,p) }
                }
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
                        else Toast.makeText(this@MainActivity, "В доке максимум 5 приложений", Toast.LENGTH_SHORT).show()
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
            .setTitle("Док: выберите до 5 приложений")
            .setMessage("Нажмите, чтобы добавить. Нажмите ещё раз, чтобы убрать. Цифры показывают порядок.")
            .setView(scroll)
            .setPositiveButton("Готово", null)
            .setNegativeButton("Закрыть", null)
            .setOnDismissListener { buildHome() }
            .show()
    }

    private fun showMenu() {
        val items = arrayOf(
            "Выбрать 5 приложений для дока",
            "Выбрать обои",
            "Изменить порядок папок",
            "Все приложения",
            "Сделать PhoneHome главным экраном",
            "О разработчике"
        )
        AlertDialog.Builder(this).setTitle("Меню PhoneHome")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseDockApps()
                    1 -> chooseWallpaper()
                    2 -> manageFolderOrder()
                    3 -> showAllApps()
                    4 -> {
                        try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                        catch (_: Exception) {
                            try { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                        }
                    }
                    5 -> showAboutDeveloper()
                }
            }.setNegativeButton("Закрыть", null).show()
    }

    private fun showAboutDeveloper() {
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
                text = "Разработчик PhoneHome"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(0xFFCCCCCC.toInt())
                setPadding(0, dp(8), 0, dp(2))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle("О разработчике")
            .setView(holder)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun showAllApps() {
        AlertDialog.Builder(this).setTitle("Все приложения")
            .setItems(apps.map { it.label }.toTypedArray()) { _, which -> apps.getOrNull(which)?.let { launch(it.pkg) } }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun chooseWallpaper() {
        val names = arrayOf("Тюмень", "Закат в горах", "Космос", "Шри-Ланка")
        val values = arrayOf("tyumen", "pixel", "space", "srilanka")
        val current = prefs.getString("wallpaper", "pixel")
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Выберите обои")
            .setSingleChoiceItems(names, checked) { dialog, which ->
                prefs.edit().putString("wallpaper", values[which]).apply()
                dialog.dismiss()
                buildHome()
            }
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
        if (manual) weatherText.text = "Тюмень  •  Обновляем…"
        Thread {
            var result: WeatherInfo? = null
            try { result = fetchMetWeather() } catch (_: Exception) {
                try { result = fetchOpenMeteoWeather() } catch (_: Exception) { }
            }
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
                    weatherText.text = if (cached != null) "$cached\nНет связи с погодным сервисом" else "Тюмень  •  Погода недоступна"
                }
            }
        }.start()
    }

    private fun fetchMetWeather(): WeatherInfo {
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
            val icon = weatherIconFor(symbol, null)
            val text = "Тюмень   " + signedTemp(temp) + "°C\n" + weatherDescription(symbol) +
                "  •  Ветер " + wind + " м/с\nВлажность " + humidity + "%"
            return WeatherInfo(text, icon)
        } finally { connection.disconnect() }
    }

    private fun fetchOpenMeteoWeather(): WeatherInfo {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=57.1522&longitude=65.5272&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,is_day&timezone=Asia%2FYekaterinburg"
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
                0 -> "Ясно"; 1, 2 -> "Переменная облачность"; 3 -> "Пасмурно"
                45, 48 -> "Туман"; 51, 53, 55 -> "Морось"; 56, 57 -> "Ледяная морось"
                61, 63, 65 -> "Дождь"; 66, 67 -> "Ледяной дождь"; 71, 73, 75, 77, 85, 86 -> "Снег"
                80, 81, 82 -> "Ливень"; 95, 96, 99 -> "Гроза"; else -> "Погодные условия"
            }
            val isDay = current.optInt("is_day", 1) == 1
            val icon = weatherIconForCode(code, isDay)
            val text = "Тюмень   " + signedTemp(temp) + "°C\n" + desc + "  •  Ощущается " + feels +
                "°C\nВетер " + wind + " м/с  •  Влажность " + humidity + "%"
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
            s.contains("thunder") -> "Гроза"; s.contains("snow") -> "Снег"
            s.contains("sleet") -> "Мокрый снег"; s.contains("rain") -> "Дождь"
            s.contains("fog") -> "Туман"; s.contains("partlycloudy") -> "Переменная облачность"
            s.contains("cloudy") -> "Облачно"; s.contains("fair") -> "Переменная облачность"
            s.contains("clearsky") -> "Ясно"
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
            Toast.makeText(this, "Приложение часов не найдено", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launch(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    private fun loadPortraitWallpaper(resourceId: Int): Bitmap {
        val source = BitmapFactory.decodeResource(resources, resourceId)
        val targetRatio = 9f / 16f
        val sourceRatio = source.width.toFloat() / source.height.toFloat()
        val crop = if (sourceRatio > targetRatio) {
            val cropWidth = (source.height * targetRatio).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(source, (source.width - cropWidth) / 2, 0, cropWidth, source.height)
        } else {
            val cropHeight = (source.width / targetRatio).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(source, 0, (source.height - cropHeight) / 2, source.width, cropHeight)
        }
        if (crop !== source) source.recycle()
        val portrait = Bitmap.createScaledBitmap(crop, 1080, 1920, true)
        if (portrait !== crop) crop.recycle()
        return portrait
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}