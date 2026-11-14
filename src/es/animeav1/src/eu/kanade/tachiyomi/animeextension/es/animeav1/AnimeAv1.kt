package eu.kanade.tachiyomi.animeextension.es.animeav1

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response

class AnimeAv1 : AnimeHttpSource() {

    override val baseUrl = "https://animeav1.com"
    override val name = "AnimeAV1"
    override val lang = "es"
    override val supportsLatest = true

    override fun getFilterList() = AnimeFilterList()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "es-ES,es;q=0.9")

    // --------------------------------- Popular ---------------------------------

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/catalogo?order=popular&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseCatalogue(response)

    // ---------------------------------- Latest ----------------------------------

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/catalogo?order=latest_released&page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseCatalogue(response)

    // ---------------------------------- Search ----------------------------------

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/catalogo".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseCatalogue(response)

    private fun parseCatalogue(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("article[class*=\"group/item\"]").mapNotNull { element ->
            val link = element.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.attr("abs:href")
            if (href.isBlank()) return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = element.selectFirst("header h3")?.text().orEmpty()
                thumbnail_url = element.selectFirst("img.object-cover")?.attr("abs:src")
                    ?: element.selectFirst("figure img")?.attr("abs:src")
            }
        }

        val hasNext = run {
            val script = document.select("script").joinToString("\n") { it.data() }
            val current = Regex("""currentPage:(\d+)""").find(script)?.groupValues?.get(1)?.toIntOrNull()
            val total = Regex("""totalPages:(\d+)""").find(script)?.groupValues?.get(1)?.toIntOrNull()
            if (current != null && total != null) {
                current < total
            } else {
                document.selectFirst("a[rel=next]") != null ||
                    document.selectFirst("a[aria-label*=\"iguiente\"]") != null
            }
        }

        return AnimesPage(animes, hasNext)
    }

    // ---------------------------------- Details ---------------------------------

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst("h1.line-clamp-2")?.text()
                ?: document.selectFirst("h1")?.text().orEmpty()
            description = document.selectFirst("div.entry p")?.text()
                ?: document.selectFirst("meta[name=description]")?.attr("content")
            thumbnail_url = document.selectFirst("img.object-cover")?.attr("abs:src")
            genre = document.select("header .items-center a")
                .joinToString(", ") { it.text() }
                .takeIf { it.isNotBlank() }

            val statusText = document.select("header .items-center span").joinToString(" ") { it.text() }
            status = when {
                statusText.contains("En emisión", true) || statusText.contains("En emision", true) -> SAnime.ONGOING
                statusText.contains("Finalizado", true) -> SAnime.COMPLETED
                statusText.contains("Próximamente", true) || statusText.contains("Proximamente", true) -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    // --------------------------------- Episodes ---------------------------------

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val script = document.selectFirst("script:containsData(node_ids)")?.data()
            ?: document.select("script").joinToString("\n") { it.data() }
        val detailsUrl = response.request.url.toString().substringBefore("?").substringBefore("#").trimEnd('/')

        val block = EPISODES_REGEX.find(script)?.groupValues?.get(1) ?: return emptyList()
        return EPISODE_REGEX.findAll(block).mapNotNull { match ->
            val number = match.groupValues[2]
            val value = number.toFloatOrNull() ?: return@mapNotNull null
            SEpisode.create().apply {
                name = "Episodio $number"
                episode_number = value
                setUrlWithoutDomain("$detailsUrl/$number")
            }
        }.toList().reversed()
    }

    // --------------------------------- Hosters ----------------------------------

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val script = document.selectFirst("script:containsData(node_ids)")?.data()
            ?: document.select("script").joinToString("\n") { it.data() }

        val embedsBlock = script.substringAfter("embeds:", "").substringBefore("downloads:")
        val downloadsBlock = script.substringAfter("downloads:", "")

        fun servers(block: String, regex: Regex, type: String): List<Triple<String, String, String>> =
            regex.findAll(block)
                .flatMap { SERVER_REGEX.findAll(it.groupValues[1]) }
                .map { match ->
                    val server = match.groupValues[1]
                    val rawUrl = match.groupValues[2]
                    val host = rawUrl.toHttpUrlOrNull()?.host.orEmpty()
                    val url = if (host in MEGA_HOSTS) rawUrl else rawUrl.substringBefore("?embed")
                    Triple(url, server, type)
                }
                .toList()

        val sub = servers(embedsBlock, SUB_REGEX, "SUB") +
            servers(downloadsBlock, SUB_REGEX, "SUB").filter { isMega(it.first) }
        val dub = servers(embedsBlock, DUB_REGEX, "DUB") +
            servers(downloadsBlock, DUB_REGEX, "DUB").filter { isMega(it.first) }

        return (sub + dub)
            .distinctBy { it.first to it.third }
            .map { (url, server, type) ->
                val name = when {
                    url.contains("player.zilla", true) -> "HLS"
                    url.contains("pixeldrain", true) -> "PixelDrain"
                    url.contains("uns.bio", true) || url.contains("upnshare", true) -> "UPNShare"
                    else -> server
                }
                Hoster(
                    hosterUrl = url,
                    hosterName = "$type $name".trim(),
                    internalData = "$type|$server",
                )
            }
    }

    private fun isMega(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host.orEmpty()
        return host in MEGA_HOSTS
    }

    // ---------------------------------- Videos ----------------------------------

    override suspend fun getVideoList(hoster: Hoster): List<Video> =
        resolveVideo(hoster.hosterUrl, hoster.hosterName, 0)

    private fun resolveVideo(url: String, title: String, depth: Int): List<Video> {
        if (url.isBlank() || depth > 2) return emptyList()

        if (url.contains("player.zilla") && url.contains("/play/")) {
            return listOf(Video(videoUrl = url.replace("/play/", "/m3u8/"), videoTitle = "$title HLS"))
        }
        if (url.contains(".m3u8")) {
            return listOf(Video(videoUrl = url, videoTitle = "$title HLS"))
        }
        if (url.substringBefore('?').endsWith(".mp4")) {
            return listOf(Video(videoUrl = url, videoTitle = title))
        }

        if (url.contains("pixeldrain.com/u/")) {
            val id = url.substringAfterLast('/').substringBefore('?')
            if (id.isNotBlank()) {
                return listOf(Video(videoUrl = "https://pixeldrain.com/api/file/$id?download", videoTitle = title))
            }
        }

        val html = runCatching {
            client.newCall(GET(url, headers)).execute().use { it.body?.string().orEmpty() }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return emptyList()

        M3U8_URL_REGEX.find(html)?.let {
            return listOf(Video(videoUrl = it.groupValues[1], videoTitle = "$title HLS"))
        }
        MP4_URL_REGEX.find(html)?.let {
            return listOf(Video(videoUrl = it.groupValues[1], videoTitle = title))
        }
        ROBOT_LINK_REGEX.find(html)?.let {
            val link = it.groupValues[1].trim()
            val full = if (link.startsWith("//")) "https:$link" else link
            return listOf(Video(videoUrl = full, videoTitle = title))
        }

        val iframe = runCatching { org.jsoup.Jsoup.parse(html).selectFirst("iframe[src]")?.attr("abs:src") }
            .getOrNull()
        if (!iframe.isNullOrBlank() && iframe != url) {
            return resolveVideo(iframe, title, depth + 1)
        }

        return emptyList()
    }

    companion object {
        private val MEGA_HOSTS = listOf("mega.nz", "mega.co.nz")

        private val SERVER_REGEX = Regex(
            """\{\s*server\s*:\s*"([^"]*)"\s*,\s*url\s*:\s*"([^"]*)"\s*}""",
        )
        private val SUB_REGEX = Regex("""SUB\s*:\s*\[([^]]*)]""")
        private val DUB_REGEX = Regex("""DUB\s*:\s*\[([^]]*)]""")
        private val EPISODES_REGEX = Regex("""episodes\s*:\s*\[([^]]*)]""")
        private val EPISODE_REGEX = Regex(
            """\{\s*id\s*:\s*([0-9]+(?:\.[0-9]+)?)\s*,\s*number\s*:\s*([0-9]+(?:\.[0-9]+)?)\s*}""",
        )
        private val M3U8_URL_REGEX = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
        private val MP4_URL_REGEX = Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""")
        private val ROBOT_LINK_REGEX = Regex("""id="robotlink"[^>]*>([^<]+)<""")
    }
}
