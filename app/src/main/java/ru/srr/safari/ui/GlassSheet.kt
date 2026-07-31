package ru.srr.safari.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import ru.srr.safari.R
import ru.srr.safari.data.BrowserSettings
import ru.srr.safari.databinding.DialogGlassSheetBinding

/** Centered Liquid Glass action sheet — replaces Material AlertDialog. */
object GlassSheet {

    data class Item(
        val title: String,
        val destructive: Boolean = false,
        val muted: Boolean = false,
        val action: () -> Unit = {}
    )

    private fun glassOpacity(context: Context): Int = BrowserSettings(context).glassOpacity

    private fun styleCard(context: Context, card: View) {
        LiquidGlass.polishCapsule(card, 26f)
        card.background = LiquidGlass.popoverDrawable(context, glassOpacity(context))
    }

    fun showList(
        context: Context,
        title: String? = null,
        items: List<Item>,
        cancelLabel: String? = "Отмена"
    ): Dialog {
        val dialog = baseDialog(context)
        val binding = DialogGlassSheetBinding.inflate(LayoutInflater.from(context))
        if (!title.isNullOrBlank()) {
            binding.sheetTitle.visibility = View.VISIBLE
            binding.sheetTitle.text = title
        }
        val rows = buildList {
            addAll(items)
            if (!cancelLabel.isNullOrBlank()) {
                add(Item(cancelLabel, muted = true) { dialog.dismiss() })
            }
        }
        rows.forEachIndexed { index, item ->
            if (index > 0 || binding.sheetTitle.visibility == View.VISIBLE) {
                binding.sheetItems.addView(hairline(context))
            }
            binding.sheetItems.addView(row(context, item) {
                dialog.dismiss()
                item.action()
            })
        }
        styleCard(context, binding.sheetCard)
        dialog.setContentView(binding.root)
        dialog.show()
        sizeWindow(dialog)
        SafariMotion.appear(
            binding.sheetCard,
            fromScale = 0.94f,
            fromY = 14f * context.resources.displayMetrics.density
        )
        return dialog
    }

    fun showInput(
        context: Context,
        title: String,
        input: EditText,
        positive: String,
        onPositive: () -> Unit,
        neutral: String? = null,
        onNeutral: (() -> Unit)? = null,
        negative: String = "Закрыть",
        onNegative: (() -> Unit)? = null
    ): Dialog {
        val dialog = baseDialog(context)
        val binding = DialogGlassSheetBinding.inflate(LayoutInflater.from(context))
        binding.sheetTitle.visibility = View.VISIBLE
        binding.sheetTitle.text = title

        val pad = (16 * context.resources.displayMetrics.density).toInt()
        input.setTextColor(ContextCompat.getColor(context, R.color.safari_text))
        input.setHintTextColor(ContextCompat.getColor(context, R.color.safari_muted))
        input.background = null
        input.setPadding(pad, pad / 2, pad, pad / 2)
        binding.sheetItems.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        fun addAction(label: String, destructive: Boolean = false, muted: Boolean = false, block: () -> Unit) {
            binding.sheetItems.addView(hairline(context))
            binding.sheetItems.addView(
                row(context, Item(label, destructive = destructive, muted = muted)) {
                    dialog.dismiss()
                    block()
                }
            )
        }
        addAction(positive) { onPositive() }
        if (neutral != null && onNeutral != null) {
            addAction(neutral) { onNeutral() }
        }
        addAction(negative, muted = true) { onNegative?.invoke() }

        styleCard(context, binding.sheetCard)
        dialog.setContentView(binding.root)
        dialog.show()
        sizeWindow(dialog)
        SafariMotion.appear(
            binding.sheetCard,
            fromScale = 0.94f,
            fromY = 14f * context.resources.displayMetrics.density
        )
        return dialog
    }

    private fun baseDialog(context: Context): Dialog {
        val dialog = Dialog(context, R.style.Theme_Safari_GlassDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return dialog
    }

    private fun sizeWindow(dialog: Dialog) {
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
    }

    private fun hairline(context: Context): View {
        val d = context.resources.displayMetrics.density
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (0.5f * d).toInt().coerceAtLeast(1)
            ).apply {
                marginStart = (14 * d).toInt()
            }
            setBackgroundColor(ContextCompat.getColor(context, R.color.safari_menu_hairline))
        }
    }

    private fun row(context: Context, item: Item, onClick: () -> Unit): TextView {
        val d = context.resources.displayMetrics.density
        val color = when {
            item.destructive -> 0xFFFF3B30.toInt()
            item.muted -> ContextCompat.getColor(context, R.color.safari_muted)
            else -> ContextCompat.getColor(context, R.color.safari_text)
        }
        return TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * d).toInt()
            )
            gravity = Gravity.CENTER
            text = item.title
            textSize = 17f
            setTextColor(color)
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            val tv = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
        }
    }
}
