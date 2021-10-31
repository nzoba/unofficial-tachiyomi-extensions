package eu.kanade.tachiyomi.extension.en.leviatanscansunofficial

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Element

class MadaraFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        LeviatanScans(),
    )
}

class LeviatanScans : Madara(
    "Leviatan Scans Unofficial",
    "https://leviatanscans.com",
    "en"
) {
    override val useNewChapterEndpoint: Boolean = true

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = super.popularMangaFromElement(element)

        toPermanentUrl(manga)

        return manga
    }

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = super.searchMangaFromElement(element)

        toPermanentUrl(manga)

        return manga
    }

    private fun toPermanentUrl(manga: SManga) {
        val regex = "/(.*)/manga".toRegex()
        manga.url = manga.url.replace(regex, "/manga")
    }
}
