package eu.kanade.tachiyomi.animeextension.es.jkanime

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

class Jkanime : AnimeHttpSource() {

    override val baseUrl = "https://jkanime.net"
    override val name = "Jkanime"
    override val lang = "es"
    override val supportsLatest = true

    override fun getFilterList() = AnimeFilterList()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "es-ES,es;q=0.9")

    // --------------------------------- Popular ---------------------------------

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/directorio?filtro=popularidad&p=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseCatalogue(response)

    // ---------------------------------- Latest ----------------------------------

    override fun latestUpdatesRequest(page: Int): Request =
        if (page == 1) GET(baseUrl, headers) else GET("$baseUrl/directorio?p=${page - 1}", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        if (response.request.url.encodedPath.startsWith("/directorio")) {
            return parseCatalogue(response)
        }

        val document = response.asJsoup()
        val animes = document.select("div.trending_div div.custom_thumb_home a").mapNotNull { element ->
            val image = element.selectFirst("img") ?: return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = image.attr("alt").ifBlank { element.attr("title") }
                thumbnail_url = image.attr("abs:src")
            }
        }
        return AnimesPage(animes, animes.isNotEmpty())
    }

    // ---------------------------------- Search ----------------------------------

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/directorio".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("p", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseCatalogue(response)

    private fun parseCatalogue(response: Response): AnimesPage {
        val document = response.asJsoup()

        val jsonText = document.selectFirst("script:containsData(var animes)")
            ?.data()
            ?.let { ANIMES_REGEX.find(it)?.groupValues?.get(1) }

        if (!jsonText.isNullOrBlank()) {
            val root = runCatching { JSONObject(jsonText) }.getOrNull()
            if (root != null) {
                val data = root.optJSONArray("data") ?: JSONArray()
                val animes = (0 until data.length()).mapNotNull { index ->
                    val item = data.optJSONObject(index) ?: return@mapNotNull null
                    SAnime.create().apply {
                        title = item.optString("title")
                        setUrlWithoutDomain(item.optString("url"))
                        thumbnail_url = item.optString("image").ifBlank { null }
                        description = item.optString("synopsis").ifBlank { null }
                        author = item.optString("studios").ifBlank { null }
                        status = parseStatus(item.optString("estado") + " " + item.optString("status"))
                    }
                }
                val next = root.optString("next_page_url")
                return AnimesPage(animes, next.isNotBlank() && next != "null")
            }
        }

        val animes = document.select("div.anime__item").mapNotNull { element ->
            val link = element.selectFirst("a[href]") ?: return@mapNotNull null
            val title = element.selectFirst("h5 a")?.text()
                ?: link.attr("title").ifBlank { null }
                ?: return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(link.attr("abs:href"))
                this.title = title
                thumbnail_url = element.selectFirst("div[data-setbg]")?.attr("abs:data-setbg")
            }
        }
        val hasNext = document.selectFirst("ul.pagination li.active + li a") != null
        return AnimesPage(animes, hasNext)
    }

    // ---------------------------------- Details ---------------------------------

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst("div.anime_info h3")?.text()
                ?: document.selectFirst("h3")?.text().orEmpty()
            description = document.selectFirst("div.anime_info p.scroll")?.text()
            thumbnail_url = document.selectFirst("div.anime_info img")?.attr("abs:src")

            document.select("div.anime_data li").forEach { item ->
                val label = item.selectFirst("span")?.text().orEmpty()
                when {
                    label.contains("Gener", true) ->
                        genre = item.select("a").joinToString(", ") { it.text() }

                    label.contains("Estado", true) ->
                        status = parseStatus(item.selectFirst("div")?.text() ?: item.text())

                    label.contains("Studio", true) || label.contains("Estudio", true) ->
                        author = item.select("a").joinToString(", ") { it.text() }
                }
            }
        }
    }

    // --------------------------------- Episodes ---------------------------------

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val animeUrl = response.request.url.toString().trimEnd('/')

        val token = document.selectFirst("meta[name=csrf-token]")?.attr("content")
            ?.takeIf { it.isNotBlank() } ?: return emptyList()
        val animeId = document.selectFirst("[data-anime]")?.attr("data-anime")
            ?.takeIf { it.isNotBlank() } ?: return emptyList()

        val cookies = response.headers.values("set-cookie")
            .map { it.substringBefore(";") }
            .joinToString("; ")
        val requestHeaders = if (cookies.isBlank()) {
            headers
        } else {
            headers.newBuilder().add("Cookie", cookies).build()
        }

        val form = FormBody.Builder().add("_token", token).build()
        val episodes = mutableListOf<SEpisode>()

        var page = 1
        var lastPage = 1
        do {
            val body = runCatching {
                client.newCall(POST("$baseUrl/ajax/episodes/$animeId/$page", requestHeaders, form))
                    .execute()
                    .use { it.body?.string().orEmpty() }
            }.getOrNull() ?: break

            val json = runCatching { JSONObject(body) }.getOrNull() ?: break
            lastPage = json.optInt("last_page", 1)
            val data = json.optJSONArray("data") ?: break

            for (index in 0 until data.length()) {
                val episode = data.optJSONObject(index) ?: continue
                val number = episode.optString("number")
                episodes.add(
                    SEpisode.create().apply {
                        name = episode.optString("title").ifBlank { "Episodio $number" }
                        episode_number = number.toFloatOrNull() ?: 0f
                        setUrlWithoutDomain("$animeUrl/$number")
                        date_upload = parseDate(episode.optString("timestamp"))
                        preview_url = previewUrl(episode.optString("image"))
                    },
                )
            }

            page++
            if (page <= lastPage) Thread.sleep(250)
        } while (page <= lastPage)

        return episodes.distinctBy { it.url }.reversed()
    }

    // --------------------------------- Hosters ----------------------------------

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val script = document.selectFirst("script:containsData(var video = [];)")?.data()
            ?: document.selectFirst("script:containsData(var servers)")?.data()
            ?: return emptyList()

        val hosters = mutableListOf<Hoster>()

        SERVER_ARRAY_REGEX.find(script)?.groupValues?.get(1)?.let { arrayText ->
            val array = runCatching { JSONArray(arrayText) }.getOrNull()
            if (array != null) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val remote = item.optString("remote")
                    val url = runCatching {
                        String(Base64.decode(remote, Base64.DEFAULT)).trim()
                    }.getOrNull() ?: continue
                    if (url.isBlank()) continue
                    val language = languageName(item.optString("lang"))
                    val server = item.optString("server").ifBlank { "Servidor" }
                    hosters.add(
                        Hoster(absoluteUrl(url), "$language $server".trim(), internalData = server),
                    )
                }
            }
        }

        val buttons = document.select("div.bg-servers a").associate { button ->
            button.attr("data-id") to (
                button.text().trim().ifBlank { "Servidor" } to
                    languageName(button.attr("class").substringAfter("lg_", "").substringBefore(" ").trim())
                )
        }

        IFRAME_VIDEO_REGEX.findAll(script).forEach { match ->
            val id = match.groupValues[1]
            val url = match.groupValues[2].trim()
            val (name, language) = buttons[id] ?: ("Servidor" to "")
            if (url.isNotBlank()) {
                hosters.add(Hoster(absoluteUrl(url), "$language $name".trim(), internalData = name))
            }
        }

        return hosters.distinctBy { it.hosterUrl }
    }

    // ---------------------------------- Videos ----------------------------------

    override suspend fun getVideoList(hoster: Hoster): List<Video> =
        resolveVideo(hoster.hosterUrl, hoster.hosterName, 0)

    private fun resolveVideo(url: String, title: String, depth: Int): List<Video> {
        if (url.isBlank() || depth > 2) return emptyList()
        if (url.contains(".m3u8")) return listOf(Video(videoUrl = url, videoTitle = "$title HLS"))
        if (url.substringBefore('?').endsWith(".mp4")) return listOf(Video(videoUrl = url, videoTitle = title))

        val html = runCatching {
            client.newCall(GET(url, headers)).execute().use { it.body?.string().orEmpty() }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return emptyList()

        M3U8_URL_REGEX.find(html)?.let {
            return listOf(Video(videoUrl = it.groupValues[1], videoTitle = "$title HLS"))
        }

        MP4_URL_REGEX.find(html)?.let {
            return listOf(Video(videoUrl = it.groupValues[1], videoTitle = title))
        }

        // Streamtape
        ROBOT_LINK_REGEX.find(html)?.let {
            val link = it.groupValues[1].trim()
            val full = if (link.startsWith("//")) "https:$link" else link
            return listOf(Video(videoUrl = full, videoTitle = title))
        }

        // jkplayer wrappers sometimes embed the real host inside an iframe.
        val iframe = runCatching { org.jsoup.Jsoup.parse(html).selectFirst("iframe[src]")?.attr("abs:src") }
            .getOrNull()
        if (!iframe.isNullOrBlank() && iframe != url) {
            return resolveVideo(iframe, title, depth + 1)
        }

        return emptyList()
    }

    // ---------------------------------- Helpers ---------------------------------

    private fun parseDate(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(value)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun previewUrl(value: String): String? = when {
        value.isBlank() -> null
        value.startsWith("http") -> value
        else -> "$CDN_URL/assets/images/animes/video/image/$value"
    }

    private fun absoluteUrl(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> baseUrl + url
        else -> url
    }

    private fun languageName(value: String): String = when (value.trim().lowercase()) {
        "1", "jap", "japanese", "[jap]" -> "[JAP]"
        "3", "lat", "latino", "[lat]" -> "[LAT]"
        "4", "chin", "chino", "[chin]" -> "[CHIN]"
        else -> ""
    }

    private fun parseStatus(value: String): Int = when {
        value.contains("emision", true) || value.contains("emisión", true) -> SAnime.ONGOING
        value.contains("currently", true) -> SAnime.ONGOING
        value.contains("conclu", true) || value.contains("finaliz", true) -> SAnime.COMPLETED
        value.contains("finished", true) -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    companion object {
        private const val CDN_URL = "https://cdn.jkdesa.com"

        private val ANIMES_REGEX = Regex(
            """var\s+animes\s*=\s*(\{.*?\})\s*;""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val SERVER_ARRAY_REGEX = Regex(
            """var\s+servers\s*=\s*(\[.*?])\s*;""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val IFRAME_VIDEO_REGEX = Regex(
            """video\[(\d+)]\s*=\s*'<iframe[^>]*src="([^"]+)"""",
        )
        private val M3U8_URL_REGEX = Regex("""url:\s*'([^']+\.m3u8[^']*)'""")
        private val MP4_URL_REGEX = Regex("""src:\s*"([^"]+\.mp4[^"]*)"""")
        private val ROBOT_LINK_REGEX = Regex("""id="robotlink"[^>]*>([^<]+)<""")
    }
}
