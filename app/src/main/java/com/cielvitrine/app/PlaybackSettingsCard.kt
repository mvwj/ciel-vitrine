package com.cielvitrine.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable internal fun PlaybackSettingsCard() {
    val context=LocalContext.current;val preferences=remember{PlaybackPreferences(context)}
    var quality by remember{mutableStateOf(preferences.quality)};var menu by remember{mutableStateOf(false)}
    Card(Modifier.fillMaxWidth().padding(horizontal=24.dp)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Просмотр видео",style=MaterialTheme.typography.titleMedium)
            Text("Качество загрузки из облака по умолчанию. Оригинал всегда хранится отдельно в исходном качестве.",style=MaterialTheme.typography.bodySmall)
            Box{OutlinedButton(onClick={menu=true}){Text(quality.label)}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}){VideoQuality.entries.forEach{value->
                    DropdownMenuItem(text={Text(value.label)},onClick={quality=value;preferences.quality=value;menu=false})
                }}
            }
            Text("После закрытия просмотра временно скачанные части фото и видео очищаются. Миниатюры и оригиналы сохраняются.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
