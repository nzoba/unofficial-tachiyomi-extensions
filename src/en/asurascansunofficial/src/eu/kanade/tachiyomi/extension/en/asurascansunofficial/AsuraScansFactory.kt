package eu.kanade.tachiyomi.extension.en.asurascansunofficial

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.OkHttpClient
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class AsuraScansFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        AsuraScans(),
    )
}

class AsuraScans : MangaThemesia("Asura Scans Unofficial", "https://www.asurascans.com", "en"), ConfigurableSource {
    override val pageSelector = "div#readerarea img[class*=wp-image-]"

    override val seriesDescriptionSelector = "div.desc p, div.entry-content p, div[itemprop=description]:not(:has(p))"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Permanent Url for Manga/Chapter End
    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return super.fetchPopularManga(page).tempUrlToPermIfNeeded()
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return super.fetchLatestUpdates(page).tempUrlToPermIfNeeded()
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return super.fetchSearchManga(page, query, filters).tempUrlToPermIfNeeded()
    }

    private fun Observable<MangasPage>.tempUrlToPermIfNeeded(): Observable<MangasPage> {
        return this.map { mangasPage ->
            MangasPage(
                mangasPage.mangas.map { it.tempUrlToPermIfNeeded() },
                mangasPage.hasNextPage
            )
        }
    }

    private fun SManga.tempUrlToPermIfNeeded(): SManga {
        val turnTempUrlToPerm = preferences.getBoolean(getPermanentMangaUrlPreferenceKey(), true)
        if (!turnTempUrlToPerm) return this

        val sMangaTitleFirstWord = this.title.split(" ")[0]
        if (!this.url.contains("/$sMangaTitleFirstWord", ignoreCase = true)) {
            this.url = this.url.replaceFirst(TEMP_TO_PERM_URL_REGEX, "$1")
        }
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

        private val TEMP_TO_PERM_URL_REGEX = Regex("""(/)\d+-""")
    }
}
