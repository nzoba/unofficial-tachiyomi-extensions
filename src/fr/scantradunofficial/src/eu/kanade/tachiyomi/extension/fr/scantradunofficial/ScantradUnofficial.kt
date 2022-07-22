package eu.kanade.tachiyomi.extension.fr.scantradunofficial

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.HashSet
import java.util.Locale
import java.util.concurrent.TimeUnit

class ScantradUnofficial : ParsedHttpSource() {

    override val name = "Scantrad Unofficial"

    override val baseUrl = "https://scantrad.net"

    override val lang = "fr"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .rateLimit(1)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10.0; SM-G930VC Build/NRD90M) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/83.0.3029.83 Mobile Safari/537.36")
        .add("Accept-Language", "fr")

    // Popular

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/mangas", headers)
    }

    override fun popularMangaSelector() = "div.manga"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        with(element) {
            select("a.mri-top").let {
                manga.setUrlWithoutDomain(it.attr("href"))
                manga.title = it.text()
            }

            manga.thumbnail_url = select("div.manga_img img").attr("abs:data-src")
        }

        return manga
    }

    override fun popularMangaNextPageSelector() = "Not needed"

    // Latest

    override fun latestUpdatesRequest(page: Int): Request {
        if (page == 1) latestUpdatesUrls.clear()

        return GET("$baseUrl/?page=$page", headers)
    }

    private val dedupeLatestUpdates = true

    private val latestUpdatesUrls = HashSet<String>()

    override fun latestUpdatesParse(response: Response): MangasPage {
        val mp = super.latestUpdatesParse(response)
        return if (dedupeLatestUpdates) {
            val mangas = mp.mangas.distinctBy { it.url }.filterNot { latestUpdatesUrls.contains(it.url) }
            latestUpdatesUrls.addAll(mangas.map { it.url })
            MangasPage(mangas, mp.hasNextPage)
        } else mp
    }

    override fun latestUpdatesSelector() = "#home-chapter div.home-manga"

    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()

        manga.setUrlWithoutDomain(element.select("div.hm-info a.hmi-titre").attr("abs:href"))
        manga.title = element.select("a.hmi-titre span").text()
        manga.thumbnail_url = element.select("a.hm-image img").attr("abs:data-src")

        return manga
    }

    override fun latestUpdatesNextPageSelector() = "a#pageNext"

    // Search

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return client.newCall(searchMangaRequest(page, query, filters))
            .asObservableSuccess()
            .map { response ->
                searchMangaParse(response, query)
            }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = popularMangaRequest(1)

    private fun searchMangaParse(response: Response, query: String): MangasPage {
        return MangasPage(popularMangaParse(response).mangas.filter { it.title.contains(query, ignoreCase = true) }, false)
    }

    override fun searchMangaSelector() = throw UnsupportedOperationException("Not used")

    override fun searchMangaFromElement(element: Element): SManga = throw UnsupportedOperationException("Not used")

    override fun searchMangaNextPageSelector() = throw UnsupportedOperationException("Not used")

    // Details

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        document.select("div.mf-chapitre").let {
            manga.author = it.select("div.titre div").text().substringAfter("de").trim()
            //      manga.title = it.select("div.titre").text().removeSuffix(manga.author.orEmpty())
            manga.description = it.select("div.new-main p").text()
            manga.thumbnail_url = it.select("div.ctt-img img").attr("abs:src")
            manga.status = parseStatus(it.select("div.sub-i").text())
            val genres = it.select("div.sub-i").text().substringBefore("Status").substringAfter("Genre :")
            manga.genre = genres.trim().replace(" ", ", ")
        }

        return manga
    }

    private fun parseStatus(status: String) = when {
        status.contains("en cours", ignoreCase = true) -> SManga.ONGOING
        status.contains("terminé", ignoreCase = true) -> SManga.COMPLETED
        status.contains("arrêté", ignoreCase = true) -> SManga.CANCELLED
        status.contains("en pause", ignoreCase = true) -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    // Chapters

    override fun chapterListSelector() = "div.chapitre"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()

        chapter.setUrlWithoutDomain(element.select("a.ch-left").attr("href"))
        chapter.name = element.select("span.chl-num").text()
        chapter.date_upload = parseChapterDate(element.select("div.chl-date").text())

        return chapter
    }

    private fun parseChapterDate(date: String): Long {
        val value = date.split(" ")[0].toIntOrNull()

        return if (value != null) {
            when (date.split(" ")[1]) {
                "minute", "minutes" -> Calendar.getInstance().apply {
                    add(Calendar.MINUTE, value * -1)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                "heure", "heures" -> Calendar.getInstance().apply {
                    add(Calendar.HOUR_OF_DAY, value * -1)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                "jour", "jours" -> Calendar.getInstance().apply {
                    add(Calendar.DATE, value * -1)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                "semaine", "semaines" -> Calendar.getInstance().apply {
                    add(Calendar.DATE, value * 7 * -1)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                "mois" -> Calendar.getInstance().apply {
                    add(Calendar.MONTH, value * -1)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                "an", "ans", "année", "années" -> Calendar.getInstance().apply {
                    add(Calendar.YEAR, value * -1)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                else -> {
                    return 0L
                }
            }
        } else {
            try {
                SimpleDateFormat("dd MMM yyyy", Locale.FRENCH).parse(date.substringAfter("le "))?.time ?: 0
            } catch (_: Exception) {
                0L
            }
        }
    }

    // Pages

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!, headersBuilder().set("Referer", page.url).build())
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()

        document.select("div.sc-lel img[id]").forEachIndexed { i, img ->
            if (img.attr("data-src").startsWith("lel", true)) {
                pages.add(Page(i, "$baseUrl/", "https://scan-trad.fr/" + img.attr("data-src")))
            } else {
                pages.add(Page(i, "", img.attr("abs:data-src")))
            }
        }

        return pages
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException("Not used")

    override fun getFilterList() = FilterList()
}
