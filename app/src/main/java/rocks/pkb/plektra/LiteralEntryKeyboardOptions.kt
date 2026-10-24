package rocks.pkb.plektra

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PlatformImeOptions
import rocks.pkb.plektra.core.InputContextState

/** Preserve deliberate spelling in dictionary, replacement and snippet editors. */
internal val LiteralEntryKeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    platformImeOptions = PlatformImeOptions(
        privateImeOptions = InputContextState.LITERAL_ENTRY_IME_OPTION
    )
)
