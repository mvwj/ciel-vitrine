package com.cielvitrine.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable internal fun CloudBadge(status:MediaCloudStatus,onClick:()->Unit) {
    val icon=when(status.mark) {
        CloudMark.LOCAL -> Icons.Outlined.CloudOff
        CloudMark.UNKNOWN -> Icons.Outlined.HelpOutline
        CloudMark.QUEUED -> Icons.Outlined.Schedule
        CloudMark.PREPARING -> Icons.Outlined.HourglassTop
        CloudMark.UPLOADING -> Icons.Outlined.CloudUpload
        CloudMark.VERIFYING -> Icons.Outlined.FactCheck
        CloudMark.PAUSED -> Icons.Outlined.PauseCircleOutline
        CloudMark.ERROR -> Icons.Outlined.ErrorOutline
        CloudMark.ARCHIVED -> Icons.Outlined.Inventory2
        else -> Icons.Outlined.CloudDone
    }
    val color=when(status.mark) {
        CloudMark.ERROR -> Color(0xFFFFB4AB)
        CloudMark.SAVED,CloudMark.READY,CloudMark.CLOUD_ONLY -> Color(0xFFA6E3BC)
        CloudMark.ARCHIVED -> Color(0xFFC6D4EF)
        else -> Color.White
    }
    Surface(onClick=onClick,shape=CircleShape,color=Color.Black.copy(alpha=.72f),
        modifier=Modifier.size(30.dp).testTag("cloud-badge:${status.mark}").semantics{contentDescription="Облако: ${status.title}"}) {
        Box(contentAlignment=Alignment.Center){
            Icon(icon,null,Modifier.size(18.dp),tint=color)
            if(status.mark in setOf(CloudMark.UPLOADING,CloudMark.PREPARING,CloudMark.VERIFYING))
                CircularProgressIndicator(Modifier.fillMaxSize().padding(1.dp),strokeWidth=1.5.dp,color=color)
        }
    }
}

@Composable internal fun CloudActivityCard(archive:ArchiveUiState,connection:YandexCloudState,open:()->Unit) {
    val statuses=archive.mediaStatus.values
    val uploading=statuses.count{it.mark in setOf(CloudMark.UPLOADING,CloudMark.PREPARING,CloudMark.VERIFYING)}
    val queued=statuses.count{it.mark==CloudMark.QUEUED}
    val errors=statuses.count{it.mark==CloudMark.ERROR}
    val title=when {
        archive.paused -> "Загрузка на паузе"
        archive.queueNotice!=null -> "Загрузка не завершена"
        uploading>0 -> "Сохраняем в облако"
        connection.connection!=YandexConnection.CONNECTED -> "Подключение к облаку"
        queued>0 -> "Файлы в очереди"
        errors>0 -> "Есть незавершённые загрузки"
        else -> "Яндекс Диск подключён"
    }
    val subtitle=when {
        archive.queueNotice!=null -> archive.queueNotice
        connection.account==null -> "Войдите в Яндекс. Очередь сохранена на телефоне."
        connection.connection!=YandexConnection.CONNECTED -> "Проверьте соединение на вкладке «Облако». Файлы на телефоне сохранены."
        else -> "В очереди: $queued · сейчас: $uploading · требуют внимания: $errors"
    }
    Surface(onClick=open,color=MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),shape=MaterialTheme.shapes.medium) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Icon(if(errors>0)Icons.Outlined.CloudOff else Icons.Outlined.CloudQueue,null)
            Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleSmall);Text(subtitle,style=MaterialTheme.typography.bodySmall)}
            Icon(Icons.Outlined.ChevronRight,"Открыть облако")
        }
    }
}

@Composable internal fun ProtectionCard(plan:ProtectionPlan,busy:Boolean,release:()->Unit,retry:()->Unit,setReady:Boolean=true) {
    Surface(Modifier.fillMaxWidth().padding(horizontal=16.dp),color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(plan.displayName.ifBlank{"Выбранный файл"},style=MaterialTheme.typography.bodyMedium,maxLines=1)
            Text(when(plan.stage){
                ProtectionStage.ARCHIVING->"1 из 3 · Холодный архив"
                ProtectionStage.WORKING_COPY->"2 из 3 · Рабочая копия"
                ProtectionStage.PREVIEW->"3 из 3 · Просмотр из облака"
                ProtectionStage.READY,ProtectionStage.ANDROID_REQUESTED->"Готово к освобождению места"
                ProtectionStage.LOCAL_REMOVED->"Оригинал на телефоне удалён"
                ProtectionStage.SAVED->"Сохранено в двух зонах облака"
                ProtectionStage.ERROR->"Подготовка остановлена"
            },style=MaterialTheme.typography.titleSmall)
            Text(plan.notice ?: when(plan.stage) {
                ProtectionStage.READY,ProtectionStage.ANDROID_REQUESTED->if(setReady)"Заново проверим холодный архив, рабочую копию и исходник. Миниатюра останется в галерее." else "Файл сохранён. Ждём остальные части выбранного набора."
                ProtectionStage.LOCAL_REMOVED->"Холодный архив и рабочая копия сохранены. Открытие снимка загрузит детали из облака."
                ProtectionStage.SAVED->"Исходник остаётся на телефоне."
                else->"Исходник остаётся на телефоне до завершения всех проверок."
            },style=MaterialTheme.typography.bodySmall)
            if(plan.stage in setOf(ProtectionStage.READY,ProtectionStage.ANDROID_REQUESTED))
                Button(onClick=release,enabled=!busy && setReady){Text("Освободить место на телефоне")}
            if(plan.stage==ProtectionStage.ERROR)OutlinedButton(onClick=retry,enabled=!busy){Text("Повторить")}
        }
    }
}
