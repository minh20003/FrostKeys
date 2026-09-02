// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.event

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.ArrayList

/**
 * Tone placement conventions used by Vietnamese Telex and VNI input.
 *
 * [COMMON] follows the current Vietnamese education convention (for example, "hòa" and
 * "thúy"). [ALTERNATE] retains the legacy spelling used by a number of existing Vietnamese
 * keyboards ("hoà" and "thuý").
 */
enum class VietnameseTonePlacement {
    COMMON,
    ALTERNATE;

    companion object {
        fun fromPreference(value: String?): VietnameseTonePlacement =
            if (value.equals("alternate", ignoreCase = true)) ALTERNATE else COMMON
    }
}

/** Reads the user preference lazily so an already open keyboard reflects the chosen convention. */
internal fun configuredVietnameseTonePlacement(): VietnameseTonePlacement {
    val context = Settings.getCurrentContext() ?: return VietnameseTonePlacement.COMMON
    return VietnameseTonePlacement.fromPreference(
        context.prefs().getString(
            Settings.PREF_VIETNAMESE_TONE_PLACEMENT,
            Defaults.PREF_VIETNAMESE_TONE_PLACEMENT
        )
    )
}

/** A stateful Telex combiner for the Vietnamese QWERTY layout. */
class VietnameseTelexCombiner(
    tonePlacement: () -> VietnameseTonePlacement = { VietnameseTonePlacement.COMMON }
) : VietnameseCombiner(tonePlacement) {
    override fun transformLetter(
        current: String,
        input: Char,
        placement: VietnameseTonePlacement
    ): VietnameseTransformResult {
        return when (input.lowercaseChar()) {
            'a' -> transformedOrLiteral(
                applyShape(current, 'a', 'â', placement), current, input, placement
            )
            'e' -> transformedOrLiteral(
                applyShape(current, 'e', 'ê', placement), current, input, placement
            )
            'o' -> transformedOrLiteral(
                applyShape(current, 'o', 'ô', placement), current, input, placement
            )
            'd' -> transformedOrLiteral(
                applyShape(current, 'd', 'đ', placement), current, input, placement
            )
            'w' -> transformedOrLiteral(applyW(current, placement), current, input, placement)
            's' -> transformedOrLiteral(
                applyTone(current, VietnameseTone.ACUTE, placement), current, input, placement
            )
            'f' -> transformedOrLiteral(
                applyTone(current, VietnameseTone.GRAVE, placement), current, input, placement
            )
            'r' -> transformedOrLiteral(
                applyTone(current, VietnameseTone.HOOK, placement), current, input, placement
            )
            'x' -> transformedOrLiteral(
                applyTone(current, VietnameseTone.TILDE, placement), current, input, placement
            )
            'j' -> transformedOrLiteral(
                applyTone(current, VietnameseTone.DOT, placement), current, input, placement
            )
            // z clears marks but is deliberately not an escape-able transform: a second z is
            // a literal z, as on established Vietnamese keyboards.
            'z' -> VietnameseTransformResult(
                stripAllMarks(current).takeIf { it != current }
                    ?: appendAndRelocateTone(current, input, placement)
            )
            else -> VietnameseTransformResult(appendAndRelocateTone(current, input, placement))
        }
    }
}

