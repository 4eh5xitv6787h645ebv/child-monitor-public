package de.rochefort.childmonitor.ux

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import de.rochefort.childmonitor.R

/**
 * Parent quick-action widgets and lockscreen controls.
 * Implements CM-UX-003.
 *
 * The widget shows a single "Start listening" button that launches the parent
 * activity. Home-screen providers are added at install time; the widget layout
 * lives in the UI module so we only need the provider class and the update
 * logic here.
 */
class ParentActionsWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) update(context, appWidgetManager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_RESUME) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, javaClass))
            for (id in ids) update(context, mgr, id)
        }
    }

    private fun update(context: Context, manager: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_parent_actions)
        val launch = Intent().apply {
            setClassName(context, "de.rochefort.childmonitor.StartActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widgetRoot, pi)
        manager.updateAppWidget(id, views)
    }

    companion object {
        const val ACTION_RESUME = "de.rochefort.childmonitor.widget.RESUME"
    }
}
