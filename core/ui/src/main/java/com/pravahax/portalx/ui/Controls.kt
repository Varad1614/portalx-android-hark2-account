package com.pravahax.portalx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pravahax.portalx.data.Dates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** v0.7.1: form controls and small pieces shared by more than one feature module. */

/** Whether the device currently has a network; provided by the app shell. */
val LocalOnline = compositionLocalOf { true }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRowChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        options.forEach { (v, l) ->
            FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(l) },
                leadingIcon = if (v == selected) ({ Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }) else null,
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer, selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer))
        }
    }
}

/** Date picker in UTC millis (as Material requires) converted to/from ISO dates; [selectable] limits the range. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateDialog(initial: String?, onPick: (String) -> Unit, onDismiss: () -> Unit, selectable: (LocalDate) -> Boolean = { true }) {
    val init = runCatching { LocalDate.parse(initial).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = init ?: Dates.today().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = selectable(Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate())
        },
    )
    DatePickerDialog(onDismissRequest = onDismiss, confirmButton = {
        TextButton(onClick = {
            state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) } ?: onDismiss()
        }) { Text("OK") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }) { DatePicker(state) }
}

@Composable
fun CircleIcon(icon: ImageVector, tint: Color, size: androidx.compose.ui.unit.Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Picker(label: String, none: String?, options: List<Pair<T, String>>, selected: T?, onSelect: (T?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(options.firstOrNull { it.first == selected }?.second ?: none ?: "Select…", {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(expanded, { expanded = false }) {
            if (none != null) DropdownMenuItem({ Text(none) }, { onSelect(null); expanded = false })
            options.forEach { (id, name) -> DropdownMenuItem({ Text(name) }, { onSelect(id); expanded = false }) }
        }
    }
}
