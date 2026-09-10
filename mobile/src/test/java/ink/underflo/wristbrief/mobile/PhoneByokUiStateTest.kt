package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneByokUiStateTest {
    @Test
    fun `managed mode never forwards provider credentials`() {
        val editor = PhoneByokEditorState(
            provider = PhoneByokProvider.Gemini,
            model = "gemini-2.5-flash",
            apiKey = "secret-key",
        )

        assertNull(PhoneSummaryAccessMode.Managed.toRequestConfig(editor))
    }

    @Test
    fun `byok mode maps openrouter editor state to client config`() {
        val config = PhoneSummaryAccessMode.Byok.toRequestConfig(
            PhoneByokEditorState(
                provider = PhoneByokProvider.OpenRouter,
                model = "openai/gpt-5-mini",
                apiKey = "provider-key",
            ),
        )!!

        assertEquals(PhoneByokProvider.OpenRouter, config.provider)
        assertEquals("openai/gpt-5-mini", config.model)
        assertEquals("provider-key", config.apiKey)
    }

    @Test
    fun `byok mode maps gemini editor state to client config`() {
        val config = PhoneSummaryAccessMode.Byok.toRequestConfig(
            PhoneByokEditorState(
                provider = PhoneByokProvider.Gemini,
                model = "gemini-2.5-flash",
                apiKey = "gemini-key",
            ),
        )!!

        assertEquals(PhoneByokProvider.Gemini, config.provider)
        assertEquals("gemini-2.5-flash", config.model)
        assertEquals("gemini-key", config.apiKey)
    }

    @Test
    fun `leaving byok mode clears provider key`() {
        assertEquals(
            "",
            byokKeyAfterAccessModeChange(
                previousMode = PhoneSummaryAccessMode.Byok,
                newMode = PhoneSummaryAccessMode.Managed,
                currentKey = "provider-secret",
            ),
        )
    }

    @Test
    fun `switching byok providers clears provider key`() {
        assertEquals(
            "",
            byokKeyAfterProviderChange(
                previousProvider = PhoneByokProvider.OpenRouter,
                newProvider = PhoneByokProvider.Gemini,
                currentKey = "openrouter-secret",
            ),
        )
    }

    @Test
    fun `staying on same byok provider preserves current key`() {
        assertEquals(
            "gemini-secret",
            byokKeyAfterProviderChange(
                previousProvider = PhoneByokProvider.Gemini,
                newProvider = PhoneByokProvider.Gemini,
                currentKey = "gemini-secret",
            ),
        )
    }
}