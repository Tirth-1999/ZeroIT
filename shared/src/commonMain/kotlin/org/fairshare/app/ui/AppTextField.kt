package org.fairshare.app.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

internal enum class FieldInput { TEXT, NAME, SEARCH, CURRENCY, DECIMAL, DATE }

internal fun fieldKeyboardOptions(input: FieldInput, multiline: Boolean = false) = KeyboardOptions(
    keyboardType = when (input) {
        FieldInput.DECIMAL -> KeyboardType.Decimal
        FieldInput.CURRENCY, FieldInput.DATE -> KeyboardType.Ascii
        else -> KeyboardType.Text
    },
    capitalization = when (input) {
        FieldInput.NAME -> KeyboardCapitalization.Words
        FieldInput.CURRENCY -> KeyboardCapitalization.Characters
        else -> KeyboardCapitalization.None
    },
    autoCorrectEnabled = input == FieldInput.TEXT || input == FieldInput.NAME,
    imeAction = if (multiline) ImeAction.Default else if (input == FieldInput.SEARCH) ImeAction.Search else ImeAction.Done,
)

/** Shared keyboard contract for every editable field, including legacy value-based fields. */
@Composable
internal fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    input: FieldInput = FieldInput.TEXT,
    signed: Boolean = false,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused, enabled) {
        if (focused && enabled) {
            // Wait until the native text-input session has been attached.
            withFrameNanos { }
            keyboard?.show()
        }
    }
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        modifier = modifier.onFocusChanged { focused = it.isFocused }, enabled = enabled,
        singleLine = singleLine, minLines = minLines, label = label, placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon ?: if (signed) {{
            // iOS's decimal pad has no minus key; keep signed adjustments numeric.
            IconButton(onClick = { onValueChange(if (value.startsWith("-")) value.drop(1) else "-$value") }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "Toggle positive or negative" }) { Text("±") }
        }} else null,
        shape = shape, textStyle = textStyle,
        keyboardOptions = fieldKeyboardOptions(input, multiline = !singleLine),
    )
}
