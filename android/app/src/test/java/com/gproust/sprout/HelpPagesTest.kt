package com.gproust.sprout

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.ui.help.HelpPage
import com.gproust.sprout.ui.help.HelpSection
import com.gproust.sprout.ui.help.helpEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * The help sheets (BDR-26): every page says something, in every language, and
 * the same thing on both phones.
 *
 * A key missing from one language is lint's to catch; what is checked here is
 * what lint cannot see — a page with nothing to say, a body filed under the
 * wrong page, a text left in English in a translation, and the iOS list
 * drifting away from this one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class HelpPagesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The seven languages Sprout speaks (BDR-4), English being the default. */
    private val languages = listOf("en", "fr", "de", "es", "it", "pl", "pt")

    /** The Swift list, from `android/app`, where Gradle runs the unit tests. */
    private val swiftList = File("../../ios/Sprout/Sources/Screens/Help/HelpPages.swift")

    private fun name(@StringRes id: Int): String = context.resources.getResourceEntryName(id)

    private fun localized(language: String): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(language))
        return context.createConfigurationContext(config)
    }

    /**
     * Every string the list names, in the order it names them: the section
     * headings, then the pages' titles, then each page's entries. It is the
     * order `HelpPages.swift` is written in too.
     */
    private fun keysInOrder(): List<Int> =
        HelpSection.entries.map { it.title } +
            HelpPage.entries.map { it.title } +
            HelpPage.entries.flatMap { page -> helpEntries(page).map { it.body } }

    @Test
    fun everyPageSaysWhatItIsForFirst() {
        HelpPage.entries.forEach { page ->
            val sections = helpEntries(page).map { it.section }
            assertTrue("${page.key} has no help", sections.isNotEmpty())
            assertEquals("${page.key} does not open on what it is for", HelpSection.PURPOSE, sections.first())
            assertEquals(
                "${page.key} repeats a section or puts them out of order",
                sections.distinct().sortedBy { it.ordinal },
                sections,
            )
        }
    }

    /** `help_feeding_how` under Feeding's "How to use it", and nowhere else. */
    @Test
    fun everyBodyIsNamedForItsPageAndSection() {
        HelpPage.entries.forEach { page ->
            helpEntries(page).forEach { entry ->
                assertEquals("help_${page.key}_${entry.section.key}", name(entry.body))
            }
        }
    }

    @Test
    fun pageKeysAreDistinct() {
        assertEquals(HelpPage.entries.size, HelpPage.entries.map { it.key }.distinct().size)
    }

    /** The words resolve, are not blank, and are not English in another language. */
    @Test
    fun everyKeyReadsInEveryLanguage() {
        val english = localized("en")
        languages.forEach { language ->
            val resources = localized(language).resources
            keysInOrder().forEach { id ->
                val text = resources.getString(id)
                assertTrue("${name(id)} is blank in $language", text.isNotBlank())
                // Help only: a page title such as "Home" is the same word in
                // Italian, but a paragraph that is is an untranslated one.
                if (language != "en" && name(id).startsWith("help_")) {
                    assertNotEquals(
                        "${name(id)} is still in English in $language",
                        english.resources.getString(id),
                        text,
                    )
                }
            }
        }
    }

    /**
     * A `help_` string no page shows is text one phone has and the other may
     * not: the iOS catalog is generated from these resources, so it would be
     * there to show, and nothing here would say it never is.
     */
    @Test
    fun everyHelpStringIsOnAPage() {
        val shown = keysInOrder().map(::name).toSet()
        val declared = R.string::class.java.fields.map { it.name }.filter { it.startsWith("help_") }
        assertTrue("no help strings found — is this reading the app's R?", declared.isNotEmpty())
        assertEquals(emptyList<String>(), declared.filterNot { it in shown }.sorted())
    }

    /** The same pages, sections and words as the iPhone, in the same order. */
    @Test
    fun theListIsTheOneIosShows() {
        assertTrue(
            "the iOS help list is not at ${swiftList.absolutePath}",
            swiftList.isFile,
        )
        // The same pattern `check_string_keys.py` reads Swift with.
        val swift = Regex("\\bStr\\.t\\(\\s*\"([A-Za-z0-9_]+)\"")
            .findAll(swiftList.readText())
            .map { it.groupValues[1] }
            .toList()
        assertEquals(keysInOrder().map(::name), swift)
    }
}
