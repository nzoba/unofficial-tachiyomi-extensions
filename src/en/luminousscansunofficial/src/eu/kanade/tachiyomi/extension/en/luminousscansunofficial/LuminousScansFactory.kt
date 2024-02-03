package eu.kanade.tachiyomi.extension.en.luminousscansunofficial

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.concurrent.TimeUnit

class LuminousScansFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        LuminousScans(),
    )
}

class LuminousScans : WPMangaReader(
    "Luminous Scans Unofficial",
    "https://luminousscans.net",
    "en",
    "/series",
) {
    override val pageSelector = "div#readerarea img[class*=wp-image-]"

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient = super.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .rateLimit(1, 1, TimeUnit.SECONDS)
        .addInterceptor(::urlChangeInterceptor)
        .build()

    override fun pageListParse(document: Document): List<Page> {
        return document.select(pageSelector).mapIndexed { i, img ->
            Page(i, document.location(), img.attr("abs:src"))
        }
    }

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

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val newManga = manga.titleToUrlFrag()

        return super.fetchChapterList(newManga)
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        val newManga = manga.titleToUrlFrag()

        return super.fetchMangaDetails(newManga)
    }

    override fun getMangaUrl(manga: SManga): String {
        val dbSlug = manga.url
            .substringBefore("#")
            .removeSuffix("/")
            .substringAfterLast("/")

        val storedSlug = preferences.slugMap[dbSlug] ?: dbSlug

        return "$baseUrl$mangaUrlDirectory/$storedSlug/"
    }

    private fun Observable<MangasPage>.tempUrlToPermIfNeeded(): Observable<MangasPage> {
        return this.map { mangasPage ->
            MangasPage(
                mangasPage.mangas.map { it.tempUrlToPermIfNeeded() },
                mangasPage.hasNextPage,
            )
        }
    }

    private fun SManga.tempUrlToPermIfNeeded(): SManga {
        if (!preferences.permaUrlPref) return this

        val slugMap = preferences.slugMap

        val sMangaTitleFirstWord = this.title.split(" ")[0]
        if (!this.url.contains("/$sMangaTitleFirstWord", ignoreCase = true)) {
            val currentSlug = this.url
                .removeSuffix("/")
                .substringAfterLast("/")

            val permaSlug = currentSlug.replaceFirst(TEMP_TO_PERM_REGEX, "")

            slugMap[permaSlug] = currentSlug

            this.url = "$mangaUrlDirectory/$permaSlug/"
        }
        preferences.slugMap = slugMap
        return this
    }

    private fun SManga.titleToUrlFrag(): SManga {
        return try {
            this.apply {
                url = "$url#${title.toSearchQuery()}"
            }
        } catch (e: UninitializedPropertyAccessException) {
            // when called from deep link, title is not present
            this
        }
    }

    private fun urlChangeInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val frag = request.url.fragment

        if (frag.isNullOrEmpty()) {
            return chain.proceed(request)
        }

        val dbSlug = request.url.toString()
            .substringBefore("#")
            .removeSuffix("/")
            .substringAfterLast("/")

        val slugMap = preferences.slugMap

        val storedSlug = slugMap[dbSlug] ?: dbSlug

        val response = chain.proceed(
            request.newBuilder()
                .url("$baseUrl$mangaUrlDirectory/$storedSlug/")
                .build(),
        )

        if (!response.isSuccessful && response.code == 404) {
            response.close()

            val newSlug = getNewSlug(storedSlug, frag)
                ?: throw IOException("Migrate from Luminous to Luminous")

            slugMap[dbSlug] = newSlug
            preferences.slugMap = slugMap

            return chain.proceed(
                request.newBuilder()
                    .url("$baseUrl$mangaUrlDirectory/$newSlug/")
                    .build(),
            )
        }

        return response
    }

    private fun getNewSlug(existingSlug: String, frag: String): String? {
        val permaSlug = existingSlug
            .replaceFirst(TEMP_TO_PERM_REGEX, "")

        val search = frag.substringBefore("#")

        val mangas = client.newCall(searchMangaRequest(1, search, FilterList()))
            .execute()
            .use {
                searchMangaParse(it)
            }

        return mangas.mangas.firstOrNull { newManga ->
            newManga.url.contains(permaSlug, true)
        }
            ?.url
            ?.removeSuffix("/")
            ?.substringAfterLast("/")
    }

    private fun String.toSearchQuery(): String {
        return this.trim()
            .lowercase()
            .replace(titleSpecialCharactersRegex, "+")
            .replace(trailingPlusRegex, "")
    }

    private var SharedPreferences.slugMap: MutableMap<String, String>
        get() {
            val serialized = getString(PREF_URL_MAP, null) ?: return mutableMapOf()

            return try {
                json.decodeFromString(serialized)
            } catch (e: Exception) {
                mutableMapOf()
            }
        }
        set(slugMap) {
            val serialized = json.encodeToString(slugMap)
            edit().putString(PREF_URL_MAP, serialized).commit()
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
    private val SharedPreferences.permaUrlPref
        get() = getBoolean(getPermanentMangaUrlPreferenceKey(), true)
    // Permanent Url for Manga/Chapter End

    companion object {
        private const val PREF_PERM_MANGA_URL_KEY_PREFIX = "pref_permanent_manga_url_"
        private const val PREF_PERM_MANGA_URL_TITLE = "Permanent Manga URL"
        private const val PREF_PERM_MANGA_URL_SUMMARY = "Turns all manga urls into permanent ones."

        private const val PREF_URL_MAP = "pref_url_map"
        private val TEMP_TO_PERM_REGEX = Regex("""^\d+-""")
        private val titleSpecialCharactersRegex = Regex("""[^a-z0-9]+""")
        private val trailingPlusRegex = Regex("""\++$""")
    }
}
