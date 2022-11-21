package eu.kanade.tachiyomi.extension.en.luminousscansunofficial

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LuminousScansFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        LuminousScans(),
    )
}

class LuminousScans : WPMangaReader(
    "Luminous Scans Unofficial",
    "https://www.luminousscans.com",
    "en",
    "/series"
) {
    override val pageSelector = "div#readerarea img[class*=wp-image-]"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select(pageSelector).mapIndexed { i, img ->
            Page(i, "", img.attr("abs:src"))
        }
    }

    // Permanent Url for Manga/Chapter End
    override fun searchMangaFromElement(element: Element): SManga {
        return super.searchMangaFromElement(element).tempUrlToPermIfNeeded()
    }

    private fun SManga.tempUrlToPermIfNeeded(): SManga {
        val turnTempUrlToPerm = preferences.getBoolean(getPermanentMangaUrlPreferenceKey(), true)
        if (!turnTempUrlToPerm) return this
        this.url = this.url.replaceFirst(TEMP_TO_PERM_URL_REGEX, "$1")
        return this
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val permanentMangaUrlPref = SwitchPreferenceCompat(screen.context).apply {
            key = getPermanentMangaUrlPreferenceKey()
            title = PREF_PERM_MANGA_URL_TITLE
            summary = PREF_PERM_MANGA_URL_SUMMARY
            setDefaultValue(true)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean
                preferences.edit()
                    .putBoolean(getPermanentMangaUrlPreferenceKey(), checkValue)
                    .commit()
            }
        }
        screen.addPreference(permanentMangaUrlPref)
    }

    private fun getPermanentMangaUrlPreferenceKey(): String {
        return PREF_PERM_MANGA_URL_KEY_PREFIX + lang
    }
    // Permanent Url for Manga/Chapter End

    companion object {
        private const val PREF_PERM_MANGA_URL_KEY_PREFIX = "pref_permanent_manga_url_"
        private const val PREF_PERM_MANGA_URL_TITLE = "Permanent Manga URL"
        private const val PREF_PERM_MANGA_URL_SUMMARY = "Turns all manga urls into permanent ones."

        private val TEMP_TO_PERM_URL_REGEX = Regex("""(/)\d*-""")
    }
}
