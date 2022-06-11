package eu.kanade.tachiyomi.extension.en.luminousscansunofficial

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.Page
import org.jsoup.nodes.Document

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

    override fun pageListParse(document: Document): List<Page> {
        return document.select(pageSelector).mapIndexed { i, img ->
            Page(i, "", img.attr("abs:src"))
        }
    }
}
