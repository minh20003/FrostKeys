package helium314.keyboard

import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_NORMAL
import helium314.keyboard.keyboard.internal.keyboard_parser.addLocaleKeyTextsToParams
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.POPUP_KEYS_LAYOUT
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.mainLayoutName
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodManager2::class
])
class SubtypeTest {
    private lateinit var latinIME: LatinIME
    private lateinit var params: KeyboardParams

    @BeforeTest fun setUp() {
        latinIME = Robolectric.setupService(LatinIME::class.java)
        ShadowLog.setupLogging()
        ShadowLog.stream = System.out
        params = KeyboardParams()
        params.mId = KeyboardLayoutSet.getFakeKeyboardId(KeyboardId.ELEMENT_ALPHABET)
        params.mPopupKeyOrder.add(POPUP_KEYS_LAYOUT)
        addLocaleKeyTextsToParams(latinIME, params, POPUP_KEYS_NORMAL)
    }

    @Test fun personalDefaultsStartWithOnlyVietnameseTelexAndEnglish() {
        assertEquals("", Defaults.PREF_ADDITIONAL_SUBTYPES)
        assertEquals(
            setOf("en", "vi"),
            Defaults.PREF_ENABLED_SUBTYPES
                .split(Separators.SETS)
                .filter { it.isNotBlank() }
                .map { it.toSettingsSubtype().locale.language }
                .toSet(),
        )
        assertTrue(Defaults.PREF_SELECTED_SUBTYPE.contains("vi"))
        assertTrue(Defaults.PREF_SELECTED_SUBTYPE.contains("CombiningRules=vi_telex"))
    }

