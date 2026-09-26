package ink.underflo.wristbrief.mobile.artifacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The transcript is generated in timed clips (4 minutes each) — there is no word-level
 * timestamping and no diarization. These assertions keep the shipped copy honest in both
 * languages and keep the two resource files at 1:1 key parity.
 */
class TranscriptCopyContractTest {

    private fun stringsFile(locale: String): File {
        val candidates = if (locale == "en") {
            listOf("src/main/res/values/strings.xml", "mobile/src/main/res/values/strings.xml")
        } else {
            listOf(
                "src/main/res/values-zh-rCN/strings.xml",
                "mobile/src/main/res/values-zh-rCN/strings.xml",
            )
        }
        for (path in candidates) {
            val file = File(path)
            if (file.isFile) return file
        }
        return File(candidates.first())
    }

    private fun entries(file: File): Map<String, String> {
        assertTrue("missing strings file: ${file.path}", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val result = linkedMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            val name = element.getAttribute("name")
            if (name.isNotBlank()) result[name] = element.textContent.orEmpty()
        }
        return result
    }

    private val english by lazy { entries(stringsFile("en")) }
    private val chinese by lazy { entries(stringsFile("zh")) }

    @Test
    fun bothLanguagesDefineTheSameKeys() {
        val missingInChinese = english.keys - chinese.keys
        val missingInEnglish = chinese.keys - english.keys
        assertEquals("keys only in English: $missingInChinese", emptySet<String>(), missingInChinese)
        assertEquals("keys only in Chinese: $missingInEnglish", emptySet<String>(), missingInEnglish)
    }

    @Test
    fun theFiveTranscriptStatesAreAllLocalizedAndDistinct() {
        val stateKeys = listOf(
            "transcript_state_checking",
            "transcript_state_queued",
            "transcript_state_processing",
            "transcript_state_failed",
            "transcript_retry_action",
        )
        val englishLabels = stateKeys.map { key ->
            assertTrue("missing $key in English", english.containsKey(key))
            assertTrue("missing $key in Chinese", chinese.containsKey(key))
            english.getValue(key)
        }
        // Queued and processing must be different words in both languages.
        assertFalse(
            "queued and processing must not share a label in English: " +
                "'${english.getValue("transcript_state_queued")}'",
            english.getValue("transcript_state_queued") == english.getValue("transcript_state_processing"),
        )
        assertFalse(
            "queued and processing must not share a label in Chinese: " +
                "'${chinese.getValue("transcript_state_queued")}'",
            chinese.getValue("transcript_state_queued") == chinese.getValue("transcript_state_processing"),
        )
        assertEquals(
            "the five state labels must all be different strings",
            stateKeys.size,
            englishLabels.toSet().size,
        )
    }

    /**
     * Positive promises that must never ship. A value that explicitly denies the promise
     * ("a timestamp marks a passage, not each spoken word" / "而不是每个词") is the correct
     * product-truth wording and must pass, so matching is negation-aware.
     */
    private val promisePatterns = listOf(
        "word-level",
        "word level",
        "word-by-word",
        "word by word",
        "per-word",
        "per word",
        "逐词",
        "逐字",
        "每个词",
        "单个词",
    )

    private val negations = listOf(
        "not ", "never", "no ", "without", "instead of", "rather than",
        "不是", "不会", "不能", "不提供", "而不", "并非", "无法",
    )

    private fun promisesWordLevel(value: String): Boolean {
        val lower = value.lowercase()
        if (negations.any { lower.contains(it) }) return false
        return promisePatterns.any { lower.contains(it) }
    }

    @Test
    fun noShippedCopyPromisesWordLevelTranscription() {
        val offending = (english + chinese).filter { (_, value) -> promisesWordLevel(value) }
        assertTrue(
            "these strings present word-level output as delivered; " +
                "word-level wording is only acceptable inside an explicit denial: $offending",
            offending.isEmpty(),
        )
    }

    /**
     * The explanation itself is checked directly, not through the promise scan: it is the
     * one place allowed to name words, because it exists to say they are NOT timestamped.
     */
    @Test
    fun thePositioningExplainerMayNameWordsOnlyToDenyThem() {
        val englishExplainer = english.getValue("transcript_positioning_explainer")
        val chineseExplainer = chinese.getValue("transcript_positioning_explainer")
        assertTrue(
            "English explainer must deny per-word positioning, got: $englishExplainer",
            englishExplainer.contains("not each spoken word"),
        )
        assertTrue(
            "Chinese explainer must deny per-word positioning, got: $chineseExplainer",
            chineseExplainer.contains("而不是每个词"),
        )
    }

    @Test
    fun thePositioningExplainerStatesWhatATimestampActuallyLocalizes() {
        val englishExplainer = english.getValue("transcript_positioning_explainer")
        val chineseExplainer = chinese.getValue("transcript_positioning_explainer")

        // It must name the real granularity (clips / 4 minutes) in both languages.
        assertTrue("English explainer must name the clip granularity", englishExplainer.contains("4 minutes"))
        assertTrue("Chinese explainer must name the clip granularity", chineseExplainer.contains("4 分钟"))
        // And it must say a timestamp marks a passage, not a word.
        assertTrue(englishExplainer.contains("passage"))
        assertTrue(chineseExplainer.contains("一段话"))
    }

