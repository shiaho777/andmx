package com.andmx.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andmx.diff.DiffLine
import com.andmx.ui.theme.AndmxCodeTextStyle
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing

// ── Shared diff colors (formerly duplicated in MessageList + DiffPane) ──────

val DiffAddBg = Color(0x1A2EA043)
val DiffDelBg = Color(0x1AF85149)
val DiffAddFg = Color(0xFF2EA043)
val DiffDelFg = Color(0xFFF85149)

// ── 1. SelectableChip ───────────────────────────────────────────────────────

/**
 * A unified selectable chip/tab/pill — the single replacement for the ~15
 * near-identical private chip definitions that were scattered across the UI
 * (SegChip, ProviderChip, WorkTabChip, ProgressTab, CategoryChip, etc.).
 *
 * Optional [icon] and [badge] cover the WorkTabChip/ProgressTab variants;
 * their absence covers the plain SegChip/ProviderChip form.
 */
@Composable
fun SelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    badge: Int = 0,
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    val bg by animateColorAsState(if (selected) colors.selected else Color.Transparent, label = "chipBg")
    val fg by animateColorAsState(if (selected) colors.textPrimary else colors.textSecondary, label = "chipFg")
    val bd by animateColorAsState(if (selected) colors.borderStrong else colors.border, label = "chipBd")
    Row(
        modifier
            .clip(Radii.sm)
            .background(bg)
            .border(1.dp, bd, Radii.sm)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(
            label,
            style = AndmxTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (badge > 0) {
            Spacer(Modifier.width(Spacing.xs))
            Box(
                Modifier.clip(Radii.pill).background(colors.accent)
                    .padding(horizontal = Spacing.xs, vertical = 1.dp),
            ) {
                Text("$badge", style = AndmxTheme.typography.labelSmall, color = colors.onAccent)
            }
        }
    }
}

// ── 2. IconActionButton ─────────────────────────────────────────────────────

/** Visual style for an [IconActionButton]. */
enum class IconVariant { PLAIN, PRIMARY, WARNING, SELECTED }

/**
 * A unified icon button — replaces CircleIconButton, GoalIconAction,
 * HeaderIconAction, ChromeButton and similar private variants.
 */
@Composable
fun IconActionButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String = "",
    enabled: Boolean = true,
    variant: IconVariant = IconVariant.PLAIN,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    val bg = when (variant) {
        IconVariant.PRIMARY -> colors.sendActive
        IconVariant.WARNING -> colors.warningSoft
        IconVariant.SELECTED -> colors.selected
        IconVariant.PLAIN -> colors.sunken
    }
    val tint = when {
        !enabled -> colors.textTertiary
        variant == IconVariant.PRIMARY -> colors.onAccent
        variant == IconVariant.WARNING -> colors.warning
        else -> colors.textSecondary
    }
    Box(
        modifier
            .size(size)
            .clip(Radii.pill)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

// ── 3. InfoRow ──────────────────────────────────────────────────────────────

/**
 * A label-value row — replaces the ~6 private InfoRow/InfoLine/GoalMetaRow/
 * InstructionStatusRow/ProgressSourceRow variants.
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    labelWidth: Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    Row(modifier.fillMaxWidth().padding(vertical = Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, modifier = Modifier.width(labelWidth))
        Text(value, style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── 4. FormField ────────────────────────────────────────────────────────────

/**
 * A labelled text input — replaces the private Field in SettingsSheet and
 * GoalEditField in ChatPane.
 */
@Composable
fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    password: Boolean = false,
    singleLine: Boolean = true,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    val colors = AndmxTheme.colors
    Column(modifier.fillMaxWidth()) {
        Text(label, style = AndmxTheme.typography.labelMedium, color = colors.textSecondary)
        Spacer(Modifier.size(Spacing.xs))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = AndmxTheme.typography.bodyMedium.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colors.border, Radii.sm)
                .background(colors.surface, Radii.sm)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder != null) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(placeholder, style = AndmxTheme.typography.bodyMedium, color = colors.textTertiary)
                        inner()
                    }
                } else {
                    inner()
                }
            },
        )
    }
}

// ── 5. DiffLineRow ──────────────────────────────────────────────────────────

/**
 * Renders a single diff line with color-coded background — replaces the three
 * private InlineDiffRow / DiffRow / DiffLine(ApprovalDialog) implementations.
 */
@Composable
fun DiffLineRow(line: DiffLine, modifier: Modifier = Modifier) {
    val colors = AndmxTheme.colors
    val sign = when (line.kind) {
        DiffLine.Kind.ADD -> "+"
        DiffLine.Kind.REMOVE -> "-"
        DiffLine.Kind.CONTEXT -> " "
    }
    val bg = when (line.kind) {
        DiffLine.Kind.ADD -> DiffAddBg
        DiffLine.Kind.REMOVE -> DiffDelBg
        DiffLine.Kind.CONTEXT -> Color.Transparent
    }
    val fg = when (line.kind) {
        DiffLine.Kind.ADD -> DiffAddFg
        DiffLine.Kind.REMOVE -> DiffDelFg
        DiffLine.Kind.CONTEXT -> colors.textTertiary
    }
    Row(modifier.fillMaxWidth().background(bg).padding(horizontal = Spacing.sm, vertical = 1.dp)) {
        Text((line.oldNo?.toString() ?: "").padStart(3), style = AndmxCodeTextStyle, color = colors.textTertiary, modifier = Modifier.width(28.dp))
        Text((line.newNo?.toString() ?: "").padStart(3), style = AndmxCodeTextStyle, color = colors.textTertiary, modifier = Modifier.width(28.dp))
        Text(sign, style = AndmxCodeTextStyle, color = fg)
        Spacer(Modifier.width(Spacing.xs))
        Text(line.text, style = AndmxCodeTextStyle, color = if (line.kind == DiffLine.Kind.CONTEXT) colors.textSecondary else fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

// ── 6. EmptyState ───────────────────────────────────────────────────────────

/**
 * A centered empty-state placeholder — replaces EmptyHint, EmptyMemoryState,
 * EmptyReferencePreview, EmptyProgressText.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    Box(modifier.fillMaxWidth().padding(Spacing.xxl), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.size(Spacing.md))
            Text(title, style = AndmxTheme.typography.titleSmall, color = colors.textSecondary)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.size(Spacing.xs))
                Text(subtitle, style = AndmxTheme.typography.bodySmall, color = colors.textTertiary, textAlign = TextAlign.Center)
            }
        }
    }
}
