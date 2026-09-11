package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StringResourceParityTest {

    @Test
    fun englishAndChineseStringKeysMatchExactly() {
        val enFile = resolveFile("src/main/res/values/strings.xml", "mobile/src/main/res/values/strings.xml")
        val zhFile = resolveFile("src/main/res/values-zh-rCN/strings.xml", "mobile/src/main/res-zh-rCN/values/strings.xml", "mobile/src/main/res/values-zh-rCN/strings.xml")

        assertTrue("English strings.xml should exist", enFile.isFile)
        assertTrue("Chinese strings.xml should exist", zhFile.isFile)

        val enKeys = extractStringKeys(enFile)
        val zhKeys = extractStringKeys(zhFile)

        assertTrue("English strings should not be empty", enKeys.isNotEmpty())
        assertTrue("Chinese strings should not be empty", zhKeys.isNotEmpty())

        val missingInZh = enKeys - zhKeys
        val missingInEn = zhKeys - enKeys

        assertEquals("Keys present in values/strings.xml but missing in values-zh-rCN/strings.xml: $missingInZh", emptySet<String>(), missingInZh)
        assertEquals("Keys present in values-zh-rCN/strings.xml but missing in values/strings.xml: $missingInEn", emptySet<String>(), missingInEn)
    }

    private fun resolveFile(vararg candidatePaths: String): File {
        for (path in candidatePaths) {
            val file = File(path)
            if (file.isFile) return file
        }
        return File(candidatePaths.first())
    }

    private fun extractStringKeys(file: File): Set<String> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        val nodeList = doc.getElementsByTagName("string")
        val keys = mutableSetOf<String>()
        for (i in 0 until nodeList.length) {
            val element = nodeList.item(i) as Element
            val name = element.getAttribute("name")
            if (name.isNotBlank()) {
                keys.add(name)
            }
        }
        return keys
    }
}
