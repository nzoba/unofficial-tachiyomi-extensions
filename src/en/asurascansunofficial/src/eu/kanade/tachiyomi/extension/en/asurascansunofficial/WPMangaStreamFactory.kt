package eu.kanade.tachiyomi.extension.en.asurascansunofficial

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class WPMangaStreamFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        AsuraScans(),
    )
}

class AsuraScans : WPMangaStream("Asura Scans Unofficial", "https://www.asurascans.com", "en") {
    override val pageSelector = "div#readerarea > p > img"
}