/** A stateful VNI combiner. Number keys are passed through unchanged unless they modify a word. */
class VietnameseVniCombiner(
    tonePlacement: () -> VietnameseTonePlacement = { VietnameseTonePlacement.COMMON }
) : VietnameseCombiner(tonePlacement) {
    override fun transformLetter(
        current: String,
        input: Char,
        placement: VietnameseTonePlacement
    ): VietnameseTransformResult = VietnameseTransformResult(
        appendAndRelocateTone(current, input, placement)
    )

    override fun transformModifier(
        current: String,
        input: Char,
        placement: VietnameseTonePlacement
    ): VietnameseTransformResult? =
        when (input) {
            '1' -> transformedResult(applyTone(current, VietnameseTone.ACUTE, placement))
            '2' -> transformedResult(applyTone(current, VietnameseTone.GRAVE, placement))
            '3' -> transformedResult(applyTone(current, VietnameseTone.HOOK, placement))
            '4' -> transformedResult(applyTone(current, VietnameseTone.TILDE, placement))
            '5' -> transformedResult(applyTone(current, VietnameseTone.DOT, placement))
            '6' -> transformedResult(applyShapeForLastEligible(current, VNI_CIRCUMFLEX_SHAPES, placement))
            '7' -> transformedResult(applyShapeForLastEligible(current, VNI_HORN_SHAPES, placement))
            '8' -> transformedResult(applyShape(current, 'a', 'ă', placement))
            '9' -> transformedResult(applyShape(current, 'd', 'đ', placement))
            // 0 is the VNI counterpart to Telex z. It removes marks, but does not use
            // repeated-key escaping: after clearing marks, another 0 is a literal digit.
            '0' -> stripAllMarks(current).takeIf { it != current }?.let { VietnameseTransformResult(it) }
            else -> null
        }

    override fun isModifier(codePoint: Int): Boolean = codePoint in '0'.code..'9'.code
}

/**
 * Shared event handling for Vietnamese input methods.
 *
 * The composing buffer is intentionally retained as feedback until a separator is received.
 * That mirrors Hangul/Khipro and means IME clients see a single composing span instead of a
 * sequence of delete-and-replace operations. [history] stores the visual and repeated-key
 * escape state before each consumed key so Backspace can undo a Telex/VNI transformation one
 * key at a time.
 */
abstract class VietnameseCombiner(
    private val tonePlacement: () -> VietnameseTonePlacement
) : Combiner {
    private var composingText = ""
    private var lastAppliedTransform: VietnameseAppliedTransform? = null
    private val history = ArrayDeque<VietnameseCompositionSnapshot>()

    override fun processEvent(previousEvents: ArrayList<Event>?, event: Event): Event {
        if (event.keyCode == KeyCode.SHIFT) return event

        if (event.keyCode == KeyCode.DELETE) {
            if (history.isEmpty()) return event
            history.removeLast().also { previous ->
                composingText = previous.text
                lastAppliedTransform = previous.lastAppliedTransform
            }
            if (composingText.isNotEmpty()) return Event.createConsumedEvent(event)

            // Do not let the original delete reach text committed before this composition.
            // The synthetic space is inserted and immediately removed by the chained delete,
            // following the existing Hangul combiner contract.
            reset()
            return Event.createHardwareKeypressEvent(
                Constants.CODE_SPACE,
                Constants.CODE_SPACE,
                0,
                event,
                event.isKeyRepeat
            )
        }

        if (event.isFunctionalKeyEvent) return commitAndReset(event)

        val codePoint = event.codePoint
        if (isVietnameseLetter(codePoint)) {
            val input = codePoint.toChar()
            // Prefer another valid transform over the usual repeated-key escape.  This is what
            // lets free/end-of-word Telex marking advance through a vowel nucleus: in
            // `duongwwfd`, the first delayed w changes o -> ơ and the second changes u -> ư.
            // When no further target exists, retain the familiar escape behavior (`aaa` -> aa,
            // `uoww` -> uow) by restoring the pre-transform text and writing the literal key.
            val normal = transformLetter(composingText, input, tonePlacement())
            val result = if (normal.canEscapeWithRepeatedKey) normal
                else escapedRepeatedTransform(input) ?: normal
            applyInput(input, result)
            return Event.createConsumedEvent(event)
        }

        if (isModifier(codePoint)) {
            val input = codePoint.toChar()
            val transformed = if (composingText.isEmpty()) {
                null
            } else {
                // VNI has the same free/end-of-word convention.  In particular, the two 7
                // markers in `duong9772` must reach o and then u before a repeated 7 can be
                // interpreted as a literal digit.
                val normal = transformModifier(composingText, input, tonePlacement())
                if (normal?.canEscapeWithRepeatedKey == true) normal
                else escapedRepeatedTransform(input) ?: normal
            }
            if (transformed != null) {
                applyInput(input, transformed)
                return Event.createConsumedEvent(event)
            }
        }

        // Digits, punctuation, emoji, and non-Latin scripts terminate a Vietnamese word. This
        // lets InputLogic handle separators normally and avoids Telex/VNI affecting URLs etc.
        return commitAndReset(event)
    }

    override val combiningStateFeedback: CharSequence
        get() = composingText

    override fun reset() {
        composingText = ""
        lastAppliedTransform = null
        history.clear()
    }

    protected abstract fun transformLetter(
        current: String,
        input: Char,
        placement: VietnameseTonePlacement
    ): VietnameseTransformResult

    /** Return null when this key must be emitted literally instead of being consumed. */
    protected open fun transformModifier(
        current: String,
        input: Char,
        placement: VietnameseTonePlacement
    ): VietnameseTransformResult? = null

    protected open fun isModifier(codePoint: Int): Boolean = false

    private fun commitAndReset(event: Event): Event {
        if (composingText.isEmpty()) return event
        val text = composingText
        reset()
        return Event.createSoftwareTextEvent(text, KeyCode.MULTIPLE_CODE_POINTS, event)
    }

    private fun applyInput(input: Char, result: VietnameseTransformResult) {
        val previousText = composingText
        history.addLast(VietnameseCompositionSnapshot(previousText, lastAppliedTransform))
        composingText = normalizeNfc(result.text)
        lastAppliedTransform = if (result.canEscapeWithRepeatedKey && composingText != previousText) {
            VietnameseAppliedTransform(input, previousText, composingText)
        } else {
            null
        }
    }

    /**
     * Telex/VNI use a repeated trigger to write the literal trigger. Keep this tied to the
     * immediately preceding generated transform, rather than to the displayed character, so a
     * user-provided precomposed "â" followed by "a" remains "âa" instead of being rewritten.
     */
    private fun escapedRepeatedTransform(input: Char): VietnameseTransformResult? {
        val previous = lastAppliedTransform ?: return null
        if (!input.equals(previous.trigger, ignoreCase = true) || composingText != previous.after) return null
        return VietnameseTransformResult(normalizeNfc(previous.before + input))
    }

    private fun isVietnameseLetter(codePoint: Int): Boolean =
        codePoint in Char.MIN_VALUE.code..Char.MAX_VALUE.code && Character.isLetter(codePoint)
            && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
}

