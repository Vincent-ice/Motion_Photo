package com.example.motionphotomaker

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlin.math.roundToInt

class StudioHomeActivity : ComponentActivity() {
    private val bg = Color.rgb(16, 17, 20)
    private val panel = Color.rgb(30, 32, 37)
    private val textPrimary = Color.rgb(245, 246, 248)
    private val textSecondary = Color.rgb(174, 178, 188)
    private val accent = Color.rgb(255, 196, 46)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi() = ScrollView(this).apply {
        setBackgroundColor(bg)
        addView(
            LinearLayout(this@StudioHomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(28), dp(20), dp(36))

                addView(TextView(context).apply {
                    text = "Motion Photo Studio"
                    textSize = 29f
                    setTextColor(textPrimary)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = "选择制作模式"
                    textSize = 14f
                    setTextColor(textSecondary)
                    setPadding(0, dp(4), 0, dp(22))
                })

                addView(modeCard(
                    title = "单张动态照片",
                    subtitle = "一张 JPEG / PNG + 一个视频，支持时间裁剪、比例、缩放和平移。",
                    buttonText = "进入单张编辑器",
                ) {
                    startActivity(Intent(this@StudioHomeActivity, AlignedEditorActivity::class.java))
                })

                addView(modeCard(
                    title = "宫格动态拼图",
                    subtitle = "把一张图片切成 4 / 6 / 9 个 1:1 方格，每格绑定一个视频，批量生成朋友圈拼图。",
                    buttonText = "进入宫格模式",
                ) {
                    startActivity(Intent(this@StudioHomeActivity, GridMotionPhotoActivity::class.java))
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(14) })

                addView(TextView(context).apply {
                    text = "所有处理均在本机完成。宫格模式生成的是一组独立 Motion Photo，可按左上到右下顺序一起选择发送。"
                    textSize = 12f
                    setTextColor(textSecondary)
                    setLineSpacing(0f, 1.2f)
                    setPadding(0, dp(20), 0, 0)
                })
            },
        )
    }

    private fun modeCard(
        title: String,
        subtitle: String,
        buttonText: String,
        onClick: () -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(panel, 20f)

        addView(TextView(context).apply {
            text = title
            textSize = 19f
            setTextColor(textPrimary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = subtitle
            textSize = 13f
            setTextColor(textSecondary)
            setLineSpacing(0f, 1.18f)
            setPadding(0, dp(7), 0, dp(14))
        })
        addView(Button(context).apply {
            text = buttonText
            isAllCaps = false
            setTextColor(Color.BLACK)
            background = rounded(accent, 15f)
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(50),
        ))
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).roundToInt()
}
