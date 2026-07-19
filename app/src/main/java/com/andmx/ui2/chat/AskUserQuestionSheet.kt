package com.andmx.ui2.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.agent.zcode.AskOption
import com.andmx.agent.zcode.AskQuestion
import com.andmx.agent.zcode.AskUserQuestionParser

@Composable
fun AskUserQuestionPanel(
    request: ChatController.ApprovalRequest,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val questions = request.questions
    val selectedLabels = remember(request.id) { mutableStateMapOf<String, String>() }
    val otherText = remember(request.id) { mutableStateMapOf<String, String>() }
    val useOther = remember(request.id) { mutableStateMapOf<String, Boolean>() }
    val notes = remember(request.id) { mutableStateMapOf<String, String>() }
    val focusedPreview = remember(request.id) { mutableStateMapOf<String, String>() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Column(
            Modifier
                .padding(12.dp)
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.HelpOutline,
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "需要你的决定",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            questions.forEachIndexed { index, q ->
                val picks = selectedLabels[q.question].orEmpty()
                    .split("|||")
                    .filter { it.isNotBlank() }
                    .toSet()
                QuestionBlock(
                    question = q,
                    selected = picks,
                    otherEnabled = useOther[q.question] == true,
                    otherValue = otherText[q.question].orEmpty(),
                    notesValue = notes[q.question].orEmpty(),
                    preview = focusedPreview[q.question],
                    onToggleOption = { opt ->
                        val cur = selectedLabels[q.question].orEmpty()
                            .split("|||")
                            .filter { it.isNotBlank() }
                            .toMutableList()
                        if (q.multiSelect) {
                            if (cur.contains(opt.label)) cur.remove(opt.label) else cur.add(opt.label)
                        } else {
                            cur.clear()
                            cur.add(opt.label)
                            useOther[q.question] = false
                        }
                        selectedLabels[q.question] = cur.joinToString("|||")
                        focusedPreview[q.question] = opt.preview.orEmpty()
                    },
                    onToggleOther = {
                        val enabled = !(useOther[q.question] == true)
                        useOther[q.question] = enabled
                        if (enabled && !q.multiSelect) {
                            selectedLabels[q.question] = ""
                        }
                    },
                    onOtherChange = { otherText[q.question] = it },
                    onNotesChange = { notes[q.question] = it },
                )
                if (index < questions.lastIndex) Spacer(Modifier.height(12.dp))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onCancel) { Text("取消") }
                Button(
                    onClick = {
                        val answers = linkedMapOf<String, String>()
                        val annotations = linkedMapOf<String, Pair<String?, String?>>()
                        questions.forEach { q ->
                            val otherOn = useOther[q.question] == true
                            val other = otherText[q.question].orEmpty().trim()
                            val picks = selectedLabels[q.question].orEmpty()
                                .split("|||")
                                .filter { it.isNotBlank() }
                            val value = when {
                                otherOn && other.isNotBlank() && picks.isNotEmpty() && q.multiSelect ->
                                    (picks + other).joinToString(", ")
                                otherOn && other.isNotBlank() -> other
                                picks.isNotEmpty() -> picks.joinToString(", ")
                                otherOn -> other.ifBlank { "Other" }
                                else -> ""
                            }
                            if (value.isNotBlank()) answers[q.question] = value
                            val prev = focusedPreview[q.question]?.ifBlank { null }
                            val note = notes[q.question]?.ifBlank { null }
                            if (prev != null || note != null) {
                                annotations[q.question] = prev to note
                            }
                        }
                        if (answers.isEmpty()) {
                            onCancel()
                        } else {
                            onSubmit(AskUserQuestionParser.formatAnswersJson(questions, answers, annotations))
                        }
                    },
                ) { Text("提交") }
            }
        }
    }
}

@Composable
private fun QuestionBlock(
    question: AskQuestion,
    selected: Set<String>,
    otherEnabled: Boolean,
    otherValue: String,
    notesValue: String,
    preview: String?,
    onToggleOption: (AskOption) -> Unit,
    onToggleOther: () -> Unit,
    onOtherChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
) {
    val hasPreview = question.options.any { !it.preview.isNullOrBlank() } && !question.multiSelect
    Column(Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Text(
                question.header,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            question.question,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
        )
        if (hasPreview) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    question.options.forEach { opt ->
                        OptionRow(
                            option = opt,
                            checked = selected.contains(opt.label),
                            multi = question.multiSelect,
                            onClick = { onToggleOption(opt) },
                        )
                    }
                    OtherRow(enabled = otherEnabled, onClick = onToggleOther)
                }
                if (!preview.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 80.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Text(
                            preview,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                        )
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                question.options.forEach { opt ->
                    OptionRow(
                        option = opt,
                        checked = selected.contains(opt.label),
                        multi = question.multiSelect,
                        onClick = { onToggleOption(opt) },
                    )
                }
                OtherRow(enabled = otherEnabled, onClick = onToggleOther)
            }
        }
        if (otherEnabled) {
            OutlinedTextField(
                value = otherValue,
                onValueChange = onOtherChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                placeholder = { Text("自定义回答") },
                singleLine = false,
                minLines = 1,
                maxLines = 3,
            )
        }
        OutlinedTextField(
            value = notesValue,
            onValueChange = onNotesChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            placeholder = { Text("备注（可选）") },
            singleLine = true,
        )
    }
}

@Composable
private fun OptionRow(
    option: AskOption,
    checked: Boolean,
    multi: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val borderColor = if (checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (checked) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            null,
            Modifier.size(18.dp),
            tint = if (checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                option.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (option.description.isNotBlank()) {
                Text(
                    option.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OtherRow(enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val borderColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (enabled) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            null,
            Modifier.size(18.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text("Other", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ExitPlanApprovalPanel(
    request: ChatController.ApprovalRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Column(
            Modifier
                .padding(12.dp)
                .heightIn(max = 360.dp),
        ) {
            Text(
                "审批实现计划",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "确认后将退出计划模式并开始实现",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 240.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Text(
                    request.planText.ifBlank { request.summary },
                    modifier = Modifier
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onDeny) { Text("继续修改") }
                Button(onClick = onAllow) { Text("批准实现") }
            }
        }
    }
}
