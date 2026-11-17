package ru.andry.projectorlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var root: LinearLayout
    private var apps: List<AppEntry> = emptyList()
    private val backgrounds = listOf(
        intArrayOf(0xFF120B35.toInt(), 0xFFB51D72.toInt(), 0xFF071B54.toInt()),
        intArrayOf(0xFF071B2B.toInt(), 0xFF087E8B.toInt(), 0xFF101820.toInt()),
        intArrayOf(0xFF24112F.toInt(), 0xFF8F3B76.toInt(), 0xFFF0A35E.toInt()),
        intArrayOf(0xFF101820.toInt(), 0xFF34495E.toInt(), 0xFF05080D.toInt())
    )
    private val backgroundNames = listOf("Неон", "Океан", "Закат", "Графит")
    private val refreshClock = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            date.text = SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(now)
            handler.postDelayed(this, 30_000)
        }
    }

    data class AppEntry(val label: String, val icon: android.graphics.drawable.Drawable, val pkg: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        loadApps()
        buildHome()
    }

    override fun onResume() {
        super.onResume()
        if (::clock.isInitialized) handler.post(refreshClock)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshClock)
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

    private fun favoritePackages(): MutableList<String> {
        val saved = prefs.getString("favorites", null)
        if (saved != null) return saved.split("|").filter { it.isNotBlank() }.take(4).toMutableList()
        return apps.take(4).map { it.pkg }.toMutableList()
    }

    private fun buildHome() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(38), dp(20), dp(38), dp(18))
            background = backgroundDrawable()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "ДОБРЫЙ ДЕНЬ"
            textSize = 16f
            letterSpacing = 0.12f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, dp(34), 1f))
        clock = TextView(this).apply {
            textSize = 31f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        }
        date = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFE8E5F5.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(18), 0)
        }
        header.addView(clock)
        header.addView(date)
        header.addView(TextView(this).apply {
            text = "⚙  Настроить"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = pillBackground()
            isFocusable = true
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { showSettings() }
        })
        root.addView(header)

        root.addView(sectionTitle("Избранное"))
        val dock = GridLayout(this).apply { columnCount = 4; useDefaultMargins = false }
        val favorites = favoritePackages()
        for (i in 0 until 4) {
            val app = apps.firstOrNull { it.pkg == favorites.getOrNull(i) }
            dock.addView(appTile(app, true) {
                if (app != null) launch(app.pkg) else chooseFavorite(i)
            }, GridLayout.LayoutParams().apply {
                width = 0; height = dp(112)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(5), dp(3), dp(5), dp(3))
            })
        }
        root.addView(dock, LinearLayout.LayoutParams(-1, dp(120)))

        root.addView(sectionTitle("Все приложения"))
        val favoriteSet = favorites.toSet()
        val rest = apps.filter { it.pkg !in favoriteSet }
        val grid = GridLayout(this).apply { columnCount = 5; useDefaultMargins = false }
        rest.forEach { app ->
            grid.addView(appTile(app, false) { launch(app.pkg) }, GridLayout.LayoutParams().apply {
                width = 0; height = dp(105)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(3), dp(4), dp(3))
            })
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            isFocusableInTouchMode = false
            addView(grid)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refreshClock.run()
        dock.getChildAt(0)?.requestFocus()
    }

    private fun appTile(app: AppEntry?, large: Boolean, action: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            background = tileBackground(false, large)
            setPadding(dp(6), dp(7), dp(6), dp(7))
            setOnFocusChangeListener { v, focused -> v.background = tileBackground(focused, large) }
            setOnClickListener { action() }
            if (app != null) {
                val icon = android.widget.ImageView(this@MainActivity).apply {
                    setImageDrawable(app.icon)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }
                addView(icon, LinearLayout.LayoutParams(if (large) dp(48) else dp(38), if (large) dp(48) else dp(38)))
                addView(TextView(this@MainActivity).apply {
                    text = app.label
                    textSize = if (large) 14f else 12f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(5), 0, 0)
                }, LinearLayout.LayoutParams(-1, dp(25)))
            } else {
                addView(TextView(this@MainActivity).apply {
                    text = "＋\nВыбрать"
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setTextColor(Color.WHITE)
                })
            }
        }
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setPadding(dp(2), dp(13), 0, dp(7))
    }

    private fun showSettings() {
        AlertDialog.Builder(this)
            .setTitle("Настройки рабочего стола")
            .setItems(arrayOf("Выбрать 4 приложения в док", "Изменить фон")) { _, which ->
                if (which == 0) chooseFavorite(0) else chooseBackground()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun chooseFavorite(slot: Int) {
        val labels = apps.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Приложение для дока " + (slot + 1))
            .setItems(labels) { _, index ->
                val selected = apps[index].pkg
                val favorites = favoritePackages()
                while (favorites.size <= slot) favorites.add("")
                val oldIndex = favorites.indexOf(selected)
                if (oldIndex >= 0 && oldIndex != slot) favorites[oldIndex] = favorites.getOrNull(slot) ?: ""
                favorites[slot] = selected
                prefs.edit().putString("favorites", favorites.filter { it.isNotBlank() }.joinToString("|")).apply()
                buildHome()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun chooseBackground() {
        AlertDialog.Builder(this)
            .setTitle("Фон рабочего стола")
            .setItems(backgroundNames.toTypedArray()) { _, index ->
                prefs.edit().putInt("background", index).apply()
                buildHome()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun launch(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    private fun backgroundDrawable(): GradientDrawable {
        val index = prefs.getInt("background", 0).coerceIn(backgrounds.indices)
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, backgrounds[index])
    }

    private fun pillBackground() = GradientDrawable().apply {
        cornerRadius = dp(24).toFloat()
        setColor(0x443FFFFFF)
        setStroke(dp(1), 0x99FFFFFF.toInt())
    }

    private fun tileBackground(focused: Boolean, large: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(if (large) 17 else 13).toFloat()
        setColor(if (focused) 0xCCB9EFFF.toInt() else if (large) 0xAA142E59.toInt() else 0x88202B49.toInt())
        if (focused) setStroke(dp(3), 0xFFFFD66B.toInt())
        else setStroke(dp(1), 0x667FE8FF)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
