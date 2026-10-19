package com.inacap.gallinasapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Recibe las alertas enviadas por la Cloud Function (mensajes "data")
 * y muestra una notificación con sonido de alarma.
 */
class AlertService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.data["title"] ?: "Alerta del gallinero"
        val body = message.data["body"] ?: return
        showAlarm(this, title, body)
    }

    override fun onNewToken(token: String) {
        // El token se vuelve a guardar al abrir la app (ver MainActivity)
    }

    companion object {
        const val CHANNEL_ID = "alertas_gallinero"

        fun createChannel(context: Context) {
            val alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val channel = NotificationChannel(
                CHANNEL_ID, "Alertas del gallinero", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisa cuando faltan gallinas"
                setSound(
                    alarm,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        fun showAlarm(context: Context, title: String, body: String) {
            createChannel(context)
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify(System.currentTimeMillis().toInt(), n)
        }
    }
}
