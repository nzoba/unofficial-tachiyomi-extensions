package eu.kanade.tachiyomi.extension.fr.japscanunofficial

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.lib.synchrony.Deobfuscator
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class Japscan : ConfigurableSource, ParsedHttpSource() {

    override val name = "Japscan Unofficial"

    override val baseUrl = "https://www.japscan.lol"

    override val lang = "fr"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .rateLimit(1, 1, TimeUnit.SECONDS)
        .build()

    companion object {
        val dateFormat by lazy {
            SimpleDateFormat("dd MMM yyyy", Locale.US)
        }
        private const val ONLY_FRENCH_CHAPTERS_Title = "Français uniquement"
        private const val ONLY_FRENCH_CHAPTERS_Summary = "Montrer uniquement les chapitres traduits en français"
        private const val ONLY_FRENCH_CHAPTERS = "JAPSCAN_ONLY_FRENCH_CHAPTERS_BOOLEAN"

        private const val CUSTOM_DECRYPT_KEYS_Title = "Utiliser des clés de decryptage custom"
        private const val CUSTOM_DECRYPT_KEYS_Summary = "Permet d'indiquer des clés de decryptage manuellement\n" +
            "Exemple : key1,key2\n" +
            "Laisser vide pour utiliser le comportement par défaut."
        private const val CUSTOM_DECRYPT_KEYS = "JAPSCAN_CUSTOM_DECRYPT_KEYS"
        private const val CUSTOM_DECRYPT_FORMAT = "Le format des clés n'est pas valide\n" +
            "Exemple : key1,key2"

        private const val LAST_WORKING_KEYS_PREF = "LAST_WORKING_KEYS_PREF"
    }

    private fun onlyShowFrenchChapters() = preferences.getBoolean(ONLY_FRENCH_CHAPTERS, false)

    private fun customKeysPref(): String = preferences.getString(CUSTOM_DECRYPT_KEYS, "") as String

    private fun lastWorkingKeys() = preferences.getString(LAST_WORKING_KEYS_PREF, null)?.let {
        it.split(",")[0] to it.split(",")[1]
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("referer", "$baseUrl/")

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/mangas/", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        pageNumberDoc = document

        val mangas = document.select(popularMangaSelector()).map { element ->
            popularMangaFromElement(element)
        }
        val hasNextPage = false
        return MangasPage(mangas, hasNextPage)
    }

    override fun popularMangaNextPageSelector(): String? = null

    override fun popularMangaSelector() = "#top_mangas_week li"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.select("a").first()!!.let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.text()
            manga.thumbnail_url = "$baseUrl/imgs/${it.attr("href").replace(Regex("/$"),".jpg").replace("manga","mangas")}".lowercase(Locale.ROOT)
        }
        return manga
    }

    // Latest
    private lateinit var latestDirectory: List<Element>

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return if (page == 1) {
            client.newCall(latestUpdatesRequest(page))
                .asObservableSuccess()
                .map { latestUpdatesParse(it) }
        } else {
            Observable.just(parseLatestDirectory(page))
        }
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET(baseUrl, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()

        latestDirectory = document.select(latestUpdatesSelector())
            .distinctBy { element -> element.select("a").attr("href") }

        return parseLatestDirectory(1)
    }

    private fun parseLatestDirectory(page: Int): MangasPage {
        val manga = mutableListOf<SManga>()
        val end = ((page * 24) - 1).let { if (it <= latestDirectory.lastIndex) it else latestDirectory.lastIndex }

        for (i in (((page - 1) * 24)..end)) {
            manga.add(latestUpdatesFromElement(latestDirectory[i]))
        }

        return MangasPage(manga, end < latestDirectory.lastIndex)
    }

    override fun latestUpdatesSelector() = "#chapters h3.mb-0"

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isEmpty()) {
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("mangas")

                filters.forEach { filter ->
                    when (filter) {
                        is TextField -> addPathSegment(((page - 1) + filter.state.toInt()).toString())
                        is PageList -> addPathSegment(((page - 1) + filter.values[filter.state]).toString())
                        else -> {}
                    }
                }
            }.build()

            return GET(url, headers)
        } else {
            val formBody = FormBody.Builder()
                .add("search", query)
                .build()
            val searchHeaders = headers.newBuilder()
                .add("X-Requested-With", "XMLHttpRequest")
                .build()

            return POST("$baseUrl/live-search/", searchHeaders, formBody)
        }
    }

    override fun searchMangaNextPageSelector(): String = "li.page-item:last-child:not(li.active)"

    override fun searchMangaSelector(): String = "div.card div.p-2"

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.pathSegments.first() == "live-search") {
            val jsonResult = json.parseToJsonElement(response.body!!.string()).jsonArray

            val mangaList = jsonResult.map { jsonEl -> searchMangaFromJson(jsonEl.jsonObject) }

            return MangasPage(mangaList, hasNextPage = false)
        }

        val baseUrlHost = baseUrl.toHttpUrl().host
        val document = response.asJsoup()
        val manga = document
            .select(searchMangaSelector())
            .filter { it ->
                // Filter out ads masquerading as search results
                it.select("p a").attr("abs:href").toHttpUrl().host == baseUrlHost
            }
            .map(::searchMangaFromElement)
        val hasNextPage = document.selectFirst(searchMangaNextPageSelector()) != null

        return MangasPage(manga, hasNextPage)
    }

    override fun searchMangaFromElement(element: Element) = SManga.create().apply {
        thumbnail_url = element.select("img").attr("abs:src")
        element.select("p a").let {
            title = it.text()
            url = it.attr("href")
        }
    }

    private fun searchMangaFromJson(jsonObj: JsonObject): SManga = SManga.create().apply {
        url = jsonObj["url"]!!.jsonPrimitive.content
        title = jsonObj["name"]!!.jsonPrimitive.content
        thumbnail_url = baseUrl + jsonObj["image"]!!.jsonPrimitive.content
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.selectFirst("#main .card-body")!!

        val manga = SManga.create()
        manga.thumbnail_url = infoElement.select("img").attr("abs:src")

        val infoRows = infoElement.select(".row, .d-flex")
        infoRows.select("p").forEach { el ->
            when (el.select("span").text().trim()) {
                "Auteur(s):" -> manga.author = el.text().replace("Auteur(s):", "").trim()
                "Artiste(s):" -> manga.artist = el.text().replace("Artiste(s):", "").trim()
                "Genre(s):" -> manga.genre = el.text().replace("Genre(s):", "").trim()
                "Statut:" -> manga.status = el.text().replace("Statut:", "").trim().let {
                    parseStatus(it)
                }
            }
        }
        manga.description = infoElement.select("div:contains(Synopsis) + p").text().orEmpty()

        return manga
    }

    private fun parseStatus(status: String) = when {
        status.contains("En Pause") -> SManga.ON_HIATUS
        status.contains("En Cours") -> SManga.ONGOING
        status.contains("Terminé") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "#chapters_list > div.collapse > div.chapters_list" +
        if (onlyShowFrenchChapters()) { ":not(:has(.badge:contains(SPOILER),.badge:contains(RAW),.badge:contains(VUS),i:contains(Attention\\: )))" } else { "" }
    // JapScan sometimes uploads some "spoiler preview" chapters, containing 2 or 3 untranslated pictures taken from a raw. Sometimes they also upload full RAWs/US versions and replace them with a translation as soon as available.
    // Those have a span.badge "SPOILER" or "RAW". The additional pseudo selector makes sure to exclude these from the chapter list.

    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.selectFirst("a")!!

        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElement.attr("href"))
        chapter.name = urlElement.ownText()
        // Using ownText() doesn't include childs' text, like "VUS" or "RAW" badges, in the chapter name.
        chapter.date_upload = element.selectFirst("span")!!.text().trim().let { parseChapterDate(it) }
        return chapter
    }

    private fun parseChapterDate(date: String) = runCatching {
        dateFormat.parse(date)!!.time
    }.getOrDefault(0L)

    private val decodingStringsRe: Regex = Regex("""'([\dA-Za-z]{62})'\n*\s*\.split""")

    private val sortedLookupString: List<Char> = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray().toList()

    override fun pageListParse(document: Document): List<Page> {
        val scrambledData = document.getElementById("data")!!.attr("data-data")

        val lastWorkingKeys = lastWorkingKeys()

        // Try if any exist in cache
        if (lastWorkingKeys != null) {
            Log.d("japscan", "Try keys from cache $lastWorkingKeys")
            try {
                return descramble(scrambledData, lastWorkingKeys)
            } catch (_: Throwable) {
                preferences.edit().putString(LAST_WORKING_KEYS_PREF, null).apply()
            }
        }

        // Try custom keys
        for (keys in getCustomKeys()) {
            Log.d("japscan", "Try keys from preference $keys")
            try {
                return descramble(scrambledData, keys)
            } catch (_: Throwable) {}
        }

        // Try zjs keys
        for (keys in getZjsKeys(document)) {
            Log.d("japscan", "Try keys from zjs $keys")
            try {
                return descramble(scrambledData, keys)
            } catch (_: Throwable) {}
        }

        throw Exception("Impossible de trouver les clés de désembrouillage")
    }

    private fun descramble(scrambleData: String, keys: Pair<String, String>): List<Page> {
        Log.d("japscan", "descramble attempt with $keys")
        val lookupTable = keys.first.zip(keys.second).toMap()

        val unscrambledData = scrambleData.map { lookupTable[it] ?: it }.joinToString("")
        if (!unscrambledData.startsWith("ey")) {
            // `ey` is the Base64 representation of a curly bracket. Since we're expecting a
            // JSON object, we're counting this attempt as failed if it doesn't start with a
            // curly bracket.
            throw Exception("Failed to descramble")
        }
        val decoded = Base64.decode(unscrambledData, Base64.DEFAULT).toString(Charsets.UTF_8)

        val data = json.parseToJsonElement(decoded).jsonObject

        preferences.edit().putString(LAST_WORKING_KEYS_PREF, "${keys.first},${keys.second}").apply()
        Log.d("japscan", "Found working key $keys. Storing it in cache.")

        return data["imagesLink"]!!.jsonArray
            .filterNot { it.jsonPrimitive.content.toHttpUrl().host == baseUrl.toHttpUrl().host } // Pages not served through their CDN are probably ads
            .mapIndexed { i, it ->
                Page(i, imageUrl = it.jsonPrimitive.content)
            }
    }

    private fun getCustomKeys(): List<Pair<String, String>> {
        val customKeys = customKeysPref().replace(" ", "")
        return if (customKeys.isNotBlank()) {
            listOf(
                Pair(customKeys.split(',')[0], customKeys.split(',')[1]),
                Pair(customKeys.split(',')[1], customKeys.split(',')[0])
            )
        } else {
            emptyList()
        }
    }

    private fun getZjsKeys(document: Document): List<Pair<String, String>> {
        val zjsurl = document.getElementsByTag("script").first {
            it.attr("src").contains("zjs", ignoreCase = true)
        }.attr("src")
        Log.d("japscan", "ZJS at $zjsurl")

        val obfuscatedZjs = client.newCall(GET(baseUrl + zjsurl, headers)).execute().body?.string()
            ?: throw Exception("Impossible de récupérer le ZJS")
        val zjs = Deobfuscator.deobfuscateScript(obfuscatedZjs) ?: throw Exception("Impossible à désobfusquer ZJS")

        val stringLookupTables = decodingStringsRe.findAll(zjs).mapNotNull { match ->
            match.groupValues[1].takeIf {
                it.toCharArray().sorted() == sortedLookupString
            }
        }.distinct().toList()

        if (stringLookupTables.size != 2) {
            throw Exception("Attendait 2 chaînes de recherche dans ZJS, a trouvé ${stringLookupTables.size}")
        }

        return listOf(
            stringLookupTables[0] to stringLookupTables[1],
            stringLookupTables[1] to stringLookupTables[0]
        )
    }

    override fun imageUrlParse(document: Document): String = ""

    // Filters
    private class TextField(name: String) : Filter.Text(name)

    private class PageList(pages: Array<Int>) : Filter.Select<Int>("Page #", arrayOf(0, *pages))

    override fun getFilterList(): FilterList {
        val totalPages = pageNumberDoc?.select("li.page-item:last-child a")?.text()
        val pagelist = mutableListOf<Int>()
        return if (!totalPages.isNullOrEmpty()) {
            for (i in 0 until totalPages.toInt()) {
                pagelist.add(i + 1)
            }
            FilterList(
                Filter.Header("Page alphabétique"),
                PageList(pagelist.toTypedArray())
            )
        } else {
            FilterList(
                Filter.Header("Page alphabétique"),
                TextField("Page #"),
                Filter.Header("Appuyez sur reset pour la liste")
            )
        }
    }

    private var pageNumberDoc: Document? = null

    // Prefs
    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val keyPref = EditTextPreference(screen.context).apply {
            key = CUSTOM_DECRYPT_KEYS
            title = CUSTOM_DECRYPT_KEYS_Title
            summary = customKeysPref().ifBlank { CUSTOM_DECRYPT_KEYS_Summary }
            dialogMessage = CUSTOM_DECRYPT_KEYS_Summary

            setOnPreferenceChangeListener { _, newValue ->
                newValue as String
                try {
                    if (newValue.isNotBlank() && newValue.split(',').size != 2) throw Exception()
                    val formattedValue = newValue.trim().ifBlank { "" }
                    preferences.edit().putString(CUSTOM_DECRYPT_KEYS, formattedValue).apply()
                    summary = formattedValue.ifBlank { CUSTOM_DECRYPT_KEYS_Summary }
                    text = formattedValue
                    false
                } catch (e: Throwable) {
                    Toast.makeText(screen.context, CUSTOM_DECRYPT_FORMAT, Toast.LENGTH_LONG).show()
                    false
                }
            }
        }
        screen.addPreference(keyPref)

        val chapterListPref = SwitchPreferenceCompat(screen.context).apply {
            key = ONLY_FRENCH_CHAPTERS
            title = ONLY_FRENCH_CHAPTERS_Title
            summary = ONLY_FRENCH_CHAPTERS_Summary
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean
                preferences.edit()
                    .putBoolean(ONLY_FRENCH_CHAPTERS, checkValue)
                    .commit()
            }
        }
        screen.addPreference(chapterListPref)
    }
}
