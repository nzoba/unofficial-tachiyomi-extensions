package eu.kanade.tachiyomi.extension.en.flamescansunofficial

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.Page
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue
import kotlin.random.Random

class FlameScansFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        FlameScans(),
    )
}

class FlameScans : WPMangaReader(
    "Flame Scans Unofficial",
    "https://flamescans.org",
    "en",
    "/series",
) {
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .rateLimit(1, 1, TimeUnit.SECONDS)
        .addInterceptor(::composedImageIntercept)
        .build()

    override val seriesAuthorSelector = "div.tsinfo h1:contains(Author) + i"
    override val seriesArtistSelector = "div.tsinfo h1:contains(Artist) + i"
    override val seriesGenreSelector = "div.gnr a, .mgen a, .seriestugenre a"
    override val seriesStatusSelector = "div.main-info div.status i"
    override val seriesTitleSelector = "h1.entry-title"
    override val seriesThumbnailSelector = ".infomanga > div[itemprop=image] img, .thumb img"
    override val seriesDescriptionSelector = ".desc, .entry-content[itemprop=description] p"
    override val seriesTypeSelector = "div.tsinfo h1:contains(Type) + i"

    private val userAgentRandomizer = " ${Random.nextInt().absoluteValue}"

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:77.0) Gecko/20100101 Firefox/78.0$userAgentRandomizer")

    private val composedSelector: String = "#readerarea div.figure_container div.composed_figure"

    override fun pageListParse(document: Document): List<Page> {
        val hasSplitImages = document
            .select(composedSelector)
            .firstOrNull() != null

        if (!hasSplitImages) {
            return super.pageListParse(document)
        }

        return document.select("#readerarea p:has(img), $composedSelector").toList()
            .filter {
                it.select("img").all { imgEl ->
                    imgEl.attr("abs:src").isNullOrEmpty().not()
                }
            }
            .mapIndexed { i, el ->
                if (el.tagName() == "p") {
                    Page(i, document.location(), el.select("img").attr("abs:src"))
                } else {
                    val imageUrls = el.select("img")
                        .joinToString("|") { it.attr("abs:src") }

                    Page(i, document.location(), imageUrls + COMPOSED_SUFFIX)
                }
            }
    }

    private fun composedImageIntercept(chain: Interceptor.Chain): Response {
        if (!chain.request().url.toString().endsWith(COMPOSED_SUFFIX)) {
            return chain.proceed(chain.request())
        }

        val imageUrls = chain.request().url.toString()
            .removeSuffix(COMPOSED_SUFFIX)
            .split("%7C")

        var width = 0
        var height = 0

        val imageBitmaps = imageUrls.map { imageUrl ->
            val request = chain.request().newBuilder().url(imageUrl).build()
            val response = chain.proceed(request)

            val bitmap = BitmapFactory.decodeStream(response.body!!.byteStream())

            width += bitmap.width
            height = bitmap.height

            bitmap
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        var left = 0

        imageBitmaps.forEach { bitmap ->
            val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
            val dstRect = Rect(left, 0, left + bitmap.width, bitmap.height)

            canvas.drawBitmap(bitmap, srcRect, dstRect, null)

            left += bitmap.width
        }

        val output = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.PNG, 100, output)

        val responseBody = output.toByteArray().toResponseBody(MEDIA_TYPE)

        return Response.Builder()
            .code(200)
            .protocol(Protocol.HTTP_1_1)
            .request(chain.request())
            .message("OK")
            .body(responseBody)
            .build()
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36"

        private const val COMPOSED_SUFFIX = "?comp"
        private val MEDIA_TYPE = "image/png".toMediaType()
    }
}
