package ink.underflo.wristbrief.mobile

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Check all resource XML files, not just strings.xml, as the product grows. */
class LocalizedResourceCoverageTest {
    @Test fun allEnglishAndChineseTextResourcesHaveMatchingKeysWithoutDuplicates() {
        val root = listOf(File("src/main/res"), File("mobile/src/main/res")).first { it.isDirectory }
        fun keys(directory: File): Set<String> {
            val result = mutableSetOf<String>()
            val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            directory.listFiles { file -> file.extension == "xml" }!!.forEach { file ->
                val children = builder.parse(file).documentElement.childNodes
                for (index in 0 until children.length) {
                    val element = children.item(index) as? Element ?: continue
                    if (element.tagName !in setOf("string", "plurals", "string-array")) continue
                    if (element.getAttribute("translatable") == "false") continue
                    val key = "${element.tagName}/${element.getAttribute("name")}"
                    assertTrue("Duplicate localized resource $key in $directory", result.add(key))
                }
            }
            return result
        }
        assertEquals(keys(File(root, "values")), keys(File(root, "values-zh-rCN")))
    }
}