    @Test
    fun transcriptFeatureCopyInOnboardingNoLongerClaimsWordLevelOutput() {
        val englishFeature = english.getValue("oobe_feature_transcripts")
        val chineseFeature = chinese.getValue("oobe_feature_transcripts")
        // Scoped to the promise forms, not a bare "word" substring: an honest sentence is
        // allowed to mention words in order to deny per-word output.
        assertFalse(
            "the onboarding transcript line still promises word-level output: $englishFeature",
            promisesWordLevel(englishFeature),
        )
        assertFalse(
            "the onboarding transcript line still promises word-level output: $chineseFeature",
            promisesWordLevel(chineseFeature),
        )
        assertTrue(
            "onboarding must state the real granularity (clip level), got: $englishFeature",
            englishFeature.contains("clip"),
        )
        assertTrue(
            "onboarding must state the real granularity (片段), got: $chineseFeature",
            chineseFeature.contains("片段"),
        )
    }

    @Test
    fun transcriptEntriesAreLabelledAsSegmentsNotWords() {
        assertTrue(
            "the segment count label must say segments, got: ${english.getValue("transcript_segment_count")}",
            english.getValue("transcript_segment_count").contains("segment"),
        )
        assertTrue(
            "the Chinese segment count label must say 分段, got: ${chinese.getValue("transcript_segment_count")}",
            chinese.getValue("transcript_segment_count").contains("分段"),
        )
        assertTrue(
            "the viewer title must not promise verbatim word-for-word output, got: ${english.getValue("transcript_title")}",
            english.getValue("transcript_title").contains("Transcript"),
        )
        assertTrue(
            "the Chinese viewer title must say 转写, not 逐字稿, got: ${chinese.getValue("transcript_title")}",
            chinese.getValue("transcript_title").contains("转写"),
        )
        assertFalse(
            "the Chinese viewer title still uses the word-for-word 逐字稿 wording",
            chinese.getValue("transcript_title").contains("逐字稿"),
        )
    }

    @Test
    fun everySpeechPreviewMessageExistsInBothLanguages() {
        val required = listOf(
            "speech_preview_title",
            "speech_preview_body",
            "speech_preview_ai_voice_disclosure",
            "speech_preview_input_label",
            "speech_preview_input_hint",
            "speech_preview_character_count",
            "speech_preview_over_limit",
            "speech_preview_play",
            "speech_preview_pause",
            "speech_preview_loading",
            "speech_preview_retry",
            "speech_preview_stop",
            "speech_preview_error_network",
            "speech_preview_error_unauthorized",
            "speech_preview_error_quota",
            "speech_preview_error_too_long",
            "speech_preview_error_invalid_text",
            "speech_preview_error_provider",
            "speech_preview_error_generation",
            "speech_preview_error_unavailable",
            "speech_preview_long_form_unavailable",
        )
        val missingEn = required.filterNot { english.containsKey(it) }
        val missingZh = required.filterNot { chinese.containsKey(it) }
        assertTrue("missing in English: $missingEn", missingEn.isEmpty())
        assertTrue("missing in Chinese: $missingZh", missingZh.isEmpty())
    }

    @Test
    fun theAiVoiceDisclosureIsExplicitInBothLanguages() {
        val englishDisclosure = english.getValue("speech_preview_ai_voice_disclosure").lowercase()
        val chineseDisclosure = chinese.getValue("speech_preview_ai_voice_disclosure")
        assertTrue(
            "the disclosure must say the voice is AI; got: $englishDisclosure",
            englishDisclosure.contains("ai"),
        )
        assertTrue(
            "the disclosure must say it is a voice/audio; got: $englishDisclosure",
            englishDisclosure.contains("voice"),
        )
        assertTrue(
            "the Chinese disclosure must say AI; got: $chineseDisclosure",
            chineseDisclosure.contains("AI"),
        )
        assertTrue(
            "the Chinese disclosure must say 语音; got: $chineseDisclosure",
            chineseDisclosure.contains("语音"),
        )
    }

    @Test
    fun longFormNarrationIsExplicitlyNotPresentedAsWorking() {
        val englishNote = english.getValue("speech_preview_long_form_unavailable").lowercase()
        val chineseNote = chinese.getValue("speech_preview_long_form_unavailable")
        assertTrue(
            "the long-form note must say the feature is not available; got: $englishNote",
            englishNote.contains("not available"),
        )
        assertTrue(
            "the Chinese long-form note must say 尚未提供; got: $chineseNote",
            chineseNote.contains("尚未提供"),
        )
    }

    /** Matches "2000" or the grouped "2,000" / "2 000" form a locale may use. */
    private fun mentionsCharacterBound(value: String): Boolean {
        val normalized = value.replace(",", "").replace("\u00A0", "").replace(" ", "")
        return normalized.contains("2000")
    }

    @Test
    fun theOverLimitMessageRefusesSilentTruncation() {
        val englishNote = english.getValue("speech_preview_over_limit").lowercase()
        val chineseNote = chinese.getValue("speech_preview_over_limit")
        assertTrue(
            "the over-limit message must state the real 2000-character bound; got: $englishNote",
            mentionsCharacterBound(englishNote),
        )
        assertTrue(
            "the over-limit message must promise no truncation; got: $englishNote",
            englishNote.contains("nothing was trimmed"),
        )
        assertTrue(
            "the over-limit message must state that nothing was sent; got: $englishNote",
            englishNote.contains("nothing was sent"),
        )
        assertTrue(
            "the Chinese over-limit message must state the bound; got: $chineseNote",
            mentionsCharacterBound(chineseNote),
        )
        assertTrue(
            "the Chinese over-limit message must promise no truncation; got: $chineseNote",
            chineseNote.contains("不会被截断"),
        )
        assertTrue(
            "the Chinese over-limit message must state that nothing was sent; got: $chineseNote",
            chineseNote.contains("也不会被发送"),
        )
    }
}
