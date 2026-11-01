package com.cielvitrine.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

@Composable internal fun PerformanceCard(jobIds:Set<String>) {
    val context=LocalContext.current
    var snapshot by remember(jobIds){mutableStateOf<JSONObject?>(null)}
    var failed by remember{mutableStateOf(false)}
    LaunchedEffect(jobIds){while(true){
        try{snapshot=withContext(Dispatchers.IO){PerformanceJournal(context).snapshot(export=true,ids=jobIds)};failed=false}
        catch(_:Exception){failed=true};delay(1500)
    }}
    val stages=linkedMapOf("encrypt" to "Шифрование", "prepare" to "Подготовка целиком", "upload" to "Отправка и запросы", "verify" to "Обратная проверка",
        "download" to "Скачивание оригинала", "restore" to "Восстановление и проверка", "image_decode" to "Декодирование", "open_first_frame" to "Первый кадр", "zoom_decode" to "Детали при зуме", "zoom_first_frame" to "Показ деталей", "cache_read" to "Проверка кэша",
        "http_upload_link" to "Получение ссылки для отправки", "http_upload_body" to "Запись тела запроса в сетевой буфер", "http_upload_request" to "Отправка до ответа сервера",
        "tile_prepare" to "Подготовка фрагментов", "tile_upload" to "Загрузка фрагментов", "tile_verify" to "Проверка фрагментов", "tile_download" to "Скачивание участков", "tile_cache" to "Участки из кэша", "tile_decode" to "Декодирование участков", "cloud_first_frame" to "Первый облачный кадр", "cloud_zoom_frame" to "Облачный зум")
    Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text("Замеры ваших файлов",style=MaterialTheme.typography.titleMedium)
        Text("Время фактических попыток. Облачные фото получают только нужные участки. Кэш и сетевые запросы считаются отдельно; вложенные этапы не складываются.",style=MaterialTheme.typography.bodySmall)
        if(failed)Text("Отчёт не прочитан",color=MaterialTheme.colorScheme.error)
        val values=mutableMapOf<String,JSONObject>()
        val counts=mutableMapOf<String,Int>();val failures=mutableMapOf<String,Int>();var networkBytes=0L
        val rows=snapshot?.optJSONArray("rows")
        if(rows!=null)for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i)
            val totals=row.optJSONObject("totals") ?: continue
            for(stage in stages.keys){val t=totals.optJSONObject(stage) ?: continue
                counts[stage]=(counts[stage] ?: 0)+t.getInt("attempts")
                failures[stage]=(failures[stage] ?: 0)+t.getInt("attempts")-t.getInt("success")
                if(t.getLong("at")>(values[stage]?.getLong("at") ?: 0))values[stage]=t
                if(stage=="tile_download")networkBytes+=t.getLong("bytes")
            }
        }
        if(values.isEmpty())Text("Выберите фото → «В облако». После проверки откройте облачную копию и увеличьте её.",style=MaterialTheme.typography.bodySmall)
        stages.forEach{(stage,label)->values[stage]?.let{v->Text("$label: последняя ${String.format(Locale.ROOT,"%.1f",v.getDouble("last_ms"))} мс · попыток ${counts[stage]} · ошибок ${failures[stage]}",style=MaterialTheme.typography.bodySmall)}}
        var bodyBytes=0L;var requestMs=0.0;var successfulRequests=0
        if(rows!=null)for(i in 0 until rows.length()) {
            val events=rows.getJSONObject(i).optJSONArray("events") ?: continue
            for(j in 0 until events.length()) {
                val event=events.getJSONObject(j)
                if(event.optString("stage")=="http_upload_request" && event.optBoolean("ok")) {
                    bodyBytes+=event.optLong("bytes");requestMs+=event.optDouble("duration_ms");successfulRequests++
                }
            }
        }
        if(bodyBytes>0 && requestMs>0)Text("Успешные отправки в отчёте: $successfulRequests · ${String.format(Locale.ROOT,"%.2f",bodyBytes/1e6)} МБ · до ответа сервера: ${String.format(Locale.ROOT,"%.1f",requestMs/1000)} с · средняя ${String.format(Locale.ROOT,"%.2f",bodyBytes/1000.0/requestMs)} МБ/с. Обратная проверка учитывается отдельно.",style=MaterialTheme.typography.bodySmall)
        if(networkBytes>0)Text("Получено участков: $networkBytes байт",style=MaterialTheme.typography.bodySmall)
    }
}
