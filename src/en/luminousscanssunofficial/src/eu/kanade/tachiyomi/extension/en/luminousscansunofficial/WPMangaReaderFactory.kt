package eu.kanade.tachiyomi.extension.en.luminousscansunofficial

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.Page
import org.json.JSONArray
import org.jsoup.nodes.Document

class WPMangaReaderFactory : SourceFactory {
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
    override val pageSelector = "div#readerarea div"

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        document.select(pageSelector)
            .filterNot { it.attr("abs:data-background").isNullOrEmpty() }
            .mapIndexed { i, img -> pages.add(Page(i, "", img.attr("abs:data-background"))) }

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
}