    @Test fun methodDeclaresOnlySupportedOfflineKeyboardSubtypes() {
        // The registry is intentionally keyed by locale, so count the actual subtype lists
        // rather than the distinct locale keys (Vietnamese and Korean have several layouts).
        val resourceLocales = SubtypeSettings.getAvailableSubtypeLocales()
            .flatMap(SubtypeSettings::getResourceSubtypesForLocale)
            .groupingBy { it.locale().language }
            .eachCount()
        val expectedLocales = linkedMapOf("en" to 1, "ko" to 4, "th" to 1, "vi" to 3)
        if (BuildConfig.FROSTKEYS_MOZC_BUNDLE_ENABLED) {
            expectedLocales["ja"] = 1
        }
        if (BuildConfig.FROSTKEYS_RIME_BUNDLE_ENABLED) {
            expectedLocales["zh"] = 1
        }
        assertEquals(expectedLocales, resourceLocales)

        val vietnameseRules = SubtypeSettings.getResourceSubtypesForLocale("vi".constructLocale())
            .map { it.extraValue.substringAfter("CombiningRules=").substringBefore(',') }
            .toSet()
        assertEquals(setOf("vi_telex", "vi_vni", "vi_raw"), vietnameseRules)

        val namespace = "http://schemas.android.com/apk/res/android"
        val xml = latinIME.resources.getXml(R.xml.method)
        val vietnameseIds = mutableSetOf<String>()
        val japaneseMozcIds = mutableSetOf<String>()
        val chineseRimeIds = mutableSetOf<String>()
        while (xml.eventType != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == XmlPullParser.START_TAG && xml.name == "subtype") {
                when (xml.getAttributeValue(namespace, "imeSubtypeLocale")) {
                    "vi" -> xml.getAttributeValue(namespace, "subtypeId")?.let(vietnameseIds::add)
                    "ja_JP" -> {
                        assertTrue(xml.getAttributeValue(namespace, "imeSubtypeExtraValue").contains("Mozc=1"))
                        xml.getAttributeValue(namespace, "subtypeId")?.let(japaneseMozcIds::add)
                    }
                    "zh_CN" -> {
                        assertTrue(xml.getAttributeValue(namespace, "imeSubtypeExtraValue").contains("Rime=1"))
                        xml.getAttributeValue(namespace, "subtypeId")?.let(chineseRimeIds::add)
                    }
                }
            }
            xml.next()
        }
        assertEquals(setOf("0x93972eee", "0x7c16f001", "0x7c16f002"), vietnameseIds)
        val expectedMozcIds = if (BuildConfig.FROSTKEYS_MOZC_BUNDLE_ENABLED) {
            setOf("0x7c16f003")
        } else {
            emptySet()
        }
        assertEquals(expectedMozcIds, japaneseMozcIds)
        val expectedRimeIds = if (BuildConfig.FROSTKEYS_RIME_BUNDLE_ENABLED) {
            setOf("0x7c16f004")
        } else {
            emptySet()
        }
        assertEquals(expectedRimeIds, chineseRimeIds)
    }

    @Test fun spellCheckerAdvertisesOnlyBundledEnglishUsAndVietnameseDictionaries() {
        val namespace = "http://schemas.android.com/apk/res/android"
        val xml = latinIME.resources.getXml(R.xml.spellchecker)
        val locales = mutableSetOf<String>()
        while (xml.eventType != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == XmlPullParser.START_TAG && xml.name == "subtype") {
                xml.getAttributeValue(namespace, "subtypeLocale")?.let(locales::add)
            }
            xml.next()
        }
        assertEquals(setOf("en_US", "vi"), locales)
    }

    @Test fun emptyAdditionalSubtypesResultsInEmptyList() {
        // avoid issues where empty string results in additional subtype for undefined locale
        val prefs = latinIME.prefs()
        prefs.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, "").apply()
        assertTrue(SubtypeSettings.getAdditionalSubtypes().isEmpty())
        val from = SubtypeSettings.getResourceSubtypesForLocale("en-US".constructLocale()).single()

        // no change, and "changed" subtype actually is resource subtype -> still expect empty list
        SubtypeUtilsAdditional.changeAdditionalSubtype(from.toSettingsSubtype(), from.toSettingsSubtype(), latinIME)
        assertEquals(emptyList(), SubtypeSettings.getAdditionalSubtypes().map { it.toSettingsSubtype() })
    }

    @Test fun subtypeStaysEnabledOnEdits() {
        val prefs = latinIME.prefs()
        // Isolate this edit-flow test from the intentional clean-install Telex + English defaults.
        prefs.edit()
            .putString(Settings.PREF_ADDITIONAL_SUBTYPES, "")
            .putString(Settings.PREF_ENABLED_SUBTYPES, "")
            .apply()
        SubtypeSettings.reloadEnabledSubtypes(latinIME)

        // edit enabled resource subtype
        val from = SubtypeSettings.getResourceSubtypesForLocale("en-US".constructLocale()).single()
        SubtypeSettings.addEnabledSubtype(prefs, from)
        val to = from.toSettingsSubtype().withLayout(LayoutType.SYMBOLS, "symbols_arabic")
        SubtypeUtilsAdditional.changeAdditionalSubtype(from.toSettingsSubtype(), to, latinIME)
        assertEquals(to, SubtypeSettings.getEnabledSubtypes(false).single().toSettingsSubtype())

        // change the new subtype to effectively be the same as original resource subtype
        val toNew = to.withoutLayout(LayoutType.SYMBOLS)
        assertEquals(from.toSettingsSubtype(), toNew)
        SubtypeUtilsAdditional.changeAdditionalSubtype(to, toNew, latinIME)
        assertEquals(emptyList(), SubtypeSettings.getAdditionalSubtypes().map { it.toSettingsSubtype() })
        assertEquals(from.toSettingsSubtype(), SubtypeSettings.getEnabledSubtypes(false).single().toSettingsSubtype())
    }

    @Test fun koreanAndThaiResourceSubtypesKeepTheirOfflineLayouts() {
        val korean = SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale())
        assertEquals(
            setOf("korean", "korean_phonetic", "korean_sebeolsik_390", "korean_sebeolsik_final"),
            korean.mapNotNull { it.mainLayoutName() }.toSet(),
        )
        assertTrue(korean.all { it.extraValue.contains("CombiningRules=hangul") })

        val thai = SubtypeSettings.getResourceSubtypesForLocale("th".constructLocale()).single()
        assertEquals("thai", thai.mainLayoutName())
        assertTrue(!thai.isAsciiCapable)
    }

    @Test fun vietnameseInputModesSurviveSubtypeReloadAndSelection() {
        val prefs = latinIME.prefs()
        val vietnameseModes = SubtypeSettings.getResourceSubtypesForLocale("vi".constructLocale())
        val telex = vietnameseModes.single {
            it.toSettingsSubtype().getExtraValueOf("CombiningRules") == "vi_telex"
        }
        val vni = vietnameseModes.single {
            it.toSettingsSubtype().getExtraValueOf("CombiningRules") == "vi_vni"
        }
        val raw = vietnameseModes.single {
            it.toSettingsSubtype().getExtraValueOf("CombiningRules") == "vi_raw"
        }
        val english = SubtypeSettings.getResourceSubtypesForLocale("en-US".constructLocale()).single()
        prefs.edit()
            .putString(
                Settings.PREF_ENABLED_SUBTYPES,
                SubtypeSettings.createPrefSubtypes(listOf(telex, vni, raw, english).map { it.toSettingsSubtype() }),
            )
            .putString(Settings.PREF_SELECTED_SUBTYPE, raw.toSettingsSubtype().toPref())
            .apply()

        // Simulates the process-local registry being rebuilt on service recreation/app restart.
        SubtypeSettings.reloadEnabledSubtypes(latinIME)

        assertEquals(
            setOf("vi_telex", "vi_vni", "vi_raw"),
            SubtypeSettings.getEnabledSubtypes()
                .filter { it.locale().language == "vi" }
                .map { it.toSettingsSubtype().getExtraValueOf("CombiningRules") }
                .toSet(),
        )
        assertEquals("vi_raw", SubtypeSettings.getSelectedSubtype(prefs)
            .toSettingsSubtype().getExtraValueOf("CombiningRules"))
    }

    @Test fun upgradeCanonicalizesRemovedLanguagePreferencesWithoutLeavingDanglingState() {
        val prefs = latinIME.prefs()
        prefs.edit()
            .putInt(Settings.PREF_VERSION_CODE, 3_000_000)
            .putString(
                Settings.PREF_ENABLED_SUBTYPES,
                listOf(
                    "en-GB${Separators.SET}KeyboardLayoutSet=MAIN:qwerty",
                    "de${Separators.SET}KeyboardLayoutSet=MAIN:qwertz",
                    "ko${Separators.SET}CombiningRules=hangul,KeyboardLayoutSet=MAIN:korean_phonetic",
                    "vi${Separators.SET}CombiningRules=vi_vni,KeyboardLayoutSet=MAIN:qwerty",
                    "vi${Separators.SET}CombiningRules=vi_raw,KeyboardLayoutSet=MAIN:qwerty",
                ).joinToString(Separators.SETS),
            )
            .putString(Settings.PREF_ADDITIONAL_SUBTYPES, "fr${Separators.SET}KeyboardLayoutSet=MAIN:azerty")
            .putString(Settings.PREF_SELECTED_SUBTYPE, "de${Separators.SET}KeyboardLayoutSet=MAIN:qwertz")
            .putString(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX + "removed", "fr${Separators.SET}")
            .putString(
                Settings.PREF_SAVED_APP_SUBTYPE_PREFIX + "vietnamese",
                "vi${Separators.SET}CombiningRules=vi_raw,KeyboardLayoutSet=MAIN:qwerty",
            )
            .commit()

        checkVersionUpgrade(latinIME)

        assertEquals("", prefs.getString(Settings.PREF_ADDITIONAL_SUBTYPES, null))
        val enabled = SubtypeSettings.createSettingsSubtypes(
            prefs.getString(Settings.PREF_ENABLED_SUBTYPES, "")!!
        )
        assertEquals(setOf("en", "ko", "vi"), enabled.map { it.locale.language }.toSet())
        assertTrue(enabled.any { it.getExtraValueOf("CombiningRules") == "vi_vni" })
        assertTrue(enabled.any { it.getExtraValueOf("CombiningRules") == "vi_raw" })
        assertEquals(
            "vi_telex",
            prefs.getString(Settings.PREF_SELECTED_SUBTYPE, "")!!
                .toSettingsSubtype().getExtraValueOf("CombiningRules"),
        )
        assertNull(prefs.getString(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX + "removed", null))
        assertEquals(
            "vi_raw",
            prefs.getString(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX + "vietnamese", "")!!
                .toSettingsSubtype().getExtraValueOf("CombiningRules"),
        )

        SubtypeSettings.reloadEnabledSubtypes(latinIME)
        assertTrue(SubtypeSettings.getEnabledSubtypes().isNotEmpty())
    }
}
