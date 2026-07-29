package com.farmerboy.silageloads

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

/**
 * Home-screen widget: the day's count with + and − buttons, no app launch needed.
 * Reads and writes the same LoadStore the app and the geofence receiver use.
 */
class LoadWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_PLUS = "com.farmerboy.silageloads.WIDGET_PLUS"
        const val ACTION_MINUS = "com.farmerboy.silageloads.WIDGET_MINUS"

        fun openAppIntent(ctx: Context): PendingIntent {
            val open = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(ctx, 0, open, immutableFlags())
        }

        private fun immutableFlags(): Int {
            var f = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f = f or PendingIntent.FLAG_IMMUTABLE
            return f
        }

        private fun broadcast(ctx: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(ctx, LoadWidget::class.java).setAction(action)
            return PendingIntent.getBroadcast(ctx, requestCode, intent, immutableFlags())
        }

        /** Redraw every placed widget from current stored counts. */
        fun refresh(ctx: Context) {
            val app = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, LoadWidget::class.java))
            if (ids.isEmpty()) return
            for (id in ids) render(app, mgr, id)
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, widgetId: Int) {
            val state = LoadStore.read(ctx)
            val job = LoadStore.activeJob(state)
            val views = RemoteViews(ctx.packageName, R.layout.widget_loads)
            views.setTextViewText(R.id.widgetCount, LoadStore.dayCount(job, LoadStore.todayKey()).toString())
            views.setTextViewText(R.id.widgetJob, job.optString("name", "Job"))
            views.setOnClickPendingIntent(R.id.widgetPlus, broadcast(ctx, ACTION_PLUS, 1))
            views.setOnClickPendingIntent(R.id.widgetMinus, broadcast(ctx, ACTION_MINUS, 2))
            views.setOnClickPendingIntent(R.id.widgetCount, openAppIntent(ctx))
            mgr.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(ctx, mgr, id)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            ACTION_PLUS -> LoadStore.addLoad(ctx)
            ACTION_MINUS -> LoadStore.removeLoad(ctx)
            else -> return
        }
        refresh(ctx)
    }
}
