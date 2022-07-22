package eu.kanade.tachiyomi.extension.en.leviatanscansunofficial

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class LeviatanScansFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        LeviatanScans(),
    )
}

class LeviatanScans : Madara(
    "Leviatan Scans Unofficial",
    "https://leviatanscans.com",
    "en",
    SimpleDateFormat("MMM, yy", Locale.US)
) {
    override val useNewChapterEndpoint: Boolean = true
    override val mangaDetailsSelectorStatus = ".post-content_item:contains(Status) .summary-content"

    override fun popularMangaFromElement(element: Element): SManga {
        return super.popularMangaFromElement(element).apply { toPermanentUrl(this) }
    }

    override fun searchMangaFromElement(element: Element): SManga {
        return super.searchMangaFromElement(element).apply { toPermanentUrl(this) }
    }

    private fun toPermanentUrl(manga: SManga) {
        val regex = "/(.*)/manga".toRegex()
        manga.url = manga.url.replace(regex, "/manga")
    }
}
