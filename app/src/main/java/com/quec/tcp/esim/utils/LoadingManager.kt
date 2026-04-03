package com.quec.tcp.esim.utils

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.Window
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView


object LoadingManager {

    private var dialog: Dialog? = null

    /**
     * 显示 Loading
     */
    fun show(activity: Activity, message: String = "Loading...") {

        if (activity.isFinishing || activity.isDestroyed) {
            return
        }

        if (dialog != null && dialog!!.isShowing) {
            return
        }

        dialog = Dialog(activity)
        dialog?.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog?.setCancelable(false)

        val layout = LinearLayout(activity)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(80, 60, 80, 60)
        layout.gravity = Gravity.CENTER

        val progressBar = ProgressBar(activity)
        val textView = TextView(activity)

        textView.text = message
        textView.textSize = 16f
        textView.gravity = Gravity.CENTER
        textView.setPadding(0, 30, 0, 0)

        layout.addView(progressBar)
        layout.addView(textView)

        dialog?.setContentView(layout)

        dialog?.show()
    }

    /**
     * 隐藏 Loading
     */
    fun hide() {
        dialog?.let {
            if (it.isShowing) {
                it.dismiss()
            }
        }
        dialog = null
    }
}