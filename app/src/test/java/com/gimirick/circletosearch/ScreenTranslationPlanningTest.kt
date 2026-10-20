package com.gimirick.circletosearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTranslationPlanningTest {
    @Test
    fun aggregateSampleIsTrimmedAndBounded() {
        val sample = buildAggregateLanguageSample(
            blockTexts = listOf("  first  ", "", "second block", "third"),
            maximumLength = 18,
        )

        assertEquals("first\nsecond block", sample)
        assertEquals(18, sample.length)
    }

    @Test
    fun normalizesRegionalLanguageTagsAndRejectsUndeterminedLanguage() {
        assertEquals("pt", normalizeTranslationLanguageTag(" pt-BR "))
        assertEquals("zh", normalizeTranslationLanguageTag("ZH_Hant"))
        assertEquals("tl", normalizeTranslationLanguageTag("fil"))
        assertEquals("tl", normalizeTranslationLanguageTag("fil-PH"))
        assertNull(normalizeTranslationLanguageTag("und"))
        assertNull(normalizeTranslationLanguageTag("  "))
    }

    @Test
    fun blockLanguageSampleIsTrimmedAndBounded() {
        assertEquals(
            "language",
            buildBlockLanguageSample("   language model input   ", maximumLength = 8),
        )
    }

    @Test
    fun shortUndeterminedBlockInheritsReliableAggregateLanguage() {
        assertEquals(
            "ru",
            resolveBlockSourceLanguage(
                text = "Настройки",
                detectedLanguageTag = "und",
                aggregateLanguage = AggregateLanguage("ru", confidence = 0.91f),
            ),
        )
    }

    @Test
    fun longUndeterminedBlockDoesNotGuessFromAggregateLanguage() {
        assertNull(
            resolveBlockSourceLanguage(
                text = "A deliberately long unidentified sentence from another language",
                detectedLanguageTag = "und",
                aggregateLanguage = AggregateLanguage("ru", confidence = 0.91f),
            ),
        )
    }

    @Test
    fun unreliableAggregateLanguageIsNotInherited() {
        assertNull(
            resolveBlockSourceLanguage(
                text = "Короткий текст",
                detectedLanguageTag = null,
                aggregateLanguage = AggregateLanguage(
                    languageTag = "ru",
                    confidence = MIN_RELIABLE_AGGREGATE_CONFIDENCE - 0.01f,
                ),
            ),
        )
    }

    @Test
    fun detectedBlockLanguageWinsOverAggregateLanguage() {
        assertEquals(
            "fr",
            resolveBlockSourceLanguage(
                text = "Bonjour",
                detectedLanguageTag = "fr-CA",
                aggregateLanguage = AggregateLanguage("en", confidence = 0.99f),
            ),
        )
    }

    @Test
    fun groupsBlocksByLanguagePairAndSkipsTargetLanguage() {
        val groups = buildTranslationLanguageGroups(
            blockTexts = listOf(
                "Bonjour tout le monde",
                "Настройки",
                "Это предложение достаточно длинное для самостоятельного определения",
                "Already English",
            ),
            detectedLanguageTags = listOf("fr", "und", "ru", "en"),
            aggregateLanguage = AggregateLanguage("ru", confidence = 0.95f),
            targetLanguageTag = "en-US",
        )

        assertEquals(
            listOf(0),
            groups[TranslationLanguagePair(sourceLanguage = "fr", targetLanguage = "en")],
        )
        assertEquals(
            listOf(1, 2),
            groups[TranslationLanguagePair(sourceLanguage = "ru", targetLanguage = "en")],
        )
        assertEquals(2, groups.size)
        assertTrue(groups.values.flatten().none { it == 3 })
    }

    @Test
    fun verifyMlKitSupportedLanguagesResolveCorrectly() {
        val dialogCodes = listOf(
            "af", "sq", "ar", "be", "bn", "bg", "ca", "zh", "zh-TW", "hr", "cs", "da", "nl", "en",
            "eo", "et", "tl", "fi", "fr", "gl", "ka", "de", "el", "gu", "ht", "he", "hi", "hu",
            "is", "id", "ga", "it", "ja", "kn", "ko", "lv", "lt", "mk", "ms", "mt", "mr", "nb",
            "fa", "pl", "pt", "ro", "ru", "sk", "sl", "es", "sw", "sv", "ta", "te", "th", "tr",
            "uk", "ur", "vi", "cy"
        )
        for (code in dialogCodes) {
            val normalized = normalizeTranslationLanguageTag(code)
            org.junit.Assert.assertNotNull("Language $code failed to normalize", normalized)
            val mlKitLanguage = com.google.mlkit.nl.translate.TranslateLanguage.fromLanguageTag(normalized!!)
            org.junit.Assert.assertNotNull("Language $code ($normalized) is not supported by ML Kit", mlKitLanguage)
        }
    }
}
