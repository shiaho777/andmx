package com.andmx.ui.computeruse

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andmx.computeruse.AndmxAccessibilityService
import com.andmx.computeruse.MediaProjectionManagerHolder
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing

/**
 * Computer Use permission gate — shown when the user enables Computer Use or
 * when the agent first needs it. It checks the two Android grants required for
 * screen operation and offers one-tap entry to the right system screen:
 *
 *  1. **MediaProjection** (screen capture) — requested via the Activity's
 *     [onRequestScreenCapture] (launches the system consent dialog).
 *  2. **AccessibilityService** (tap/swipe/type) — can only be toggled by the
 *     user in Settings → Accessibility, so we deep-link there.
 *
 * Neither grant can be auto-granted; this gate just makes the path obvious.
 */
@Composable
fun PermissionGate(
    onRequestScreenCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = AndmxTheme.colors

    val projectionGranted = MediaProjectionManagerHolder.isAuthorized
    val a11yGranted = isAccessibilityEnabled(context)

    Column(modifier.fillMaxWidth()) {
        Text(
            "Computer Use 需要两项权限。仅在你主动使用屏幕操作功能时生效,AndMX 不会静默操作屏幕。",
            style = AndmxTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(Spacing.md))

        PermissionRow(
            icon = Icons.Outlined.ScreenShare,
            title = "屏幕录制",
            subtitle = if (projectionGranted) "已授权" else "用于截取屏幕画面供 AI 分析",
            granted = projectionGranted,
            actionLabel = if (projectionGranted) null else "授权",
            onAction = onRequestScreenCapture,
        )
        Spacer(Modifier.height(Spacing.sm))

        PermissionRow(
            icon = Icons.Outlined.AccessibilityNew,
            title = "无障碍服务",
            subtitle = if (a11yGranted) "已开启" else "用于点击、滑动、输入(需在系统设置中手动开启)",
            granted = a11yGranted,
            actionLabel = if (a11yGranted) null else "去设置",
            onAction = { openAccessibilitySettings(context) },
        )
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    val colors = AndmxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Radii.sm)
            .border(1.dp, colors.border, Radii.sm)
            .background(colors.surface, Radii.sm)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (granted) Icons.Outlined.CheckCircle else icon,
            contentDescription = null,
            tint = if (granted) colors.accent else colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = AndmxTheme.typography.labelLarge, color = colors.textPrimary)
            Text(subtitle, style = AndmxTheme.typography.bodySmall, color = colors.textTertiary)
        }
        if (actionLabel != null) {
            Text(
                actionLabel,
                style = AndmxTheme.typography.labelMedium,
                color = colors.onAccent,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.sendActive, RoundedCornerShape(999.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            )
        }
    }
}

/** True if our [AndmxAccessibilityService] is enabled in system settings. */
fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(context, AndmxAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

/** Deep-link to the system Accessibility settings so the user can toggle our service. */
fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