data class VietnameseTransformResult(
    val text: String,
    val canEscapeWithRepeatedKey: Boolean = false
)

private data class VietnameseAppliedTransform(
    val trigger: Char,
    val before: String,
    val after: String
)

private data class VietnameseCompositionSnapshot(
    val text: String,
    val lastAppliedTransform: VietnameseAppliedTransform?
)

private fun transformedOrLiteral(
    transformed: String?,
    current: String,
    input: Char,
    placement: VietnameseTonePlacement
): VietnameseTransformResult = transformedResult(transformed)
    ?: VietnameseTransformResult(appendAndRelocateTone(current, input, placement))

private fun transformedResult(transformed: String?): VietnameseTransformResult? =
    transformed?.let { VietnameseTransformResult(it, canEscapeWithRepeatedKey = true) }

private enum class VietnameseTone(val combiningMark: Char) {
    ACUTE('\u0301'),
    GRAVE('\u0300'),
    HOOK('\u0309'),
    TILDE('\u0303'),
    DOT('\u0323');

    companion object {
        fun fromCodePoint(codePoint: Int): VietnameseTone? = values().firstOrNull { it.combiningMark.code == codePoint }
    }
}

private fun appendAndRelocateTone(
    current: String,
    input: Char,
    placement: VietnameseTonePlacement
): String = relocateTone(normalizeNfc(current + input), placement)

private fun normalizeNfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

private fun applyShape(
    current: String,
    sourceBase: Char,
    targetBase: Char,
    placement: VietnameseTonePlacement
): String? {
    val targetIndex = findLastUnshapedBaseIndex(current, sourceBase) ?: return null
    val shaped = reshapeWithExistingTone(current[targetIndex], targetBase)
    return relocateTone(current.replaceRange(targetIndex, targetIndex + 1, shaped.toString()), placement)
}

