package com.vslot.app.ui.dialog

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import com.vslot.app.R

class ThirdPartyNoticesDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val noticeText = runCatching {
            val bundledNotices = THIRD_PARTY_NOTICES_ASSETS.joinToString("\n\n") { assetName ->
                context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { reader ->
                    reader.readText()
                }
            }
            "${getString(R.string.third_party_notices_original_language)}\n\n$bundledNotices"
        }.getOrElse {
            getString(R.string.third_party_notices_unavailable)
        }
        val root = FrameLayout(context).apply {
            setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
        }
        root.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.modal_panel_backplate)
                scaleType = ImageView.ScaleType.FIT_XY
                importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((28 * density).toInt(), (24 * density).toInt(), (28 * density).toInt(), (24 * density).toInt())
        }
        content.addView(
            TextView(context).apply {
                setText(R.string.third_party_notices_title)
                setTextColor(Color.WHITE)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        content.addView(
            ScrollView(context).apply {
                isFillViewport = true
                addView(
                    TextView(context).apply {
                        text = noticeText
                        setTextColor(Color.rgb(238, 233, 247))
                        textSize = 12f
                        typeface = Typeface.MONOSPACE
                        setTextIsSelectable(true)
                        setPadding(0, (16 * density).toInt(), 0, (16 * density).toInt())
                    },
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        val closeButton = ImageButton(context).apply {
            setImageResource(R.drawable.btn_modal_close_selector)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.close)
            scaleType = ImageView.ScaleType.FIT_XY
            setOnClickListener { dismiss() }
        }
        val closeContainer = FrameLayout(context).apply {
            addView(
                closeButton,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.label_close)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setPadding((18 * density).toInt(), 0, (18 * density).toInt(), 0)
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        content.addView(
            closeContainer,
            LinearLayout.LayoutParams((180 * density).toInt(), (52 * density).toInt())
        )
        root.addView(
            content,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        return Dialog(context).apply {
            setContentView(root)
            setOnShowListener { keepGameFullscreen() }
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        }
    }

    private companion object {
        val THIRD_PARTY_NOTICES_ASSETS = listOf(
            "third_party_notices.txt",
            "third_party_embedded_licenses.txt"
        )
    }
}
