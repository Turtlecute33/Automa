package app.automa.ui.term

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.automa.term.Look
import app.automa.ui.Icons
import com.termux.view.TerminalView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Bg = Color(Look.BACKGROUND)
private val Fg = Color(Look.FOREGROUND)
private val BarBg = lerp(Bg, Fg, 0.03f)
private val KeyBg = lerp(Bg, Fg, 0.08f)
private val Accent = Color(Look.ANSI[4])
private val KeyShape = RoundedCornerShape(10.dp)

/**
 * Two rows above the keyboard, in the terminal colours, as in Varco (DESIGN §5). The arrows are on the right:
 * [● session ▾] ESC TAB CTRL      ↑
 * ALT ⎘ / | -                  ← ↓ →
 * Arrows repeat while held. ⎘ pastes the clipboard. A long press on "/", "|" and "-" sends "\", "&" and "_".
 * A tap on CTRL or ALT applies it to the next key; a long press locks it.
 * Each key is a button for TalkBack, with a spoken name, the long press as an action and the CTRL/ALT state.
 * Rows are 48 dp high (touch target guideline).
 */
@Composable
fun ExtraKeys(
    view: () -> TerminalView?,
    mods: Modifiers,
    chipLabel: String,
    chipDot: Color,
    chipState: String,
    onChip: () -> Unit,
    onPaste: () -> Unit,
) {
    fun key(code: Int) {
        view()?.handleKeyCode(code, mods.keyMod())
    }

    fun char(c: Char) {
        view()?.inputCodePoint(c.code, mods.ctrl.consume(), mods.alt.consume())
    }

    Row(
        Modifier.fillMaxWidth().background(BarBg).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Each part is a traversal group, so TalkBack reads the left keys first, then the arrows.
        Column(Modifier.weight(5f).semantics { isTraversalGroup = true }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip(chipLabel, chipDot, chipState, onChip, Modifier.weight(2.6f))
                Key("ESC", "Escape", onTap = { key(KeyEvent.KEYCODE_ESCAPE) })
                Key("TAB", "Tab", onTap = { key(KeyEvent.KEYCODE_TAB) })
                Mod("CTRL", "Control", mods.ctrl)
            }
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Mod("ALT", "Alt", mods.alt)
                IconKey(Icons.Paste, "Paste", onPaste)
                Key("/", "Slash", alt = "\\", altSpoken = "Type backslash", onTap = { char('/') }, onLong = { char('\\') })
                Key("|", "Vertical bar", alt = "&", altSpoken = "Type ampersand", onTap = { char('|') }, onLong = { char('&') })
                Key("-", "Minus", alt = "_", altSpoken = "Type underscore", onTap = { char('-') }, onLong = { char('_') })
            }
        }
        // 3/8 of the width, so that each arrow is about as wide as a key on the left. The empty cells keep ↑ above ↓.
        Column(Modifier.weight(3f).semantics { isTraversalGroup = true }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Spacer(Modifier.weight(1f))
                Key("↑", "Up arrow", repeat = true, onTap = { key(KeyEvent.KEYCODE_DPAD_UP) })
                Spacer(Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Key("←", "Left arrow", repeat = true, onTap = { key(KeyEvent.KEYCODE_DPAD_LEFT) })
                Key("↓", "Down arrow", repeat = true, onTap = { key(KeyEvent.KEYCODE_DPAD_DOWN) })
                Key("→", "Right arrow", repeat = true, onTap = { key(KeyEvent.KEYCODE_DPAD_RIGHT) })
            }
        }
    }
}

@Composable
private fun RowScope.Key(
    label: String,
    spoken: String,
    alt: String? = null,
    altSpoken: String? = null,
    repeat: Boolean = false,
    onTap: () -> Unit,
    onLong: (() -> Unit)? = null,
) {
    val view = LocalView.current
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    fun haptic() = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(KeyShape)
            .background(KeyBg)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = spoken
                onClick { tap(); true }
                if (onLong != null) onLongClick(altSpoken) { long?.invoke(); true }
            }
            .pointerInput(repeat, onLong != null) {
                if (repeat) {
                    // Sends once on press, then repeats after 400 ms while the finger stays down.
                    detectTapGestures(onPress = {
                        haptic()
                        tap()
                        coroutineScope {
                            val repeater = launch {
                                delay(400)
                                while (true) {
                                    tap()
                                    delay(50)
                                }
                            }
                            tryAwaitRelease()
                            repeater.cancel()
                        }
                    })
                } else {
                    detectTapGestures(
                        onTap = { haptic(); tap() },
                        onLongPress = if (onLong != null) { _ -> haptic(); long?.invoke() } else null,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Fg, fontFamily = FontFamily.Monospace, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (alt != null) {
            Text(
                alt,
                color = Fg.copy(alpha = 0.45f),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 5.dp),
            )
        }
    }
}

@Composable
private fun RowScope.Mod(label: String, spoken: String, key: ModKey) {
    val view = LocalView.current
    val (keyBg, keyFg) = when (key.mode) {
        ModKey.Mode.OFF -> KeyBg to Fg
        ModKey.Mode.ONCE -> lerp(KeyBg, Accent, 0.45f) to Fg
        ModKey.Mode.LOCKED -> Accent to Bg
    }
    val (state, longLabel) = when (key.mode) {
        ModKey.Mode.OFF -> "Off" to "Lock"
        ModKey.Mode.ONCE -> "Next key" to "Lock"
        ModKey.Mode.LOCKED -> "Locked" to "Unlock"
    }
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(KeyShape)
            .background(keyBg)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = spoken
                stateDescription = state
                onClick { key.tap(); true }
                onLongClick(longLabel) { key.lock(); true }
            }
            .pointerInput(key) {
                detectTapGestures(
                    onTap = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); key.tap() },
                    onLongPress = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); key.lock() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = keyFg, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RowScope.IconKey(icon: ImageVector, spoken: String, onTap: () -> Unit) {
    val view = LocalView.current
    val tap by rememberUpdatedState(onTap)
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(KeyShape)
            .background(KeyBg)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = spoken
                onClick { tap(); true }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); tap() }) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Fg, modifier = Modifier.size(20.dp))
    }
}

/** The session: its name and state dot. A tap opens the session sheet. TalkBack says the [state] (review DS-L-68). */
@Composable
private fun Chip(label: String, dot: Color, state: String, onTap: () -> Unit, modifier: Modifier) {
    val tap by rememberUpdatedState(onTap)
    Row(
        modifier
            .fillMaxHeight()
            .widthIn(min = 64.dp)
            .clip(KeyShape)
            .background(KeyBg)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                stateDescription = state
                onClick("Show sessions") { tap(); true }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.size(8.dp))
        Text(label, color = Fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("▾", color = Fg.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}
