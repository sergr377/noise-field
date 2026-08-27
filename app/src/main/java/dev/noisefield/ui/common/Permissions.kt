package dev.noisefield.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.noisefield.ui.theme.Palette

/** Разрешения, без которых замер физически невозможен. */
val CAPTURE_PERMISSIONS: List<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

fun hasCapturePermissions(context: Context): Boolean = CAPTURE_PERMISSIONS.all { permission ->
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Запрашивает разрешения при первом входе на экран, где нужен микрофон.
 * При отказе объясняет, что именно теперь не работает, и ведёт в настройки —
 * молчаливая неработающая кнопка в поле хуже всего.
 */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCapturePermissions(context)) }
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.all { it.value } || hasCapturePermissions(context)
        asked = true
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(CAPTURE_PERMISSIONS.toTypedArray())
    }

    if (granted) {
        content()
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Lbl("Нет разрешений")
        Text(
            text = "Приложению нужны микрофон — чтобы мерить уровень, " +
                "геопозиция — чтобы у замера были координаты, и уведомления — " +
                "чтобы замер не оборвался при погашенном экране.\n\n" +
                "Аудио никуда не пишется и не передаётся: наружу выходят только уровни.",
            fontSize = 14.sp,
            color = Palette.Ink2,
        )
        PrimaryButton(if (asked) "Открыть настройки" else "Дать разрешения") {
            if (asked) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                launcher.launch(CAPTURE_PERMISSIONS.toTypedArray())
            }
        }
    }
}