package org.factorio.local.launcher.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.factorio.local.launcher.R
import org.factorio.local.launcher.ui.theme.Copper
import org.factorio.local.launcher.ui.theme.Muted

@Composable
internal fun LauncherStatus(busy: Boolean, message: String, progress: Float) {
    if (busy || message.isNotEmpty()) {
        Column(
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (busy) {
                if (progress >= 0f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (message.isNotEmpty())
                Text(message, color = Copper, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun Eyebrow() {
    Text(
        "F A C T O R I O",
        color = Copper,
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = 2.sp,
    )
}

@Composable
internal fun Hint(id: Int) {
    Text(stringResource(id), color = Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun Field(title: Int, hint: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    Hint(hint)
}

@Composable
internal fun Datum(title: Int, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), color = Muted, style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
internal fun FactoryPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().factoryWebPanelSurface().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
internal fun FactoryButton(
    label: Int,
    enabled: Boolean,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FactoryWebButton(enabled, primary, modifier.fillMaxWidth(), onClick) {
        Text(stringResource(label))
    }
}

@Composable
internal fun Choice(
    labels: List<Int>,
    selected: Int,
    field: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth
        FactoryWebButton(
            enabled,
            modifier = Modifier.fillMaxWidth(),
            onClick = { expanded = true },
        ) {
            Text(
                stringResource(field) + " · " + stringResource(labels[selected]),
                Modifier.weight(1f),
            )
            Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(24.dp))
        }
        DropdownMenu(
            expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(width),
        ) {
            labels.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        expanded = false
                        onSelect(index)
                    },
                )
            }
        }
    }
}
