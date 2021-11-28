package eu.kanade.tachiyomi.extension.en.luminousscansunofficial

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import rx.Single
import java.text.SimpleDateFormat
import java.util.Locale

abstract class WPMangaReader(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    val mangaUrlDirectory: String = "/manga",
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.US)
) : ParsedHttpSource() {

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    // popular
    override fun popularMangaRequest(page: Int) = searchMangaRequest(page, "", FilterList(OrderByFilter(5)))
    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    override fun popularMangaFromElement(element: Element) = throw UnsupportedOperationException("Not used")
    override fun popularMangaSelector() = throw UnsupportedOperationException("Not used")
    override fun popularMangaNextPageSelector() = throw UnsupportedOperationException("Not used")

    // latest
    override fun latestUpdatesRequest(page: Int) = searchMangaRequest(page, "", FilterList(OrderByFilter(3)))
    override fun latestUpdatesParse(response: Response) = searchMangaParse(response)

    override fun latestUpdatesSelector() = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException("Not used")

    // search
    override fun searchMangaSelector() = ".utao .uta .imgu, .listupd .bs .bsx, .listo .bs .bsx"

    /**
     * Given some string which represents an http url, returns a identifier (id) for a manga
     * which can be used to fetch its details at "$baseUrl$mangaUrlDirectory/$id"
     *
     * @param s: String - url
     *
     * @returns An identifier for a manga, or null if none could be found
     */
    protected open fun mangaIdFromUrl(s: String): Single<String?> {
        val baseMangaUrl = "$baseUrl$mangaUrlDirectory".toHttpUrlOrNull()!!
        return s.toHttpUrlOrNull()?.let { url ->
            fun pathLengthIs(url: HttpUrl, n: Int, strict: Boolean = false) = url.pathSegments.size == n && url.pathSegments[n - 1].isNotEmpty() || (!strict && url.pathSegments.size == n + 1 && url.pathSegments[n].isEmpty())
            val isMangaUrl = listOf(
                baseMangaUrl.host == url.host,
                pathLengthIs(url, 2),
                url.pathSegments[0] == baseMangaUrl.pathSegments[0]
            ).all { it }
            val potentiallyChapterUrl = pathLengthIs(url, 1)
            if (isMangaUrl)
                Single.just(url.pathSegments[1])
            else if (potentiallyChapterUrl)
                client.newCall(GET(s, headers)).asObservableSuccess().map {
                    val links = it.asJsoup().select("a[itemprop=item]")
                    if (links.size == 3) //  near the top of page: home > manga > current chapter
                        links[1].attr("href").toHttpUrlOrNull()?.pathSegments?.get(1)
                    else
                        null
                }.toSingle()
            else
                Single.just(null)
        } ?: Single.just(null)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (!query.startsWith(URL_SEARCH_PREFIX))
            return super.fetchSearchManga(page, query, filters)

        return mangaIdFromUrl(query.substringAfter(URL_SEARCH_PREFIX))
            .toObservable()
            .concatMap { id ->
                if (id == null)
                    Observable.just(MangasPage(emptyList(), false))
                else
                    fetchMangaDetails(SManga.create().apply { this.url = "$mangaUrlDirectory/$id" })
                        .map {
                            it.url = "$mangaUrlDirectory/$id" // isn't set in returned manga
                            MangasPage(listOf(it), false)
                        }
            }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var url = "$baseUrl".toHttpUrlOrNull()!!.newBuilder()
        if (query.isNotEmpty()) {
            url.addPathSegments("page/$page").addQueryParameter("s", query)
        } else {
            url.addPathSegment(mangaUrlDirectory.substring(1)).addQueryParameter("page", "$page")
            filters.forEach { filter ->
                when (filter) {
                    is UrlEncoded -> filter.encode(url)
                    // if site has project page, default value "hasProjectPage" = false
                    is ProjectFilter -> {
                        if (filter.toUriPart() == "project-filter-on") {
                            url = "$baseUrl$projectPageString/page/$page".toHttpUrlOrNull()!!.newBuilder()
                        }
                    }
                }
            }
        }
        return GET("$url")
    }

    open val projectPageString = "/project"

    override fun searchMangaParse(response: Response): MangasPage {
        if (genrelist == null)
            genrelist = parseGenres(response.asJsoup(response.peekBody(Long.MAX_VALUE).string()))
        return super.searchMangaParse(response)
    }

    private fun parseGenres(document: Document): List<LabeledValue>? {
        return document.selectFirst("ul.c4")?.select("li")?.map { li ->
            LabeledValue(li.selectFirst("label").text(), li.selectFirst("input[type=checkbox]").`val`())
        }
    }

    private fun toPermanentUrl(manga: SManga) {
        val regex = "/(\\d*)-".toRegex()
        manga.url = manga.url.replace(regex, "/")
    }

    override fun searchMangaFromElement(element: Element) = SManga.create().apply {
        thumbnail_url = element.select("img").attr("abs:src")
        title = element.select("a").attr("title")
        setUrlWithoutDomain(element.select("a").attr("href"))
        toPermanentUrl(this)
    }

    override fun searchMangaNextPageSelector() = "div.pagination .next, div.hpage .r"

    // manga details
    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        author = document.select(".listinfo li:contains(Author), .tsinfo .imptdt:nth-child(4) i, .infotable tr:contains(author) td:last-child")
            .firstOrNull()?.ownText()

        artist = document.select(".infotable tr:contains(artist) td:last-child, .tsinfo .imptdt:contains(artist) i")
            .firstOrNull()?.ownText()

        genre = document.select("div.gnr a, .mgen a, .seriestugenre a").joinToString { it.text() }
        status = parseStatus(
            document.select("div.listinfo li:contains(Status), .tsinfo .imptdt:contains(status), .infotable tr:contains(status) td")
                .text()
        )

        title = document.selectFirst("h1.entry-title").text()
        thumbnail_url = document.select(".infomanga > div[itemprop=image] img, .thumb img").attr("abs:src")
        description = document.select(".desc, .entry-content[itemprop=description]").joinToString("\n") { it.text() }

        // add series type(manga/manhwa/manhua/other) thinggy to genre
        document.select(seriesTypeSelector).firstOrNull()?.ownText()?.let {
            if (it.isEmpty().not() && genre!!.contains(it, true).not()) {
                genre += if (genre!!.isEmpty()) it else ", $it"
            }
        }

        // add alternative name to manga description
        document.select(altNameSelector).firstOrNull()?.ownText()?.let {
            if (it.isEmpty().not()) {
                description += when {
                    description!!.isEmpty() -> altName + it
                    else -> "\n\n$altName" + it
                }
            }
        }
    }

    open val seriesTypeSelector = "span:contains(Type) a, .imptdt:contains(Type) a, a[href*=type\\=], .infotable tr:contains(Type) td:last-child"
    open val altNameSelector = ".alternative, .seriestualt"
    open val altName = "Alternative Name" + ": "

    open fun parseStatus(status: String) = when {
        status.contains("Ongoing") -> SManga.ONGOING
        status.contains("Completed") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // chapters
    override fun chapterListSelector() = "div.bxcl li, #chapterlist li .eph-num a"

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = document.select(chapterListSelector()).map { chapterFromElement(it) }

        // Add timestamp to latest chapter, taken from "Updated On". so source which not provide chapter timestamp will have atleast one
        val date = document.select(".listinfo time[itemprop=dateModified]").attr("datetime")
        val checkChapter = document.select(chapterListSelector()).firstOrNull()
        if (date != "" && checkChapter != null) chapters[0].date_upload = parseDate(date)

        return chapters
    }

    private fun parseChapterDate(date: String): Long {
        return try {
            dateFormat.parse(date)?.time ?: 0
        } catch (_: Exception) {
            0L
        }
    }

    private fun parseDate(date: String): Long {
        return SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(date)?.time ?: 0L
    }

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.select("a").attr("href").substringAfter(baseUrl))
        name = element.select(".lch a, .chapternum").text()
        date_upload = element.select(".chapterdate").firstOrNull()?.text()?.let { parseChapterDate(it) } ?: 0
    }

    // pages
    open val pageSelector = "div#readerarea img"

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        document.select(pageSelector)
            .filterNot { it.attr("abs:src").isNullOrEmpty() }
            .mapIndexed { i, img -> pages.add(Page(i, "", img.attr("abs:src"))) }

        // Some sites like mangakita now load pages via javascript
        if (pages.isNotEmpty()) { return pages }

        val docString = document.toString()
        val imageListRegex = Regex("\\\"images.*?:.*?(\\[.*?\\])")
        val imageListJson = imageListRegex.find(docString)!!.destructured.toList()[0]

        val imageList = JSONArray(imageListJson)

        for (i in 0 until imageList.length()) {
            pages.add(Page(i, "", imageList.getString(i)))
        }

        return pages
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not Used")

    private interface UrlEncoded {
        fun encode(url: HttpUrl.Builder)
    }

    // essentially a named pair
    protected class LabeledValue(val displayname: String, val _value: String?) {
        val value: String get() = _value ?: displayname
        override fun toString(): String = displayname
    }

    private open class Select<T>(header: String, values: Array<T>, state: Int = 0) : Filter.Select<T>(header, values, state) {
        val selected: T
            get() = this.values[this.state]
    }

    private open class MultiSelect<T>(header: String, val elems: List<T>) :
        Filter.Group<Filter.CheckBox>(header, elems.map { object : Filter.CheckBox("$it") {} }) {
        val selected: Sequence<T>
            get() = this.elems.asSequence().filterIndexed { i, _ -> this.state[i].state }
    }

    open val hasProjectPage = false

    // filters
    override fun getFilterList(): FilterList {
        val filters = mutableListOf<Filter<*>>(
            Filter.Header("NOTE: Ignored if using text search!"),
            GenreFilter(),
            StatusFilter(),
            TypesFilter(),
            OrderByFilter(),
        )
        if (hasProjectPage) {
            filters.addAll(
                mutableListOf<Filter<*>>(
                    Filter.Separator(),
                    Filter.Header("NOTE: cant be used with other filter!"),
                    Filter.Header("$name Project List page"),
                    ProjectFilter(),
                )
            )
        }
        return FilterList(filters)
    }

    protected class ProjectFilter : UriPartFilter(
        "Filter Project",
        arrayOf(
            Pair("Show all manga", ""),
            Pair("Show only project manga", "project-filter-on")
        )
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private fun GenreFilter() = object : MultiSelect<LabeledValue>("Genre", getGenreList()), UrlEncoded {
        override fun encode(url: HttpUrl.Builder) {
            selected.forEach { url.addQueryParameter("genre[]", it.value) }
        }
    }

    private fun StatusFilter() = object : Select<LabeledValue>("Status", getPublicationStatus()), UrlEncoded {
        override fun encode(url: HttpUrl.Builder) {
            url.addQueryParameter("status", selected.value)
        }
    }

    private fun TypesFilter() = object : Select<LabeledValue>("Type", getContentType()), UrlEncoded {
        override fun encode(url: HttpUrl.Builder) {
            url.addQueryParameter("type", selected.value)
        }
    }

    private fun OrderByFilter(state: Int = 0) = object : Select<LabeledValue>("Order By", getOrderBy(), state), UrlEncoded {
        override fun encode(url: HttpUrl.Builder) {
            url.addQueryParameter("order", selected.value)
        }
    }

    // overridable
    // some sources have numeric values for filters
    private var genrelist: List<LabeledValue>? = null
    protected open fun getGenreList(): List<LabeledValue> {
        // Filters are fetched immediately once an extension loads
        // We're only able to get filters after a loading the manga directory, and resetting
        // the filters is the only thing that seems to reinflate the view
        return genrelist ?: listOf(LabeledValue("Press reset to attempt to fetch genres", ""))
    }

    private fun getPublicationStatus() = arrayOf(
        LabeledValue("All", ""),
        LabeledValue("Ongoing", "ongoing"),
        LabeledValue("Completed", "completed"),
        LabeledValue("Hiatus", "hiatus")
    )

    private fun getContentType() = arrayOf(
        LabeledValue("All", ""),
        LabeledValue("Manga", "manga"),
        LabeledValue("Manhwa", "manhwa"),
        LabeledValue("Manhua", "manhua"),
        LabeledValue("Comic", "comic")
    )

    private fun getOrderBy() = arrayOf(
        LabeledValue("Default", ""),
        LabeledValue("A-Z", "title"),
        LabeledValue("Z-A", "titlereverse"),
        LabeledValue("Update", "update"),
        LabeledValue("Added", "latest"),
        LabeledValue("Popular", "popular")
    )

    companion object {
        const val URL_SEARCH_PREFIX = "url:"
    }
}
