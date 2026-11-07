package com.dromaia.ipcam

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/** Home-screen widget: «all cameras» (gold) and the first five cameras; one tap opens the live view. */
class CamWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, am: AppWidgetManager, ids: IntArray) = updateAll(ctx)

    companion object {
        private val BUTTONS = intArrayOf(R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4, R.id.btn5)

        fun updateAll(ctx: Context) {
            try {
                val am = AppWidgetManager.getInstance(ctx) ?: return
                val ids = am.getAppWidgetIds(ComponentName(ctx, CamWidget::class.java))
                if (ids.isEmpty()) return
                val rv = build(ctx)
                for (id in ids) am.updateAppWidget(id, rv)
            } catch (_: Exception) {
            }
        }

        private fun open(ctx: Context, code: Int, intent: Intent): PendingIntent =
            PendingIntent.getActivity(
                ctx, code,
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun build(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_cams)
            val cams = Store.all(ctx)
            rv.setTextViewText(R.id.btn0, L.t(ctx, "▦ كل الكاميرات", "▦ All cameras"))
            rv.setOnClickPendingIntent(
                R.id.btn0,
                if (cams.isEmpty()) open(ctx, 100, Intent(ctx, MainActivity::class.java)) else open(ctx, 100, Watch.all(ctx)),
            )
            for (i in 1 until BUTTONS.size) {
                val cam = cams.getOrNull(i - 1)
                if (cam == null) {
                    rv.setViewVisibility(BUTTONS[i], View.INVISIBLE)
                } else {
                    rv.setViewVisibility(BUTTONS[i], View.VISIBLE)
                    rv.setTextViewText(BUTTONS[i], cam.name)
                    rv.setOnClickPendingIntent(BUTTONS[i], open(ctx, 100 + i, Watch.one(ctx, cam.id)))
                }
            }
            return rv
        }
    }
}
