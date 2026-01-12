package com.ghostyapps.heynotes

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class QuickWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        // MainActivity'de yakalayacağımız özel komutlar
        const val ACTION_QUICK_VOICE = "com.ghostyapps.heynotes.ACTION_QUICK_VOICE"
        const val ACTION_QUICK_TEXT = "com.ghostyapps.heynotes.ACTION_QUICK_TEXT"

        internal fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_actions)

            // 1. SES KAYDI BUTONU INTENT
            val voiceIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_QUICK_VOICE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val voicePending = PendingIntent.getActivity(
                context, 1, voiceIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btnWidgetVoice, voicePending)


            // 2. YAZI NOTU BUTONU INTENT
            val textIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_QUICK_TEXT
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val textPending = PendingIntent.getActivity(
                context, 2, textIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btnWidgetText, textPending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }}
}