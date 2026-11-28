package com.ootruffle.clienttag.config

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.polyfrost.compose.text.Font

/** A dark palette in the style of Polyfrost's UIs. */
private object Palette {
    val Scrim = Color(0x8C000000)
    val Card = Color(0xFF171C23)
    val Border = Color(0xFF2A323C)
    val Text = Color(0xFFEDF0F3)
    val TextSecondary = Color(0xFFA0A8B2)
    val Accent = Color(0xFF2155F5)
    val AccentText = Color.White
    val Subtle = Color(0xFF232A33)
    val Warning = Color(0xFFF2B33D)
}

private val CardShape = RoundedCornerShape(14.dp)
private val ButtonShape = RoundedCornerShape(8.dp)

/** PolyCompose ships Inter. */
private const val INTER = "assets/polycompose/fonts/inter"

private val Inter: FontFamily by lazy {
    FontFamily(
        Font("$INTER/Inter-Regular.ttf", FontWeight.Normal),
        Font("$INTER/Inter-Medium.ttf", FontWeight.Medium),
        Font("$INTER/Inter-SemiBold.ttf", FontWeight.SemiBold),
    )
}

/**
 * The consent question's content, drawn with PolyCompose. [ConsentScreen] hosts it - in Java, so
 * the Kotlin compiler never reads the Minecraft supertype from PolyCompose's metadata, which isn't
 * remapped on 1.8.9. Only reached through [ConsentPrompt], which checks PolyCompose is loaded first.
 */
fun consentContent(heading: String, message: String, yes: String, no: String, readyAt: Long, answer: (Boolean) -> Unit): @Composable () -> Unit {
    // The last paragraph is the question, drawn a little stronger.
    val paragraphs = message.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
    return { ConsentDialog(heading, paragraphs, yes, no, readyAt, answer) }
}

@Composable
private fun ConsentDialog(heading: String, paragraphs: List<String>, yes: String, no: String, readyAt: Long, answer: (Boolean) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (now < readyAt) {
            withFrameNanos { }
            now = System.currentTimeMillis()
        }
    }
    val secondsLeft = ((readyAt - now + 999) / 1000).coerceAtLeast(0).toInt()
    val ready = secondsLeft == 0

    Box(
        Modifier.fillMaxSize().background(Palette.Scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 540.dp)
                .background(Palette.Card, CardShape)
                .border(1.dp, Palette.Border, CardShape)
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Palette.Warning, CircleShape))
                Spacer(Modifier.width(10.dp))
                BasicText(
                    heading,
                    style = TextStyle(color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = Inter),
                )
            }
            Spacer(Modifier.height(18.dp))
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                paragraphs.forEachIndexed { i, paragraph ->
                    val question = i == paragraphs.lastIndex && paragraphs.size > 1
                    BasicText(
                        paragraph,
                        style = TextStyle(
                            color = if (question) Palette.Text else Palette.TextSecondary,
                            fontSize = if (question) 15.sp else 14.sp,
                            lineHeight = 21.sp,
                            fontWeight = if (question) FontWeight.Medium else FontWeight.Normal,
                            fontFamily = Inter,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                DialogButton(no, primary = false, enabled = ready) { answer(false) }
                DialogButton(if (ready) yes else "$yes ($secondsLeft)", primary = true, enabled = ready) { answer(true) }
            }
        }
    }
}

@Composable
private fun DialogButton(text: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val base = if (primary) Palette.Accent else Color.Transparent
    val hover = if (primary) Palette.Accent.copy(alpha = 0.82f) else Palette.Subtle
    val background by animateColorAsState(if (hovered && enabled) hover else base)
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.45f)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .background(background, ButtonShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = TextStyle(
                color = if (primary) Palette.AccentText else Palette.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = Inter,
            ),
        )
    }
}
