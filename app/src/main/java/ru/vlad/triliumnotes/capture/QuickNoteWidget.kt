package ru.vlad.triliumnotes.capture

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.MainActivity
import ru.vlad.triliumnotes.R

/** A launch shortcut only: no session, server or note content is read by the widget. */
class QuickNoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            val launch = Intent(context, MainActivity::class.java).setAction(ACTION_QUICK_NOTE)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(context, id, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val views = RemoteViews(context.packageName, R.layout.quick_note_widget).apply {
                setTextViewText(R.id.quick_note_label, AppLanguage.get(context, R.string.quick_note_widget_name))
                setContentDescription(R.id.quick_note_button, AppLanguage.get(context, R.string.quick_note_widget_description))
                setOnClickPendingIntent(R.id.quick_note_button, pending)
            }
            manager.updateAppWidget(id, views)
        }
    }
    companion object {
        const val ACTION_QUICK_NOTE = "ru.vlad.triliumnotes.action.QUICK_NOTE"
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            QuickNoteWidget().onUpdate(context, manager, manager.getAppWidgetIds(ComponentName(context, QuickNoteWidget::class.java)))
        }
    }
}