/**
 * Applies a shape marker to the rightmost still-raw eligible vowel in the active Vietnamese word.
 *
 * A Telex/VNI marker is allowed at the end of a word, after its coda.  Do not treat a shaped
 * character (ă/â/ê/ô/ơ/ư) as its plain base here: a second marker must move to the earlier raw
 * vowel or escape literally, rather than repeatedly rewriting the same character.
 */
private fun applyShapeForLastEligible(
    current: String,
    mapping: Map<Char, Char>,
    placement: VietnameseTonePlacement
): String? {
    val targetIndex = current.indices.reversed().firstOrNull { index ->
        mapping.containsKey(stripTone(current[index]).lowercaseChar())
    } ?: return null
    val last = current[targetIndex]
    val source = stripTone(last).lowercaseChar()
    val target = requireNotNull(mapping[source])
    return relocateTone(
        current.replaceRange(targetIndex, targetIndex + 1, reshapeWithExistingTone(last, target).toString()),
        placement
    )
}

private fun applyW(current: String, placement: VietnameseTonePlacement): String? {
    if (current.length >= 2) {
        val uIndex = current.lastIndex - 1
        val oIndex = current.lastIndex
        val u = current[uIndex]
        val o = current[oIndex]
        if (isUnshapedBase(u, 'u') && isUnshapedBase(o, 'o')) {
            val withHorn = current
                .replaceRange(oIndex, oIndex + 1, reshapeWithExistingTone(o, 'ơ').toString())
                .replaceRange(uIndex, uIndex + 1, reshapeWithExistingTone(u, 'ư').toString())
            return relocateTone(withHorn, placement)
        }
    }
    return applyShapeForLastEligible(current, TELEX_W_SHAPES, placement)
}

private fun applyTone(
    current: String,
    tone: VietnameseTone,
    placement: VietnameseTonePlacement
): String? {
    val targetIndex = toneIndex(current, placement) ?: return null
    val existingToneIndex = current.indices.firstOrNull { toneOf(current[it]) != null }
    if (existingToneIndex == targetIndex && toneOf(current[targetIndex]) == tone) return null
    val withoutTones = current.map { stripTone(it) }.joinToString("")
    return withoutTones.replaceRange(
        targetIndex,
        targetIndex + 1,
        applyTone(withoutTones[targetIndex], tone).toString()
    )
}

private fun relocateTone(current: String, placement: VietnameseTonePlacement): String {
    val existingTone = current.firstNotNullOfOrNull(::toneOf) ?: return current
    val withoutTones = current.map { stripTone(it) }.joinToString("")
    val targetIndex = toneIndex(withoutTones, placement) ?: return current
    return withoutTones.replaceRange(
        targetIndex,
        targetIndex + 1,
        applyTone(withoutTones[targetIndex], existingTone).toString()
    )
}

/**
 * Determine the vowel that receives a tone. Final consonants use the final vowel; open syllables
 * use the first vowel, except three-vowel nuclei where the middle vowel is conventional. The
 * only configurable legacy difference is oa/oe/uy (hòa/thúy vs hoà/thuý).
 */
