package com.kidsync.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kidsync.app.R
import com.kidsync.app.ui.theme.PasswordMedium
import com.kidsync.app.ui.theme.PasswordStrong
import com.kidsync.app.ui.theme.PasswordWeak

/**
 * Password strength levels with associated display properties.
 */
enum class PasswordStrength {
    NONE,
    WEAK,
    MEDIUM,
    STRONG;

    companion object {
        /**
         * Evaluate password strength based on length, character variety, and patterns.
         */
        fun evaluate(password: String): PasswordStrength {
            if (password.isEmpty()) return NONE
            if (password.length < 8) return WEAK

            var score = 0
            if (password.length >= 8) score++
            if (password.length >= 12) score++
            if (password.any { it.isUpperCase() }) score++
            if (password.any { it.isLowerCase() }) score++
            if (password.any { it.isDigit() }) score++
            if (password.any { !it.isLetterOrDigit() }) score++

            return when {
                score >= 5 -> STRONG
                score >= 3 -> MEDIUM
                else -> WEAK
            }
        }
    }
}

/**
 * Visual password strength meter showing a colored progress bar
 * with a text label indicating weak/medium/strong.
 */
@Composable
fun PasswordStrengthIndicator(
    password: String,
    modifier: Modifier = Modifier
) {
    val strength = PasswordStrength.evaluate(password)

    val progress by animateFloatAsState(
        targetValue = when (strength) {
            PasswordStrength.NONE -> 0f
            PasswordStrength.WEAK -> 0.33f
            PasswordStrength.MEDIUM -> 0.66f
            PasswordStrength.STRONG -> 1f
        },
        animationSpec = tween(durationMillis = 300),
        label = "password_strength_progress"
    )

    val color by animateColorAsState(
        targetValue = when (strength) {
            PasswordStrength.NONE -> MaterialTheme.colorScheme.outlineVariant
            PasswordStrength.WEAK -> PasswordWeak
            PasswordStrength.MEDIUM -> PasswordMedium
            PasswordStrength.STRONG -> PasswordStrong
        },
        animationSpec = tween(durationMillis = 300),
        label = "password_strength_color"
    )

    val strengthLabel = when (strength) {
        PasswordStrength.NONE -> ""
        PasswordStrength.WEAK -> stringResource(R.string.password_strength_weak)
        PasswordStrength.MEDIUM -> stringResource(R.string.password_strength_medium)
        PasswordStrength.STRONG -> stringResource(R.string.password_strength_strong)
    }

    val accessibilityDescription = when (strength) {
        PasswordStrength.NONE -> stringResource(R.string.cd_password_strength_none)
        PasswordStrength.WEAK -> stringResource(R.string.cd_password_strength_weak)
        PasswordStrength.MEDIUM -> stringResource(R.string.cd_password_strength_medium)
        PasswordStrength.STRONG -> stringResource(R.string.cd_password_strength_strong)
    }

    if (password.isNotEmpty()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .semantics { contentDescription = accessibilityDescription }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    strokeCap = StrokeCap.Round
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = strengthLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = color
                )
            }
        }
    }
}
