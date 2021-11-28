package eu.kanade.tachiyomi.extension.en.luminousscansunofficial

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

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
)
