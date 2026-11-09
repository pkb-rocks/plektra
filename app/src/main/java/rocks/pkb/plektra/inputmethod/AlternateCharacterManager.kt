package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.data.layout.LayoutMappingRepository
import rocks.pkb.plektra.data.layout.isRealMultiTap
import rocks.pkb.plektra.data.mappings.KeyMappingLoader
import rocks.pkb.plektra.data.mappings.AltModifierMappingResolver
import rocks.pkb.plektra.data.mappings.DeviceSymMappingRepository
import rocks.pkb.plektra.data.variation.VariationRepository
import rocks.pkb.plektra.core.AutoSpaceTracker
import rocks.pkb.plektra.core.DeferredPunctuationSpaceTracker
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates modifier-bound and SYM character mappings, long presses, and character insertion.
 */
class AlternateCharacterManager(
    private val assets: AssetManager,
    private val prefs: SharedPreferences,
    private val context: Context? = null,
    private val activeLayoutNameProvider: (() -> String?)? = null
) {
    // Callback invoked when an Alt character is inserted after a long press
    var onAltCharInserted: ((Char) -> Unit)? = null
    // Callback invoked when a normal character is confirmed (short press in Alt mode)
    var onNormalCharCommitted: ((String) -> Unit)? = null
    // Callback invoked before mapped punctuation is committed.
    var onBoundaryTextRequested: ((String, InputConnection) -> Boolean)? = null

    companion object {
        private const val TAG = "AlternateCharacterManager"
    }

    private fun autoSpacePunctuation(): String {
        return context?.let { SettingsManager.getAutoSpacePunctuation(it) }
            ?: rocks.pkb.plektra.core.Punctuation.DEFAULT_AUTO_SPACE
    }

    private fun handleBoundaryTextBeforeCommit(
        text: String,
        inputConnection: InputConnection
    ): Boolean {
        if (text.length != 1) return false
        val boundary = rocks.pkb.plektra.core.Punctuation.normalizeApostrophe(text[0])
        if (boundary == '\'' || boundary !in rocks.pkb.plektra.core.Punctuation.BOUNDARY) {
            return false
        }
        return onBoundaryTextRequested?.invoke(boundary.toString(), inputConnection) == true
    }

    private val handler = Handler(Looper.getMainLooper())

    private val altModifierMappings = mutableMapOf<Int, String>()
    private val deviceSymMappings = mutableMapOf<Int, String>()
    private val symKeyMap = mutableMapOf<Int, String>()
    private val symKeyMap2 = mutableMapOf<Int, String>()
    private val symKeyMapUppercase = mutableMapOf<Int, String>()
    private val symKeyMap2Uppercase = mutableMapOf<Int, String>()

    private val pressedKeys = ConcurrentHashMap<Int, Long>()
    private val longPressRunnables = ConcurrentHashMap<Int, Runnable>()
    private val longPressActivated = ConcurrentHashMap<Int, Boolean>()
    private val insertedNormalChars = ConcurrentHashMap<Int, String>()
    private val insertedTextAnchors = ConcurrentHashMap<Int, InsertedTextAnchor>()
    private val keyPressWasShifted = ConcurrentHashMap<Int, Boolean>()

    private var longPressThreshold: Long = 500L

    init {
        context?.let {
            altModifierMappings.putAll(AltModifierMappingResolver.resolveBaseMappings(assets, it))
            deviceSymMappings.putAll(DeviceSymMappingRepository.load(assets, it))
        }
        symKeyMap.putAll(KeyMappingLoader.loadSymKeyMappings(assets))
        symKeyMap2.putAll(KeyMappingLoader.loadSymKeyMappingsPage2(assets))
        symKeyMapUppercase.putAll(KeyMappingLoader.loadSymKeyMappingsUppercase(assets))
        symKeyMap2Uppercase.putAll(KeyMappingLoader.loadSymKeyMappingsPage2Uppercase(assets))
        reloadLongPressThreshold()
    }

    fun reloadLongPressThreshold() {
        longPressThreshold = prefs.getLong("long_press_threshold", 500L).coerceIn(50L, 1000L)
    }

    fun reloadModifierAndDeviceSymMappings() {
        altModifierMappings.clear()
        context?.let { altModifierMappings.putAll(AltModifierMappingResolver.resolveBaseMappings(assets, it)) }
        deviceSymMappings.clear()
        context?.let { deviceSymMappings.putAll(DeviceSymMappingRepository.load(assets, it)) }
    }

    fun getAltModifierMappings(): Map<Int, String> = LayoutMappingRepository.applyAltMappings(altModifierMappings)

    fun getDeviceSymMappings(): Map<Int, String> = deviceSymMappings

    fun getSymMappings(): Map<Int, String> = symKeyMap
    
    fun getSymMappings2(): Map<Int, String> = symKeyMap2

    fun getSymMappingsUppercase(): Map<Int, String> = symKeyMapUppercase

    fun getSymMappings2Uppercase(): Map<Int, String> = symKeyMap2Uppercase
    
    /**
     * Ricarica le mappature SYM, controllando prima le personalizzazioni.
     */
    fun reloadSymMappings() {
        if (context != null) {
            val customMappings = rocks.pkb.plektra.SettingsManager.getSymMappings(context)
            if (customMappings.isNotEmpty()) {
                symKeyMap.clear()
                symKeyMap.putAll(customMappings)
                symKeyMapUppercase.clear()
                Log.d(TAG, "Loaded custom SYM mappings: ${customMappings.size} entries")
            } else {
                // Use default mappings from JSON
                symKeyMap.clear()
                symKeyMap.putAll(KeyMappingLoader.loadSymKeyMappings(assets))
                symKeyMapUppercase.clear()
                symKeyMapUppercase.putAll(KeyMappingLoader.loadSymKeyMappingsUppercase(assets))
                Log.d(TAG, "Loaded default SYM mappings")
            }
        }
    }
    
    /**
     * Reloads SYM mappings for page 2, checking for custom mappings first.
     */
    fun reloadSymMappings2() {
        if (context != null) {
            val customMappings = rocks.pkb.plektra.SettingsManager.getSymMappingsPage2(context)
            if (customMappings.isNotEmpty()) {
                symKeyMap2.clear()
                symKeyMap2.putAll(customMappings)
                symKeyMap2Uppercase.clear()
                // Mircea Horea IONICĂ (mionica), pastiera 38be1cfb53.
                symKeyMap2.forEach { (key, value) -> symKeyMap2Uppercase[key] = value.uppercase() }
                Log.d(TAG, "Loaded custom SYM page 2 mappings: ${customMappings.size} entries")
            } else {
                // Use default mappings from JSON
                symKeyMap2.clear()
                symKeyMap2.putAll(KeyMappingLoader.loadSymKeyMappingsPage2(assets))
                symKeyMap2Uppercase.clear()
                symKeyMap2Uppercase.putAll(KeyMappingLoader.loadSymKeyMappingsPage2Uppercase(assets))
                Log.d(TAG, "Loaded default SYM page 2 mappings")
            }
        }
    }

    fun hasAltMapping(keyCode: Int): Boolean = getAltModifierMappings().containsKey(keyCode)

    fun hasSymLongPressMapping(keyCode: Int, shiftPressed: Boolean): Boolean {
        val page = context?.let(SettingsManager::resolveLongPressSymPage) ?: 1
        return if (page == 1) {
            if (shiftPressed && symKeyMapUppercase.containsKey(keyCode)) {
                true
            } else {
                symKeyMap.containsKey(keyCode)
            }
        } else {
            if (shiftPressed && symKeyMap2Uppercase.containsKey(keyCode)) {
                true
            } else {
                symKeyMap2.containsKey(keyCode)
            }
        }
    }

    fun hasPendingPress(keyCode: Int): Boolean = pressedKeys.containsKey(keyCode)

    fun addAltKeyMapping(keyCode: Int, character: String) {
        altModifierMappings[keyCode] = character
    }

    fun removeAltKeyMapping(keyCode: Int) {
        altModifierMappings.remove(keyCode)
    }

    fun resetTransientState() {
        longPressRunnables.values.forEach { handler.removeCallbacks(it) }
        longPressRunnables.clear()
        pressedKeys.clear()
        longPressActivated.clear()
        insertedNormalChars.clear()
        insertedTextAnchors.clear()
        keyPressWasShifted.clear()
    }

    fun buildEmojiMapText(): String {
        val keyLabels = mapOf(
            KeyEvent.KEYCODE_Q to "Q", KeyEvent.KEYCODE_W to "W", KeyEvent.KEYCODE_E to "E",
            KeyEvent.KEYCODE_R to "R", KeyEvent.KEYCODE_T to "T", KeyEvent.KEYCODE_Y to "Y",
            KeyEvent.KEYCODE_U to "U", KeyEvent.KEYCODE_I to "I", KeyEvent.KEYCODE_O to "O",
            KeyEvent.KEYCODE_P to "P", KeyEvent.KEYCODE_A to "A", KeyEvent.KEYCODE_S to "S",
            KeyEvent.KEYCODE_D to "D", KeyEvent.KEYCODE_F to "F", KeyEvent.KEYCODE_G to "G",
            KeyEvent.KEYCODE_H to "H", KeyEvent.KEYCODE_J to "J", KeyEvent.KEYCODE_K to "K",
            KeyEvent.KEYCODE_L to "L", KeyEvent.KEYCODE_Z to "Z", KeyEvent.KEYCODE_X to "X",
            KeyEvent.KEYCODE_C to "C", KeyEvent.KEYCODE_V to "V", KeyEvent.KEYCODE_B to "B",
            KeyEvent.KEYCODE_N to "N", KeyEvent.KEYCODE_M to "M", KeyEvent.KEYCODE_0 to "0", KeyEvent.KEYCODE_GRAVE to "$"
        )

        val rows = mutableListOf<String>()
        val keys = listOf(
            listOf(KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P),
            listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L),
            listOf(KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_M)
        )

        for (row in keys) {
            val rowText = row.joinToString("  ") { keyCode ->
                val label = keyLabels[keyCode] ?: ""
                val emoji = symKeyMap[keyCode] ?: ""
                "$label:$emoji"
            }
            rows.add(rowText)
        }

        return rows.joinToString("\n")
    }

    fun handleKeyWithAltMapping(
        keyCode: Int,
        event: KeyEvent?,
        capsLockEnabled: Boolean,
        inputConnection: InputConnection,
        shiftOneShot: Boolean = false,
        layoutChar: Char? = null // Optional character from keyboard layout
    ): Boolean {
        pressedKeys[keyCode] = System.currentTimeMillis()
        longPressActivated[keyCode] = false

        // Use centralized character retrieval from layout manager when key is mapped
        var normalChar = if (LayoutMappingRepository.isMapped(keyCode)) {
            LayoutMappingRepository.getCharacterStringWithModifiers(
                keyCode,
                isShiftPressed = event?.isShiftPressed == true,
                capsLockEnabled = capsLockEnabled,
                shiftOneShot = shiftOneShot
            )
        } else {
            // Fallback: use layout character if provided, otherwise fall back to event's unicode character
            if (layoutChar != null) {
                layoutChar.toString()
            } else if (event != null && event.unicodeChar != 0) {
                event.unicodeChar.toChar().toString()
            } else {
                ""
            }
        }

        // For unmapped keys, apply case conversion if needed (fallback only)
        if (normalChar.isNotEmpty() && !LayoutMappingRepository.isMapped(keyCode)) {
            // Gestisci shiftOneShot: se è attivo e il carattere è una lettera, rendilo maiuscolo
            if (shiftOneShot && normalChar.isNotEmpty() && normalChar[0].isLetter()) {
                normalChar = normalChar.uppercase()
            } else if (capsLockEnabled && event?.isShiftPressed != true) {
                normalChar = normalChar.uppercase()
            } else if (capsLockEnabled && event?.isShiftPressed == true) {
                normalChar = normalChar.lowercase()
            }
        }

        if (normalChar.isNotEmpty()) {
            if (handleBoundaryTextBeforeCommit(normalChar, inputConnection)) {
                normalChar.firstOrNull()?.let { onAltCharInserted?.invoke(it) }
                return true
            }
            inputConnection.commitText(normalChar, 1)
            insertedNormalChars[keyCode] = normalChar
            insertedTextAnchors.remove(keyCode)
            captureInsertedTextAnchor(inputConnection, normalChar)?.let { anchor ->
                insertedTextAnchors[keyCode] = anchor
            }
            keyPressWasShifted[keyCode] = shiftOneShot || event?.isShiftPressed == true
        }

        // Check if this key should support long press
        val longPressMode = context?.let {
            SettingsManager.getLongPressModifier(it)
        } ?: "alt"

        val shouldScheduleLongPress = when (longPressMode) {
            "variations" -> {
                if (normalChar.isEmpty()) {
                    false
                } else {
                    val variations = context?.let { ctx ->
                        VariationRepository.loadVariations(
                            assets = ctx.assets,
                            context = ctx,
                            activeLayoutName = activeLayoutNameProvider?.invoke()
                        )
                    } ?: emptyMap()
                    variations[normalChar.firstOrNull()]?.isNotEmpty() == true
                }
            }
            "sym", "sym_symbols", "sym_emoji" -> hasSymLongPressMapping(
                keyCode = keyCode,
                shiftPressed = keyPressWasShifted[keyCode] == true
            )
            "shift" -> LayoutMappingRepository.isMapped(keyCode) && normalChar.isNotEmpty()
            else -> hasAltMapping(keyCode)
        }
        
        if (shouldScheduleLongPress) {
            scheduleLongPress(keyCode, inputConnection)
        }
        
        return true
    }

    fun handleAltCombination(
        keyCode: Int,
        inputConnection: InputConnection,
        event: KeyEvent?,
        mappingsOverride: Map<Int, String>? = null,
        defaultHandler: (Int, KeyEvent?) -> Boolean
    ): Boolean {
        val altChar = (mappingsOverride ?: getAltModifierMappings())[keyCode]
        return if (altChar != null) {
            if (handleBoundaryTextBeforeCommit(altChar, inputConnection)) {
                altChar.firstOrNull()?.let { onAltCharInserted?.invoke(it) }
                return true
            }
            context?.let {
                DeferredPunctuationSpaceTracker.prepareForTextCommit(it, inputConnection, altChar)
            }
            val frenchSpacedPunctuation = altChar.length == 1 &&
                context?.let { SettingsManager.shouldApplyFrenchPunctuationSpacing(it) } == true &&
                rocks.pkb.plektra.core.Punctuation.commitFrenchSpacedPunctuation(inputConnection, altChar[0])
            if (frenchSpacedPunctuation) {
                onAltCharInserted?.invoke(altChar[0])
                return true
            }
            val punctuationSet = autoSpacePunctuation()
            if (altChar.isNotEmpty() && altChar[0] in punctuationSet) {
                val applied = AutoSpaceTracker.replaceAutoSpaceWithPunctuation(inputConnection, altChar)
                if (applied) {
                    Log.d(TAG, "Alt mapping applied with auto-space replacement for '$altChar'")
                    onAltCharInserted?.invoke(altChar[0])
                    return true
                }
            }
            AutoSpaceTracker.clear()
            inputConnection.commitText(altChar, 1)
            if (altChar.isNotEmpty()) {
                onAltCharInserted?.invoke(altChar[0])
            }
            true
        } else {
            defaultHandler(keyCode, event)
        }
    }

    fun handleKeyUp(keyCode: Int, symKeyActive: Boolean, shiftPressed: Boolean = false): Boolean {
        val pressStartTime = pressedKeys.remove(keyCode)
        val wasLongPressActivated = longPressActivated.remove(keyCode) ?: false
        val insertedChar = insertedNormalChars.remove(keyCode)
        insertedTextAnchors.remove(keyCode)
        keyPressWasShifted.remove(keyCode)
        
        longPressRunnables.remove(keyCode)?.let { handler.removeCallbacks(it) }

        // If the long press did NOT trigger and we had inserted a normal char, notify tracking
        if (!wasLongPressActivated && insertedChar != null) {
            onNormalCharCommitted?.invoke(insertedChar)
        }

        return pressStartTime != null && !symKeyActive
    }

    fun cancelPendingLongPress(keyCode: Int) {
        longPressRunnables.remove(keyCode)?.let { handler.removeCallbacks(it) }
    }

    /**
     * Schedules a long-press without committing a new character, reusing the
     * same runnable logic used by handleKeyWithAltMapping. This is used so
     * multi-tap commits can still trigger Alt/Shift long-press behaviour.
     */
    fun scheduleLongPressOnly(
        keyCode: Int,
        inputConnection: InputConnection,
        insertedChar: String
    ) {
        pressedKeys[keyCode] = System.currentTimeMillis()
        longPressActivated[keyCode] = false
        insertedNormalChars[keyCode] = insertedChar
        insertedTextAnchors.remove(keyCode)
        captureInsertedTextAnchor(inputConnection, insertedChar)?.let { anchor ->
            insertedTextAnchors[keyCode] = anchor
        }
        keyPressWasShifted[keyCode] = insertedChar.firstOrNull()?.isUpperCase() == true
        scheduleLongPress(keyCode, inputConnection)
    }

    /**
     * The text a Shift long press gives for [typedText] on [keyCode]: the uppercase
     * letter, or the lowercase one when the typed letter already is that uppercase
     * (auto-capitalized, one-shot Shift, Caps Lock), so Shift always toggles case.
     * Null when the key has no case to toggle.
     */
    private fun resolveShiftLongPressText(keyCode: Int, typedText: String?): String? {
        if (LayoutMappingRepository.isMapped(keyCode)) {
            val mapping = LayoutMappingRepository.getMapping(keyCode) ?: return null
            val tap = if (mapping.isRealMultiTap) {
                mapping.taps.firstOrNull { typedText == it.lowercase || typedText == it.uppercase }
            } else {
                null
            }
            val upper = tap?.uppercase ?: mapping.uppercase
            if (typedText != null && typedText == upper) {
                val lower = (tap?.lowercase ?: mapping.lowercase)
                    .takeIf { it.isNotEmpty() && it != upper }
                    ?: upper.lowercase().takeIf { it != upper }
                if (lower != null) return lower
            }
            return upper
        }
        if (typedText.isNullOrEmpty() || !typedText[0].isLetter()) return null
        val upper = typedText.uppercase()
        if (upper != typedText) return upper
        return typedText.lowercase().takeIf { it != typedText } ?: upper
    }

    private fun scheduleLongPress(
        keyCode: Int,
        inputConnection: InputConnection
    ) {
        reloadLongPressThreshold()

        val longPressMode = context?.let {
            SettingsManager.getLongPressModifier(it)
        } ?: "alt"

        val runnable = Runnable {
            if (pressedKeys.containsKey(keyCode)) {
                val insertedChar = insertedNormalChars[keyCode]

                when (longPressMode) {
                    "variations" -> {
                        if (!insertedChar.isNullOrEmpty()) {
                            val wasShifted = keyPressWasShifted[keyCode] ?: false
                            val baseChar = insertedChar[0]
                            val lookupChar = if (wasShifted && baseChar.isLowerCase()) {
                                baseChar.uppercaseChar()
                            } else if (!wasShifted && baseChar.isUpperCase()) {
                                baseChar.lowercaseChar()
                            } else {
                                baseChar
                            }

                            val variations = context?.let { ctx ->
                                VariationRepository.loadVariations(
                                    assets = ctx.assets,
                                    context = ctx,
                                    activeLayoutName = activeLayoutNameProvider?.invoke()
                                )[lookupChar]
                            }
                            if (!variations.isNullOrEmpty()) {
                                val firstVariation = variations.first()
                                val outcome = replaceInsertedText(
                                    keyCode = keyCode,
                                    inputConnection = inputConnection,
                                    insertedText = insertedChar,
                                    replacement = firstVariation,
                                    commitAtCursor = null
                                )
                                finishLongPress(keyCode, outcome, firstVariation, "Variations")
                            }
                        }
                    }

                    "sym", "sym_symbols", "sym_emoji" -> {
                        val page = context?.let(SettingsManager::resolveLongPressSymPage) ?: 1
                        val wasShifted = keyPressWasShifted[keyCode] ?: false
                        val symChar = if (page == 1) {
                            if (wasShifted && symKeyMapUppercase.containsKey(keyCode)) {
                                symKeyMapUppercase[keyCode]
                            } else {
                                symKeyMap[keyCode]
                            }
                        } else {
                            if (wasShifted && symKeyMap2Uppercase.containsKey(keyCode)) {
                                symKeyMap2Uppercase[keyCode]
                            } else {
                                symKeyMap2[keyCode]
                            }
                        }

                        if (!symChar.isNullOrEmpty()) {
                            val outcome = replaceInsertedText(keyCode, inputConnection, insertedChar, symChar) {
                                commitSymbolAtCursor(symChar, inputConnection, "Sym")
                            }
                            finishLongPress(keyCode, outcome, symChar, "Sym")
                        }
                    }

                    "shift" -> {
                        // Long press with Shift toggles case: uppercase from the layout (always use
                        // JSON for mapped keys), or lowercase when the typed letter is already
                        // uppercase (auto-capitalized, one-shot Shift, Caps Lock).
                        if (LayoutMappingRepository.isMapped(keyCode)) {
                            val shiftChar = resolveShiftLongPressText(keyCode, insertedChar)
                            if (shiftChar != null) {
                                val outcome = replaceInsertedText(keyCode, inputConnection, insertedChar, shiftChar) {
                                    handleBoundaryTextBeforeCommit(shiftChar, inputConnection) ||
                                        inputConnection.commitText(shiftChar, 1)
                                }
                                finishLongPress(keyCode, outcome, shiftChar, "Shift")
                            }
                        } else if (insertedChar != null && insertedChar.isNotEmpty() && insertedChar[0].isLetter()) {
                            // Fallback for unmapped keys only: use Kotlin case conversion.
                            val shiftChar = resolveShiftLongPressText(keyCode, insertedChar) ?: insertedChar.uppercase()
                            val outcome = replaceInsertedText(keyCode, inputConnection, insertedChar, shiftChar) {
                                inputConnection.commitText(shiftChar, 1)
                            }
                            finishLongPress(keyCode, outcome, shiftChar, "Shift (fallback)")
                        }
                    }

                    else -> {
                        // Long press with Alt: use existing Alt mapping (default).
                        val altChar = getAltModifierMappings()[keyCode]

                        if (!altChar.isNullOrEmpty()) {
                            val outcome = replaceInsertedText(keyCode, inputConnection, insertedChar, altChar) {
                                commitSymbolAtCursor(altChar, inputConnection, "Alt")
                            }
                            finishLongPress(keyCode, outcome, altChar, "Alt")
                        }
                    }
                }
            }
        }

        longPressRunnables[keyCode] = runnable
        handler.postDelayed(runnable, longPressThreshold)
    }

    /**
     * Ends a long press as [outcome] reports it. Only a replacement activates it and reports
     * the alternate character. Unchanged text stays the key's own, which key-up reports as
     * a normally committed character; text the editor removed without taking the
     * replacement is reported as neither.
     */
    private fun finishLongPress(keyCode: Int, outcome: ReplacementOutcome, replacement: String, label: String) {
        longPressRunnables.remove(keyCode)
        when (outcome) {
            ReplacementOutcome.UNCHANGED -> {
                Log.d(TAG, "Skipped $label long press for keyCode $keyCode: text not replaced")
                return
            }
            ReplacementOutcome.REMOVED -> {
                Log.w(TAG, "$label long press for keyCode $keyCode: editor removed the text but rejected '$replacement'")
                longPressActivated[keyCode] = true
                insertedNormalChars.remove(keyCode)
                keyPressWasShifted.remove(keyCode)
                return
            }
            ReplacementOutcome.REPLACED -> Unit
        }
        longPressActivated[keyCode] = true
        insertedNormalChars.remove(keyCode)
        keyPressWasShifted.remove(keyCode)
        Log.d(TAG, "Long press $label per keyCode $keyCode -> $replacement")
        replacement.firstOrNull()?.let { onAltCharInserted?.invoke(it) }
    }

    /**
     * Commits a long-press symbol at the cursor with the same boundary and spacing rules as
     * typing. A handled spacing/boundary request is successful only if its commit was accepted.
     */
    private fun commitSymbolAtCursor(text: String, inputConnection: InputConnection, label: String): Boolean {
        var commitAccepted: Boolean? = null
        val trackedConnection = object : InputConnectionWrapper(inputConnection, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                super.commitText(text, newCursorPosition).also { commitAccepted = it }
        }
        // An unhandled boundary can still prepare spacing before the actual symbol is committed.
        if (handleBoundaryTextBeforeCommit(text, trackedConnection)) {
            return commitAccepted == true
        }
        if (commitAccepted == false) return false
        commitAccepted = null

        val frenchSpacedPunctuation = text.length == 1 &&
            context?.let { SettingsManager.shouldApplyFrenchPunctuationSpacing(it) } == true &&
            rocks.pkb.plektra.core.Punctuation.commitFrenchSpacedPunctuation(trackedConnection, text[0])
        if (frenchSpacedPunctuation || commitAccepted != null) {
            Log.d(TAG, "Long press $label French spacing for '$text': accepted=$commitAccepted")
            return commitAccepted == true
        }

        if (text[0] in autoSpacePunctuation() &&
            AutoSpaceTracker.replaceAutoSpaceWithPunctuation(trackedConnection, text)
        ) {
            Log.d(TAG, "Long press $label auto-space replacement for '$text': accepted=$commitAccepted")
            return commitAccepted == true
        }

        AutoSpaceTracker.clear()
        return inputConnection.commitText(text, 1)
    }

    /**
     * Whether committing [text] at the cursor can run typing rules (boundary handling,
     * French spacing, auto-space replacement) that a plain replacement would skip.
     */
    private fun followsCursorTypingRules(text: String): Boolean {
        if (text.length != 1) return false
        val char = text[0]
        val boundary = rocks.pkb.plektra.core.Punctuation.normalizeApostrophe(char)
        return (onBoundaryTextRequested != null && boundary != '\'' &&
            boundary in rocks.pkb.plektra.core.Punctuation.BOUNDARY) ||
            char in autoSpacePunctuation() ||
            (char in rocks.pkb.plektra.core.Punctuation.FRENCH_SPACED_PUNCTUATION &&
                context?.let { SettingsManager.shouldApplyFrenchPunctuationSpacing(it) } == true)
    }

    private data class EditorSnapshot(
        val text: String,
        val textStart: Int,
        val selectionStart: Int,
        val selectionEnd: Int
    )

    private data class InsertedTextAnchor(
        val inputConnection: InputConnection,
        val textStart: Int,
        val targetStart: Int,
        val targetEnd: Int,
        val prefixThroughTarget: String,
        val expectedText: String
    )

    /** Where the text a key press inserted is when its long press fires. */
    private sealed interface InsertedTextTarget {
        /** A readable editor shows the inserted text at this range. */
        data class Located(val start: Int, val end: Int, val snapshot: EditorSnapshot) : InsertedTextTarget

        /**
         * The editor gives no snapshot, so no absolute range: the text before the cursor
         * either matches the inserted text ([verified]) or is not readable at all (null).
         */
        data class BeforeCursor(val verified: Boolean) : InsertedTextTarget

        /** Readable editor state shows the inserted text is not where it was put. */
        data object Refuted : InsertedTextTarget
    }

    /**
     * What a long-press replacement did to the editor. The input connection has no
     * transaction with rollback, so a deletion it accepted stays when it then rejects the
     * replacement ([REMOVED]); edits that can avoid that replace the range in one commit.
     */
    private enum class ReplacementOutcome { REPLACED, UNCHANGED, REMOVED }

    private fun captureInsertedTextAnchor(
        inputConnection: InputConnection,
        insertedText: String
    ): InsertedTextAnchor? {
        if (insertedText.isEmpty()) return null

        val snapshot = readEditorSnapshot(inputConnection) ?: return null
        if (snapshot.selectionStart != snapshot.selectionEnd) return null

        val targetEnd = snapshot.selectionStart
        val targetStart = targetEnd - insertedText.length
        val relativeStart = targetStart - snapshot.textStart
        val relativeEnd = targetEnd - snapshot.textStart
        if (relativeStart < 0 || relativeEnd > snapshot.text.length) return null
        if (snapshot.text.substring(relativeStart, relativeEnd) != insertedText) return null

        return InsertedTextAnchor(
            inputConnection = inputConnection,
            textStart = snapshot.textStart,
            targetStart = targetStart,
            targetEnd = targetEnd,
            prefixThroughTarget = snapshot.text.substring(0, relativeEnd),
            expectedText = insertedText
        )
    }

    /**
     * Replaces the whole text this key press inserted with [replacement], so multi-character
     * outputs (Uyghur لا) and surrogate pairs are replaced as one unit.
     *
     * A range a readable editor still shows is replaced in place with one commit through a
     * composing region, also when text was typed after it; the selection moves with it.
     * Only a replacement that typing rules may rewrite at the cursor (see
     * [followsCursorTypingRules]) and an editor without a readable snapshot go through
     * [commitAtCursor] after deleting the inserted length before the cursor. Without
     * [commitAtCursor] those cases are left unchanged. Nothing is changed when readable
     * editor state contradicts the insertion; only an editor that offers no readable text
     * at all gets the deletion unverified.
     */
    private fun replaceInsertedText(
        keyCode: Int,
        inputConnection: InputConnection,
        insertedText: String?,
        replacement: String,
        commitAtCursor: (() -> Boolean)?
    ): ReplacementOutcome {
        val anchor = insertedTextAnchors.remove(keyCode)
        if (replacement.isEmpty()) return ReplacementOutcome.UNCHANGED
        if (insertedText.isNullOrEmpty()) {
            // The key press inserted nothing (Alt/Sym-only key): just commit.
            return if (commitAtCursor?.invoke() == true) ReplacementOutcome.REPLACED else ReplacementOutcome.UNCHANGED
        }

        return when (val target = resolveInsertedTextTarget(inputConnection, insertedText, anchor)) {
            InsertedTextTarget.Refuted -> ReplacementOutcome.UNCHANGED
            is InsertedTextTarget.BeforeCursor -> if (commitAtCursor == null) {
                ReplacementOutcome.UNCHANGED
            } else {
                deleteBeforeCursorAndCommit(inputConnection, insertedText.length, commitAtCursor)
            }
            is InsertedTextTarget.Located -> {
                val range = target.start to target.end
                val atCursor = target.end == target.snapshot.selectionStart
                val outcome = if (commitAtCursor != null && atCursor && followsCursorTypingRules(replacement)) {
                    deleteBeforeCursorAndCommit(inputConnection, insertedText.length, commitAtCursor)
                } else {
                    replaceTarget(inputConnection, target.snapshot, range, replacement)
                }
                when (outcome) {
                    ReplacementOutcome.REPLACED -> shiftPendingAnchors(inputConnection, range, replacement)
                    ReplacementOutcome.REMOVED -> shiftPendingAnchors(inputConnection, range, "")
                    ReplacementOutcome.UNCHANGED -> Unit
                }
                outcome
            }
        }
    }

    private fun deleteBeforeCursorAndCommit(
        inputConnection: InputConnection,
        length: Int,
        commitAtCursor: () -> Boolean
    ): ReplacementOutcome {
        inputConnection.beginBatchEdit()
        try {
            if (!inputConnection.deleteSurroundingText(length, 0)) return ReplacementOutcome.UNCHANGED
            return if (commitAtCursor()) ReplacementOutcome.REPLACED else ReplacementOutcome.REMOVED
        } finally {
            inputConnection.endBatchEdit()
        }
    }

    private fun resolveInsertedTextTarget(
        inputConnection: InputConnection,
        expectedText: String,
        anchor: InsertedTextAnchor?
    ): InsertedTextTarget {
        val snapshot = readEditorSnapshot(inputConnection)
        if (snapshot == null) {
            // Null means unknown; readable text, even empty, has to show the inserted text.
            val before = inputConnection.getTextBeforeCursor(expectedText.length, 0)?.toString()
            return when (before) {
                null -> InsertedTextTarget.BeforeCursor(verified = false)
                expectedText -> InsertedTextTarget.BeforeCursor(verified = true)
                else -> InsertedTextTarget.Refuted
            }
        }

        val range = if (anchor != null) {
            resolveAnchoredTarget(inputConnection, snapshot, anchor)
                // The text before the anchor may have been rewritten (e.g. by autocorrect);
                // the inserted text right before the cursor at its original end still matches.
                ?: resolveImmediatelyPrecedingTarget(snapshot, expectedText)
                    ?.takeIf { anchor.inputConnection === inputConnection && it.second == anchor.targetEnd }
        } else {
            resolveImmediatelyPrecedingTarget(snapshot, expectedText)
        }
        return range?.let { InsertedTextTarget.Located(it.first, it.second, snapshot) }
            ?: InsertedTextTarget.Refuted
    }

    /**
     * Keeps anchors of other keys still held valid after this manager replaced [range]
     * in the same editor; anchors overlapping the range no longer match and are dropped.
     */
    private fun shiftPendingAnchors(
        inputConnection: InputConnection,
        range: Pair<Int, Int>,
        replacement: String
    ) {
        val lengthDelta = replacement.length - (range.second - range.first)
        for ((key, anchor) in insertedTextAnchors) {
            if (anchor.inputConnection !== inputConnection || anchor.targetEnd <= range.first) continue
            val relativeStart = range.first - anchor.textStart
            val relativeEnd = range.second - anchor.textStart
            if (anchor.targetStart < range.second || relativeStart < 0) {
                insertedTextAnchors.remove(key)
                continue
            }
            insertedTextAnchors[key] = anchor.copy(
                targetStart = anchor.targetStart + lengthDelta,
                targetEnd = anchor.targetEnd + lengthDelta,
                prefixThroughTarget = anchor.prefixThroughTarget.replaceRange(relativeStart, relativeEnd, replacement)
            )
        }
    }

    /**
     * Replaces [target] with one commit into a composing region set on it. The region is
     * finished on every path, so a rejected commit or an exception never leaves it for the
     * next keystroke to overwrite; the selection only moves after an accepted commit.
     */
    private fun replaceTarget(
        inputConnection: InputConnection,
        snapshot: EditorSnapshot,
        target: Pair<Int, Int>,
        replacement: String
    ): ReplacementOutcome {
        val selectionStart = snapshot.selectionStart
        val selectionEnd = snapshot.selectionEnd
        val lengthDelta = replacement.length - (target.second - target.first)
        inputConnection.beginBatchEdit()
        try {
            inputConnection.finishComposingText()
            if (!inputConnection.setComposingRegion(target.first, target.second)) return ReplacementOutcome.UNCHANGED
            if (!inputConnection.commitText(replacement, 1)) return ReplacementOutcome.UNCHANGED
            inputConnection.finishComposingText()
            inputConnection.setSelection(
                adjustSelectionAfterReplacement(selectionStart, target, lengthDelta, replacement.length),
                adjustSelectionAfterReplacement(selectionEnd, target, lengthDelta, replacement.length)
            )
            return ReplacementOutcome.REPLACED
        } finally {
            inputConnection.finishComposingText()
            inputConnection.endBatchEdit()
        }
    }

    private fun resolveAnchoredTarget(
        inputConnection: InputConnection,
        snapshot: EditorSnapshot,
        anchor: InsertedTextAnchor
    ): Pair<Int, Int>? {
        if (anchor.inputConnection !== inputConnection) return null
        if (snapshot.selectionStart != snapshot.selectionEnd) return null
        if (snapshot.selectionStart < anchor.targetEnd) return null
        if (snapshot.textStart != anchor.textStart) return null

        val relativeStart = anchor.targetStart - snapshot.textStart
        val relativeEnd = anchor.targetEnd - snapshot.textStart
        if (relativeStart < 0 || relativeEnd > snapshot.text.length) return null
        if (snapshot.text.substring(relativeStart, relativeEnd) != anchor.expectedText) return null
        if (snapshot.text.substring(0, relativeEnd) != anchor.prefixThroughTarget) return null
        return anchor.targetStart to anchor.targetEnd
    }

    private fun resolveImmediatelyPrecedingTarget(
        snapshot: EditorSnapshot,
        expectedText: String
    ): Pair<Int, Int>? {
        if (snapshot.selectionStart != snapshot.selectionEnd) return null
        val targetEnd = snapshot.selectionStart
        val targetStart = targetEnd - expectedText.length
        val relativeStart = targetStart - snapshot.textStart
        val relativeEnd = targetEnd - snapshot.textStart
        if (relativeStart < 0 || relativeEnd > snapshot.text.length) return null
        if (snapshot.text.substring(relativeStart, relativeEnd) != expectedText) return null
        return targetStart to targetEnd
    }

    private fun readEditorSnapshot(inputConnection: InputConnection): EditorSnapshot? {
        val extracted = inputConnection.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        val text = extracted.text?.toString() ?: return null
        if (extracted.selectionStart < 0 || extracted.selectionEnd < 0) return null
        return EditorSnapshot(
            text = text,
            textStart = extracted.startOffset,
            selectionStart = extracted.startOffset + extracted.selectionStart,
            selectionEnd = extracted.startOffset + extracted.selectionEnd
        )
    }

    private fun adjustSelectionAfterReplacement(
        position: Int,
        target: Pair<Int, Int>,
        lengthDelta: Int,
        replacementLength: Int
    ): Int {
        return when {
            position <= target.first -> position
            position >= target.second -> position + lengthDelta
            else -> target.first + replacementLength
        }
    }
}