private fun toneIndex(text: String, placement: VietnameseTonePlacement): Int? {
    val vowels = text.indices.filter { index -> plainBase(text[index])?.lowercaseChar() in VOWELS }.toMutableList()
    if (vowels.isEmpty()) return null

    // The u in initial "qu" and the i in initial "gi" are consonant components when another
    // vowel follows, not the syllable's tone-bearing vowel.
    if (vowels.size > 1 && text.length > 1 && text[0].lowercaseChar() == 'q'
        && vowels.first() == 1 && plainBase(text[1])?.lowercaseChar() == 'u') {
        vowels.removeAt(0)
    }
    if (vowels.size > 1 && text.length > 1 && text[0].lowercaseChar() == 'g'
        && vowels.first() == 1 && plainBase(text[1])?.lowercaseChar() == 'i') {
        vowels.removeAt(0)
    }
    if (vowels.isEmpty()) return null

    // A shaped vowel is the spelling's nucleus (iê, uô, ươ, uâ, ...). Keep its
    // tone there even while the user is still composing the final consonant; otherwise
    // "tiees" would transiently display "tíê" instead of "tiế".
    vowels.lastOrNull { isShapedVietnameseVowel(text[it]) }?.let { return it }

    val lastVowel = vowels.last()
    val hasFinalConsonant = text.substring(lastVowel + 1).any { Character.isLetter(it) }
    if (hasFinalConsonant || vowels.size == 1) return lastVowel
    if (vowels.size >= 3) return vowels[vowels.lastIndex - 1]

    val firstBase = plainBase(text[vowels[0]])?.lowercaseChar()
    val secondBase = plainBase(text[vowels[1]])?.lowercaseChar()
    val legacyMovablePair = (firstBase == 'o' && (secondBase == 'a' || secondBase == 'e'))
        || (firstBase == 'u' && secondBase == 'y')
    return if (placement == VietnameseTonePlacement.ALTERNATE && legacyMovablePair) vowels[1] else vowels[0]
}

private val VOWELS = setOf('a', 'e', 'i', 'o', 'u', 'y')
private val TELEX_W_SHAPES = mapOf('a' to 'ă', 'o' to 'ơ', 'u' to 'ư')
private val VNI_CIRCUMFLEX_SHAPES = mapOf('a' to 'â', 'e' to 'ê', 'o' to 'ô')
private val VNI_HORN_SHAPES = mapOf('o' to 'ơ', 'u' to 'ư')

private fun findLastUnshapedBaseIndex(current: String, sourceBase: Char): Int? =
    current.indices.reversed().firstOrNull { index -> isUnshapedBase(current[index], sourceBase) }

private fun isUnshapedBase(char: Char, base: Char): Boolean = stripTone(char).lowercaseChar() == base

private fun reshapeWithExistingTone(char: Char, targetBase: Char): Char {
    val shaped = targetBase.withCaseOf(char)
    val tone = toneOf(char) ?: return shaped
    return applyTone(shaped, tone)
}

private fun isShapedVietnameseVowel(char: Char): Boolean = when (stripTone(char).lowercaseChar()) {
    'ă', 'â', 'ê', 'ô', 'ơ', 'ư' -> true
    else -> false
}

private fun plainBase(char: Char): Char? = stripTone(char).let { deToned ->
    val decomposed = Normalizer.normalize(deToned.toString(), Normalizer.Form.NFD)
    decomposed.firstOrNull { Character.getType(it) !in MARK_TYPES }?.let {
        when (it) {
            'đ' -> 'd'
            'Đ' -> 'D'
            else -> it
        }
    }
}

private fun toneOf(char: Char): VietnameseTone? {
    val decomposed = Normalizer.normalize(char.toString(), Normalizer.Form.NFD)
    return decomposed.firstNotNullOfOrNull { VietnameseTone.fromCodePoint(it.code) }
}

private fun stripTone(char: Char): Char {
    val decomposed = Normalizer.normalize(char.toString(), Normalizer.Form.NFD)
    val withoutTone = decomposed.filter { VietnameseTone.fromCodePoint(it.code) == null }
    return Normalizer.normalize(withoutTone, Normalizer.Form.NFC).singleOrNull() ?: char
}

private fun stripAllMarks(text: String): String {
    val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
    val stripped = decomposed.filter { Character.getType(it) !in MARK_TYPES }.map {
        when (it) {
            'đ' -> 'd'
            'Đ' -> 'D'
            else -> it
        }
    }.joinToString("")
    return Normalizer.normalize(stripped, Normalizer.Form.NFC)
}

private fun applyTone(char: Char, tone: VietnameseTone): Char {
    val base = stripTone(char)
    return Normalizer.normalize(base.toString() + tone.combiningMark, Normalizer.Form.NFC).singleOrNull() ?: char
}

private fun Char.withCaseOf(reference: Char): Char =
    if (reference.isUpperCase()) uppercaseChar() else lowercaseChar()

private val MARK_TYPES: Set<Int> = setOf(
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt()
)
