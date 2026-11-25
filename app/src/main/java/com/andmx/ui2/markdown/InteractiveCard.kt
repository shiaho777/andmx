package com.andmx.ui2.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andmx.ui2.chat.ChatComposerBus
import com.andmx.ui2.settings.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class InteractiveCardSpec(val title: String, val fields: List<InteractiveField>) {
    companion object {
        private val fieldId = Regex("[A-Za-z][A-Za-z0-9_-]{0,39}")

        fun parse(raw: String): InteractiveCardSpec? {
            if (raw.length > 8192) return null
            return runCatching { Json.decodeFromString<InteractiveCardSpec>(raw) }.getOrNull()?.takeIf { card ->
                card.title.isNotBlank() && card.title.length <= 120 &&
                    card.fields.size in 1..8 &&
                    card.fields.map { it.id }.distinct().size == card.fields.size &&
                    card.fields.all { field ->
                        fieldId.matches(field.id) && field.label.isNotBlank() && field.label.length <= 100 &&
                            when (field.type) {
                                "text", "toggle" -> field.options.isEmpty()
                                "choice" -> field.options.size in 2..8 &&
                                    field.options.distinct().size == field.options.size &&
                                    field.options.all { it.isNotBlank() && it.length <= 80 }
                                else -> false
                            }
                    }
            }
        }
    }
}

@Serializable
internal data class InteractiveField(
    val id: String,
    val label: String,
    val type: String,
    val options: List<String> = emptyList(),
)

@Composable
internal fun InteractiveCard(spec: InteractiveCardSpec) {
    var serialized by rememberSaveable(spec) { mutableStateOf("{}") }
    val values = Json.decodeFromString<Map<String, String>>(serialized)
    fun set(id: String, value: String) {
        serialized = Json.encodeToString(values + (id to value))
    }
    DisableSelection {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(spec.title, style = MaterialTheme.typography.titleSmall)
                spec.fields.forEach { field ->
                    val value = values[field.id].orEmpty()
                    when (field.type) {
                        "text" -> OutlinedTextField(
                            value = value,
                            onValueChange = { set(field.id, it.take(300)) },
                            label = { Text(field.label) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        "toggle" -> Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(field.label, Modifier.weight(1f))
                            Switch(checked = value == "true", onCheckedChange = { set(field.id, it.toString()) })
                        }
                        "choice" -> Column {
                            Text(field.label)
                            field.options.forEach { option ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = value == option, onClick = { set(field.id, option) })
                                    Text(option)
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = {
                    val submitted = spec.fields.associate { field ->
                        field.id to (values[field.id] ?: if (field.type == "toggle") "false" else "")
                    }
                    ChatComposerBus.insert("[Interactive card: ${spec.title}]\n${Json.encodeToString(submitted)}")
                }) { Text(tr("将选项加入对话")) }
            }
        }
    }
}
