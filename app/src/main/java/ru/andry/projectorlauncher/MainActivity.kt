package ru.andry.projectorlauncher

import android.app.Activity
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
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private val refreshClock = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            date.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
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

    private fun buildHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(34), dp(22), dp(34), dp(24))
            setBackgroundColor(Color.rgb(16, 20, 29))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = TextView(this).apply {
            text = "PROJECTOR"
            textSize = 17f
            setTextColor(Color.rgb(151, 174, 199))
            letterSpacing = 0.18f
        }
        header.addView(heading, LinearLayout.LayoutParams(0, dp(36), 1f))

        val timeColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        clock = TextView(this).apply {
            textSize = 30f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        }
        date = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(155, 169, 188))
        }
        timeColumn.addView(clock)
        timeColumn.addView(date)
        header.addView(timeColumn)
        root.addView(header)

        val label = TextView(this).apply {
            text = "Приложения"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dp(25), 0, dp(14))
        }
        root.addView(label)

        val grid = GridLayout(this).apply {
            columnCount = 4
            rowCount = 2
            useDefaultMargins = false
        }
        val apps = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).mapNotNull { info ->
            val pkg = info.activityInfo.packageName
            if (pkg == packageName) null else Triple(
                info.loadLabel(packageManager).toString(),
                info.loadIcon(packageManager),
                pkg
            )
        }.distinctBy { it.third }
            .sortedBy { it.first.lowercase(Locale.getDefault()) }

        apps.take(8).forEach { app ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                background = tileBackground(false)
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setOnFocusChangeListener { v, focused -> v.background = tileBackground(focused) }
                setOnClickListener {
                    packageManager.getLaunchIntentForPackage(app.third)?.let { startActivity(it) }
                }
            }
            val icon = android.widget.ImageView(this).apply {
                setImageDrawable(app.second)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }
            tile.addView(icon, LinearLayout.LayoutParams(dp(46), dp(46)))
            val name = TextView(this).apply {
                text = app.first
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.WHITE)
                setPadding(0, dp(10), 0, 0)
            }
            tile.addView(name, LinearLayout.LayoutParams(-1, dp(28)))
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(124)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(6), dp(5), dp(6), dp(5))
            }
            grid.addView(tile, lp)
        }

        val settingsTile = TextView(this).apply {
            text = "⚙   Настройки"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isFocusable = true
            background = tileBackground(false)
            setOnFocusChangeListener { v, focused -> v.background = tileBackground(focused) }
            setOnClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            }
        }
        grid.addView(settingsTile, GridLayout.LayoutParams().apply {
            width = 0
            height = dp(64)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(6), dp(5), dp(6), dp(5))
        })

        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = TextView(this).apply {
            text = "Выберите приложение с помощью пульта"
            textSize = 12f
            setTextColor(Color.rgb(112, 129, 151))
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(footer, LinearLayout.LayoutParams(-1, dp(24)))
        setContentView(root)
        refreshClock.run()
        grid.getChildAt(0)?.requestFocus()
    }

    private fun tileBackground(focused: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(15).toFloat()
            setColor(if (focused) Color.rgb(47, 70, 94) else Color.rgb(29, 37, 51))
            if (focused) setStroke(dp(2), Color.rgb(117, 213, 255))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
