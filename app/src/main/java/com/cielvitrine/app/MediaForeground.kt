package com.cielvitrine.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

internal fun mediaForeground(context:Context,id:Int,text:String):ForegroundInfo {
    val manager=context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("media-work","Сохранение фото и видео",NotificationManager.IMPORTANCE_LOW))
    val notification=NotificationCompat.Builder(context,"media-work").setSmallIcon(R.drawable.ic_vitrine)
        .setContentTitle("Небесная витрина").setContentText(text).setOngoing(true).setOnlyAlertOnce(true).build()
    return ForegroundInfo(id,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
}
