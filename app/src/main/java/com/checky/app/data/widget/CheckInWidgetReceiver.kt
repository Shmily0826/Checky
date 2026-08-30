package com.checky.app.data.widget

import android.content.Context
import android.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.Button
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.checky.app.data.work.AutoCheckInWorker

/**
 * Home-screen widget: one tap runs "Check in all" for every enabled provider
 * through the same worker the daily schedule uses. Results land in history
 * (and reconnect notifications), so the widget itself stays stateless.
 */
class CheckInWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CheckInWidget()
}

class CheckInWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Box(
                modifier = GlanceModifier.fillMaxSize().background(ColorProvider(Color.DKGRAY)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = GlanceModifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Checky",
                        style = TextStyle(
                            color = ColorProvider(Color.WHITE),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    )
                    Text(
                        "One tap runs every enabled check-in.",
                        style = TextStyle(
                            color = ColorProvider(Color.LTGRAY),
                            fontSize = 12.sp
                        )
                    )
                }
                Button(
                    text = "Check in all",
                    onClick = actionRunCallback<RunAllCheckInsAction>(
                        actionParametersOf()
                    ),
                    modifier = GlanceModifier.padding(top = 8.dp).cornerRadius(12.dp)
                )
            }
        }
    }
}

/** Button action: enqueue one auto check-in run. */
class RunAllCheckInsAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<AutoCheckInWorker>().build()
        )
    }
}
