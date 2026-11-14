package eu.kanade.tachiyomi.animeextension.es.hentaila

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

class Hentaila : AnimeHttpSource() {

    override val baseUrl = "https://hentaila.com"
    override val name = "HentaiLA"
    override val lang = "es"
    override val supportsLatest = true

    override fun getFilterList() = AnimeFilterList()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "es-ES,es;q=0.9")

    // --------------------------------- Popular ---------------------------------

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/catalogo/__data.json?order=popular&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseCatalogue(response)

    // ---------------------------------- Latest ----------------------------------

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/catalogo/__data.json?order=latest_released&page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseCatalogue(response)

    // ---------------------------------- Search ----------------------------------

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/catalogo/__data.json".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseCatalogue(response)

    private fun parseCatalogue(response: Response): AnimesPage {
        val root = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
            ?: return AnimesPage(emptyList(), false)
        val nodes = root.optJSONArray("nodes") ?: return AnimesPage(emptyList(), false)

        for (nodeIndex in 0 until nodes.length()) {
            val node = nodes.optJSONObject(nodeIndex) ?: continue
            val uses = node.optJSONObject("uses") ?: continue
            if (!uses.has("search_params")) continue

            val data = node.optJSONArray("data") ?: continue
            val first = data.optJSONObject(0) ?: continue

            val results = data.optJSONArray(first.optInt("results", -1)) ?: continue
            val animes = (0 until results.length()).mapNotNull { index ->
                val item = data.optJSONObject(results.optInt(index, -1)) ?: return@mapNotNull null
                val title = data.string(item, "title") ?: return@mapNotNull null
                val slug = data.string(item, "slug") ?: return@mapNotNull null
                val id = data.int(item, "id")
                SAnime.create().apply {
                    this.title = title
                    url = "/media/$slug"
                    description = data.string(item, "synopsis")
                    if (id != null) thumbnail_url = "https://cdn.hentaila.com/covers/$id.jpg"
                }
            }

            val pagination = data.optJSONObject(first.optInt("pagination", -1))
            val currentIndex = pagination?.optInt("currentPage", -1) ?: -1
            val totalIndex = pagination?.optInt("totalPages", -1) ?: -1
            val current = if (currentIndex >= 0) data.optInt(currentIndex, 0) else 0
            val total = if (totalIndex >= 0) data.optInt(totalIndex, 0) else 0
            return AnimesPage(animes, current in 1 until total)
        }

        return AnimesPage(emptyList(), false)
    }

    // ---------------------------------- Details ---------------------------------

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val statusText = document.select("div.flex.flex-wrap.items-center.text-sm span")
            .joinToString(" ") { it.text() }

        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst(".grid.items-start h1.text-lead")?.text()
                ?: document.selectFirst("h1")?.text().orEmpty()
            description = document.select(".entry.text-lead.text-sm p").joinToString(" ") { it.text() }
                .ifBlank { document.selectFirst("meta[name=description]")?.attr("content").orEmpty() }
            thumbnail_url = document.selectFirst("img.aspect-poster")?.attr("abs:src")
                ?: document.selectFirst("img.object-cover")?.attr("abs:src")
            genre = document.select(".flex-wrap.items-center .btn.btn-xs.rounded-full:not(.sm\\:w-auto)")
                .joinToString(", ") { it.text() }
                .takeIf { it.isNotBlank() }
            status = when {
                statusText.contains("En emisión", true) || statusText.contains("En emision", true) -> SAnime.ONGOING
                statusText.isNotBlank() -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // --------------------------------- Episodes ---------------------------------

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val animeId = Regex("""media/([^/?#]+)""")
            .find(response.request.url.toString())
            ?.groupValues?.get(1)
            ?.lowercase()
            .orEmpty()
        if (animeId.isBlank()) return emptyList()

        return document.select("article.group\\/item").mapNotNull { article ->
            val numberText = article.selectFirst("div.bg-line.text-subs span")?.text()
                ?: article.select("span").firstOrNull { it.text().toFloatOrNull() != null }?.text()
                ?: return@mapNotNull null
            val number = numberText.toFloatOrNull() ?: return@mapNotNull null
            SEpisode.create().apply {
                name = "Episodio $numberText"
                episode_number = number
                url = "/media/$animeId/$numberText"
            }
        }.reversed()
    }

    // --------------------------------- Hosters ----------------------------------

    override fun hosterListRequest(episode: SEpisode): Request =
        GET("$baseUrl${episode.url.trimEnd('/')}/__data.json", headers)

    override fun hosterListParse(response: Response): List<Hoster> {
        val root = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
            ?: return emptyList()
        val nodes = root.optJSONArray("nodes") ?: return emptyList()
        val hosters = mutableListOf<Hoster>()

        for (nodeIndex in 0 until nodes.length()) {
            val node = nodes.optJSONObject(nodeIndex) ?: continue
            val uses = node.optJSONObject("uses") ?: continue
            val params = uses.optJSONArray("params") ?: continue
            if ((0 until params.length()).none { params.optString(it) == "number" }) continue

            val data = node.optJSONArray("data") ?: continue
            val first = data.optJSONObject(0) ?: continue
            val embeds = data.optJSONObject(first.optInt("embeds", -1)) ?: continue

            val languages = embeds.keys()
            while (languages.hasNext()) {
                val language = languages.next()
                val languageArray = data.optJSONArray(embeds.optInt(language, -1)) ?: continue
                for (index in 0 until languageArray.length()) {
                    val item = data.optJSONObject(languageArray.optInt(index, -1)) ?: continue
                    val server = data.string(item, "server") ?: continue
                    val url = data.string(item, "url") ?: continue
                    hosters.add(
                        Hoster(
                            hosterUrl = url,
                            hosterName = "$language $server".trim(),
                            internalData = "$language|$server",
                        ),
                    )
                }
            }
        }

        return hosters.distinctBy { it.hosterUrl }
    }

    // ---------------------------------- Videos ----------------------------------

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val server = hoster.internalData.substringAfter("|", "")
        return resolveVideo(hoster.hosterUrl, hoster.hosterName, server, 0)
    }

    private fun resolveVideo(url: String, title: String, server: String, depth: Int): List<Video> {
        if (url.isBlank() || depth > 2) return emptyList()

        if (server.equals("vip", true) && url.contains("/play/")) {
            return listOf(Video(videoUrl = url.replace("/play/", "/m3u8/"), videoTitle = "VIP HLS"))
        }
        if (url.contains(".m3u8")) {
            return listOf(Video(videoUrl = url, videoTitle = "$title HLS"))
        }
        if (url.substringBefore('?').endsWith(".mp4")) {
            return listOf(Video(videoUrl = url, videoTitle = title))
        }
        if (server.equals("arc", true) && url.contains('#')) {
            val direct = url.substringAfter('#')
            if (direct.startsWith("http")) return listOf(Video(videoUrl = direct, videoTitle = "Arc"))
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
            return resolveVideo(iframe, title, server, depth + 1)
        }

        return emptyList()
    }

    // ---------------------------------- Helpers ---------------------------------

    private fun JSONArray.string(item: JSONObject, key: String): String? {
        val index = item.optInt(key, -1)
        val value = if (index >= 0) optString(index, "") else item.optString(key, "")
        return value.ifBlank { null }
    }

    private fun JSONArray.int(item: JSONObject, key: String): Int? {
        val index = item.optInt(key, -1)
        val value = if (index >= 0) opt(index) else null
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    companion object {
        private val M3U8_URL_REGEX = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
        private val MP4_URL_REGEX = Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""")
        private val ROBOT_LINK_REGEX = Regex("""id="robotlink"[^>]*>([^<]+)<""")
    }
}
