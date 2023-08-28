package eu.kanade.tachiyomi.extension.en.reaperscansunofficial

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ReaperScans : ParsedHttpSource() {

    override val name = "Reaper Scans"

    override val baseUrl = "https://reaperscans.com"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(1, 2, TimeUnit.SECONDS)
        .build()

    // Popular
    override fun popularMangaRequest(page: Int) = GET("$baseUrl/comics?page=$page", headers)

    override fun popularMangaNextPageSelector(): String = "button[wire:click*=nextPage]"

    override fun popularMangaSelector(): String = "li"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            element.select("div > a[href*=/comics/]:nth-child(2)").let {
                title = it.text()
                setUrlWithoutDomain(it.attr("href"))
            }
            thumbnail_url = element.select("img").first()?.imgAttr()
        }
    }

    // Latest
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/latest/comics?page=$page", headers)

    override fun latestUpdatesNextPageSelector(): String = "button[wire:click*=nextPage]"

    override fun latestUpdatesSelector(): String = "div > p > a[href*=/comics/]"

    override fun latestUpdatesFromElement(element: Element): SManga {
        return SManga.create().apply {
            element.let {
                title = it.text().trim()
                setUrlWithoutDomain(it.attr("href"))
            }
            thumbnail_url = element.parent()?.parent()?.parent()?.parent()?.select("img")?.first()?.imgAttr()
        }
    }

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val response = client.newCall(GET(baseUrl)).execute()
        val soup = response.asJsoup()

        val csrfToken = soup.selectFirst("meta[name=csrf-token]")?.attr("content")

        val livewareData = soup.selectFirst("div[wire:initial-data*=comics]")
            ?.attr("wire:initial-data")
            ?.parseJson<LiveWireDataDto>()

        if (csrfToken == null) error("Couldn't find csrf-token")
        if (livewareData == null) error("Couldn't find LiveWireData")

        val routeName = livewareData.fingerprint["name"]?.jsonPrimitive?.contentOrNull
            ?: error("Couldn't find routeName")

        //  Javascript: (Math.random() + 1).toString(36).substring(8)
        val generateId = { "1.${Random.nextLong().toString(36)}".substring(10) } // Not exactly the same, but results in a 3-5 character string
        val payload = buildJsonObject {
            put("fingerprint", livewareData.fingerprint)
            put("serverMemo", livewareData.serverMemo)
            putJsonArray("updates") {
                addJsonObject {
                    put("type", "syncInput")
                    putJsonObject("payload") {
                        put("id", generateId())
                        put("name", "query")
                        put("value", query)
                    }
                }
            }
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val headers = Headers.Builder()
            .add("x-csrf-token", csrfToken)
            .add("x-livewire", "true")
            .build()

        return POST("$baseUrl/livewire/message/$routeName", headers, payload)
    }

    override fun searchMangaSelector(): String = "a[href*=/comics/]"

    override fun searchMangaParse(response: Response): MangasPage {
        val html = response.parseJson<LiveWireResponseDto>().effects.html
        val mangas = Jsoup.parse(html, baseUrl).select(searchMangaSelector()).map { element ->
            searchMangaFromElement(element)
        }
        return MangasPage(mangas, false)
    }

    override fun searchMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            element.select("img").first()?.let {
                thumbnail_url = it.imgAttr()
            }
            title = element.select("p").first()!!.text()
        }
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith(PREFIX_ID_SEARCH)) {
            val realUrl = "/comics/" + query.removePrefix(PREFIX_ID_SEARCH)
            val manga = SManga.create().apply {
                url = realUrl
            }
            return fetchMangaDetails(manga).map {
                MangasPage(listOf(it.apply { url = realUrl }), false)
            }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    // Details
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            thumbnail_url = document.select("div > img").first()!!.imgAttr()
            title = document.select("h1").first()!!.text()

            status = when (document.select("dt:contains(Release Status)").next().first()!!.text()) {
                "On hold" -> SManga.ON_HIATUS
                "Complete" -> SManga.COMPLETED
                "Ongoing" -> SManga.ONGOING
                "Dropped" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }

            genre = mutableListOf<String>().apply {
                when (document.select("dt:contains(Source Language)").next().first()!!.text()) {
                    "Korean" -> "Manhwa"
                    "Chinese" -> "Manhua"
                    "Japanese" -> "Manga"
                    else -> null
                }?.let { add(it) }
            }.takeIf { it.isNotEmpty() }?.joinToString(",")

            description = document.select("section > div:nth-child(1) > div > p").first()!!.text()
        }
    }

    // Chapters
    private fun chapterListNextPageSelector(): String = "button[wire:click*=nextPage]"

    override fun chapterListSelector() = "div[wire:id] > div > ul[role=list] > li"

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()
        document.select(chapterListSelector()).forEach { chapters.add(chapterFromElement(it)) }
        var hasNextPage = document.selectFirst(chapterListNextPageSelector()) != null

        if (!hasNextPage) {
            return chapters
        }

        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content")
            ?: error("Couldn't find csrf-token")

        val livewareData = document.selectFirst("div[wire:initial-data*=Models\\\\Comic]")
            ?.attr("wire:initial-data")
            ?.parseJson<LiveWireDataDto>()
            ?: error("Couldn't find LiveWireData")

        val routeName = livewareData.fingerprint["name"]?.jsonPrimitive?.contentOrNull
            ?: error("Couldn't find routeName")

        val fingerprint = livewareData.fingerprint
        var serverMemo = livewareData.serverMemo

        var pageToQuery = 2

        //  Javascript: (Math.random() + 1).toString(36).substring(8)
        val generateId = { "1.${Random.nextLong().toString(36)}".substring(10) } // Not exactly the same, but results in a 3-5 character string
        while (hasNextPage) {
            val payload = buildJsonObject {
                put("fingerprint", fingerprint)
                put("serverMemo", serverMemo)
                putJsonArray("updates") {
                    addJsonObject {
                        put("type", "callMethod")
                        putJsonObject("payload") {
                            put("id", generateId())
                            put("method", "gotoPage")
                            putJsonArray("params") {
                                add(pageToQuery)
                                add("page")
                            }
                        }
                    }
                }
            }.toString().toRequestBody(JSON_MEDIA_TYPE)

            val headers = Headers.Builder()
                .add("x-csrf-token", csrfToken)
                .add("x-livewire", "true")
                .build()

            val request = POST("$baseUrl/livewire/message/$routeName", headers, payload)

            val responseData = client.newCall(request).execute().parseJson<LiveWireResponseDto>()

            // response contains state that we need to preserve
            serverMemo = serverMemo.mergeLeft(responseData.serverMemo)
            val chaptersHtml = Jsoup.parse(responseData.effects.html, baseUrl)
            chaptersHtml.select(chapterListSelector()).forEach { chapters.add(chapterFromElement(it)) }
            hasNextPage = chaptersHtml.selectFirst(chapterListNextPageSelector()) != null
            pageToQuery++
        }

        return chapters
    }

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            element.selectFirst("a")?.let { urlElement ->
                setUrlWithoutDomain(urlElement.attr("href"))
                urlElement.select("p").let {
                    name = it.getOrNull(0)?.text() ?: ""
                    date_upload = it.getOrNull(1)?.text()?.parseRelativeDate() ?: 0
                }
            }
        }
    }

    val pageListParseSelector = "img[src*=chapters]"

    // Page
    override fun pageListParse(document: Document): List<Page> {
        val pages = document.select(pageListParseSelector)
        if (!pages.isEmpty()) {
            return pages.mapIndexed { index, element ->
                Page(
                    index,
                    document.location(),
                    element.select("img").first()?.let {
                        it.absUrl(if (it.hasAttr("data-src")) "data-src" else "src")
                    }
                )
            }
        } else {
            val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content")

            val livewareData = document.selectFirst("div[wire:initial-data*=display-chapter]")
                ?.attr("wire:initial-data")
                ?.parseJson<LiveWireDataDto>()

            if (csrfToken == null) error("Couldn't find csrf-token")
            if (livewareData == null) error("Couldn't find LiveWireData")

            val routeName = livewareData.fingerprint["name"]?.jsonPrimitive?.contentOrNull
                ?: error("Couldn't find routeName")

            val turnstileName = document.selectFirst("script:containsData(captchacallback)")?.html()
                ?.let { turnstile.find(it)?.groupValues?.get(1) }
                ?: error("Couldn't find Turnstile Name")

            //  Javascript: (Math.random() + 1).toString(36).substring(8)
            val generateId = { "1.${Random.nextLong().toString(36)}".substring(10) } // Not exactly the same, but results in a 3-5 character string
            val payload = buildJsonObject {
                put("fingerprint", livewareData.fingerprint)
                put("serverMemo", livewareData.serverMemo)
                putJsonArray("updates") {
                    addJsonObject {
                        put("type", "callMethod")
                        putJsonObject("payload") {
                            put("id", generateId())
                            put("method", "${"$"}set")
                            putJsonArray("params") {
                                add(turnstileName)
                                add(randomString())
                            }
                        }
                    }
                }
            }.toString().toRequestBody(JSON_MEDIA_TYPE)

            val headers = Headers.Builder()
                .add("x-csrf-token", csrfToken)
                .add("x-livewire", "true")
                .build()

            val liveWireRequest = POST("$baseUrl/livewire/message/$routeName", headers, payload)

            val liveWireResponse = client.newCall(liveWireRequest).execute()

            val html = runCatching { liveWireResponse.parseJson<LiveWireResponseDto>().effects.html }
                .getOrElse {
                    Log.e(name, it.stackTraceToString())
                    error("Fuck you Reaper Scans")
                }

            return Jsoup.parse(html, baseUrl).select("img").mapIndexed { idx, element ->
                Page(idx, imageUrl = element.imgAttr())
            }
        }
    }

    private fun randomString(): String {
        val flags = Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE
        val bytes_336 = Random.nextBytes(ByteArray(336))
        val base64_336 = Base64.encodeToString(bytes_336, flags)

        val bytes_16 = Random.nextBytes(ByteArray(16))
        val base64_16 = Base64.encodeToString(bytes_16, flags)

        val bytes_32 = Random.nextBytes(ByteArray(32))
        val hex32 = bytes_32.joinToString("") { "%02x".format(it) }

        return "0.$base64_336.$base64_16.$hex32"
    }

    // Helpers
    private inline fun <reified T> Response.parseJson(): T = use {
        it.body?.string()?.parseJson() ?: error("Couldn't parse json")
    }

    private inline fun <reified T> String.parseJson(): T = json.decodeFromString(this)

    /**
     * Recursively merges j2 onto j1 in place
     * If j1 and j2 both contain keys whose values aren't both jsonObjects, j2's value overwrites j1's
     *
     */
    private fun JsonObject.mergeLeft(j2: JsonObject): JsonObject = buildJsonObject {
        val j1 = this@mergeLeft
        j1.entries.forEach { (key, value) -> put(key, value) }
        j2.entries.forEach { (key, value) ->
            val j1Value = j1[key]
            when {
                j1Value !is JsonObject -> put(key, value)
                value is JsonObject -> put(key, j1Value.mergeLeft(value))
            }
        }
    }

    /**
     * Parses dates in this form: 21 hours ago
     * Taken from multisrc/madara/Madara.kt
     */
    private fun String.parseRelativeDate(): Long {
        val number = Regex("""(\d+)""").find(this)?.value?.toIntOrNull() ?: return 0
        val cal = Calendar.getInstance()

        return when {
            contains("day") -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
            contains("hour") -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
            contains("minute") -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
            contains("second") -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
            contains("week") -> cal.apply { add(Calendar.DAY_OF_MONTH, -number * 7) }.timeInMillis
            contains("month") -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
            contains("year") -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
            else -> 0
        }
    }

    private fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-cfsrc") -> attr("abs:data-cfsrc")
        else -> attr("abs:src")
    }

    // Unused
    override fun searchMangaNextPageSelector() = throw UnsupportedOperationException("Not Used")

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException("Not Used")

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val PREFIX_ID_SEARCH = "id:"
        private val turnstile by lazy { Regex("""set\s*\(\s*\"([^"]*)""") }
    }
}
