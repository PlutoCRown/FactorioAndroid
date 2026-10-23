package org.factorio.local.launcher.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.util.Locale
import org.factorio.local.launcher.R
import org.factorio.local.launcher.ui.components.*

@Composable
internal fun LauncherDevice(s: LauncherState, actions: LauncherActions, memory: Long) {
    Eyebrow()
    Text(stringResource(R.string.device_info), style = MaterialTheme.typography.headlineMedium)
    Hint(R.string.device_hint)
    FactoryPanel {
        Datum(R.string.device_label, "${Build.MANUFACTURER} ${Build.MODEL}")
        Datum(R.string.system_label, "Android ${Build.VERSION.RELEASE}")
        Datum(
            R.string.memory_label,
            String.format(Locale.ROOT, "%.1f GB", memory / 1_000_000_000.0),
        )
        Datum(R.string.gpu_label, s.gpu ?: stringResource(R.string.waiting_for_check))
        Datum(
            R.string.graphics_api,
            s.api
                ?: stringResource(
                    if (s.gpu == null) R.string.waiting_for_check else R.string.unavailable
                ),
        )
    }
    FactoryButton(
        if (s.graphicsPassed == null) R.string.check_device else R.string.recheck_device,
        !s.busy,
        true,
    ) {
        actions.action("probe")
    }
    Hint(
        when (s.graphicsPassed) {
            null -> R.string.device_check_local
            true -> R.string.device_check_pass
            false -> R.string.device_check_fail
        }
    )
    FactoryButton(R.string.open_logs_folder, !s.busy) { actions.action("logs") }
}
