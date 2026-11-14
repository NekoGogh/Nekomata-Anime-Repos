package eu.kanade.tachiyomi.animeextension.es.aniyae

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

class Aniyae : AnimeHttpSource() {

    override val baseUrl = "https://open.aniyae.net"
    override val name = "Aniyae"
    override val lang = "es"
    override val supportsLatest = true

    override fun getFilterList() = AnimeFilterList()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "es-ES,es;q=0.9")

    private val apiHeaders
        get() = headers.newBuilder()
            .add("Accept", "application/json")
            .add("Content-Type", "application/json")
            .build()

    // --------------------------------- Popular ---------------------------------

    override fun popularAnimeRequest(page: Int): Request =
        POST("$baseUrl/api/content/titles", apiHeaders, titlesPayload(page))

    override fun popularAnimeParse(response: Response): AnimesPage = parseTitles(response)

    // ---------------------------------- Latest ----------------------------------

    override fun latestUpdatesRequest(page: Int): Request =
        POST("$baseUrl/api/content/titles", apiHeaders, titlesPayload(page))

    override fun latestUpdatesParse(response: Response): AnimesPage = parseTitles(response)

    private fun titlesPayload(page: Int): okhttp3.RequestBody {
        val payload = JSONObject()
            .put("page", page)
            .put("limit", 24)
            .put("sort", "latest")
            .put("order", "DESC")
            .put("nsfw", false)
        return payload.toString().toRequestBody(JSON_MEDIA_TYPE)
    }

    private fun parseTitles(response: Response): AnimesPage {
        val root = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
            ?: return AnimesPage(emptyList(), false)
        val data = root.optJSONArray("data") ?: JSONArray()

        val animes = (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("title").ifBlank { return@mapNotNull null }
            SAnime.create().apply {
                this.title = title
                setUrlWithoutDomain(item.optString("permalink"))
                thumbnail_url = absolute(item.optString("posterUrl"))
                description = item.optString("description")
                    .ifBlank { item.optString("synopsis") }
                    .takeIf { it.isNotBlank() }
                author = item.optJSONArray("studios")
                    ?.let { array -> (0 until array.length()).joinToString(", ") { array.optString(it) } }
                    ?.takeIf { it.isNotBlank() }
                genre = item.optJSONArray("genres")
                    ?.let { array -> (0 until array.length()).joinToString(", ") { array.optString(it) } }
                    ?.takeIf { it.isNotBlank() }
                status = parseStatus(item.optJSONObject("status")?.optString("label").orEmpty())
            }
        }

        val page = root.optInt("page", 1)
        val totalPages = root.optInt("totalPages", 1)
        return AnimesPage(animes, page < totalPages)
    }

    // ---------------------------------- Search ----------------------------------

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val payload = JSONObject()
            .put("q", query)
            .put("page", page)
            .put("limit", 24)
        return POST(
            "$baseUrl/api/content/search",
            apiHeaders,
            payload.toString().toRequestBody(JSON_MEDIA_TYPE),
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseTitles(response)

    // ---------------------------------- Details ---------------------------------

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create().apply {
            url = response.request.url.encodedPath
            title = document.title().substringBefore("【").substringBefore(" | ").trim()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            description = document.selectFirst("meta[property=og:description]")?.attr("content")
        }

        document.select("script[type=application/ld+json]").forEach { script ->
            val json = runCatching { JSONObject(script.data()) }.getOrNull() ?: return@forEach
            if (json.optString("@type") != "TVSeries") return@forEach

            json.optString("name").takeIf { it.isNotBlank() }?.let { anime.title = it }
            json.optString("description").takeIf { it.isNotBlank() }?.let { anime.description = it }
            json.optString("image").takeIf { it.isNotBlank() }?.let { anime.thumbnail_url = it }
            json.optJSONArray("genre")?.let { array ->
                anime.genre = (0 until array.length())
                    .joinToString(", ") { array.optString(it) }
                    .takeIf { it.isNotBlank() }
            }
        }

        val text = document.text()
        anime.status = when {
            text.contains("En emisión", true) || text.contains("En emision", true) -> SAnime.ONGOING
            text.contains("Finalizado", true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        return anime
    }

    // --------------------------------- Episodes ---------------------------------

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    override fun episodeListParse(response: Response): List<SEpisode> = emptyList()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val titleId = fetchTitleId(anime.url) ?: return emptyList()

        val episodes = mutableListOf<SEpisode>()
        var page = 1
        var totalPages = 1
        do {
            val payload = JSONObject().put("page", page).put("limit", 100)
            val body = runCatching {
                client.newCall(
                    POST(
                        "$baseUrl/api/content/titles/$titleId/episodes",
                        apiHeaders,
                        payload.toString().toRequestBody(JSON_MEDIA_TYPE),
                    ),
                ).execute().use { it.body?.string().orEmpty() }
            }.getOrNull() ?: break

            val root = runCatching { JSONObject(body) }.getOrNull() ?: break
            val data = root.optJSONObject("data") ?: break
            totalPages = data.optInt("totalPages", 1)
            val array = data.optJSONArray("data") ?: break

            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val number = item.optString("number")
                episodes.add(
                    SEpisode.create().apply {
                        name = item.optString("title").ifBlank { "Episodio $number" }
                        episode_number = number.toFloatOrNull() ?: 0f
                        setUrlWithoutDomain(item.optString("permalink"))
                        preview_url = absolute(item.optString("thumbnailUrl"))
                    },
                )
            }

            page++
        } while (page <= totalPages)

        // Aniyae returns the oldest episode first; Aniyomi expects the newest first.
        return episodes.distinctBy { it.url }.reversed()
    }

    private fun fetchTitleId(animeUrl: String): Int? {
        if (animeUrl.isBlank()) return null
        val dataUrl = absolute(animeUrl).trimEnd('/') + "/__data.json"
        val body = runCatching {
            client.newCall(GET(dataUrl, headers)).execute().use { it.body?.string().orEmpty() }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val nodes = root.optJSONArray("nodes") ?: return null
        for (nodeIndex in 0 until nodes.length()) {
            val node = nodes.optJSONObject(nodeIndex) ?: continue
            val data = node.optJSONArray("data") ?: continue
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                if (!item.has("code") || !item.has("posterUrl")) continue
                val idIndex = item.optInt("id", -1)
                if (idIndex >= 0) return data.optInt(idIndex, -1).takeIf { it > 0 }
            }
        }
        return null
    }

    // --------------------------------- Hosters ----------------------------------

    override fun hosterListParse(response: Response): List<Hoster> =
        throw UnsupportedOperationException("Hoster list is loaded through the Aniyae API")

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val episodeId = fetchEpisodeId(episode.url) ?: return emptyList()
        val body = runCatching {
            client.newCall(
                GET(
                    "$baseUrl/api/content/episodes/$episodeId/servers",
                    headers.newBuilder().add("Accept", "application/json").build(),
                ),
            ).execute().use { it.body?.string().orEmpty() }
        }.getOrNull() ?: return emptyList()

        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val data = root.optJSONArray("data") ?: return emptyList()

        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val encoded = item.optString("url")
            val url = runCatching {
                String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)).trim()
            }.getOrNull() ?: return@mapNotNull null
            if (url.isBlank()) return@mapNotNull null

            val server = item.optString("server").ifBlank { "Servidor" }
            val type = item.optString("type").ifBlank { "sub" }.uppercase()
            Hoster(
                hosterUrl = url,
                hosterName = "$type $server".trim(),
                internalData = "$type|$server",
            )
        }.distinctBy { it.hosterUrl }
    }

    private fun fetchEpisodeId(episodeUrl: String): Int? {
        if (episodeUrl.isBlank()) return null
        val dataUrl = absolute(episodeUrl).trimEnd('/') + "/__data.json"
        val body = runCatching {
            client.newCall(GET(dataUrl, headers)).execute().use { it.body?.string().orEmpty() }
        }.getOrNull() ?: return null

        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val nodes = root.optJSONArray("nodes") ?: return null
        for (nodeIndex in 0 until nodes.length()) {
            val node = nodes.optJSONObject(nodeIndex) ?: continue
            val data = node.optJSONArray("data") ?: continue
            val first = data.optJSONObject(0) ?: continue
            if (!first.has("episode")) continue
            val episode = data.optJSONObject(first.optInt("episode", -1)) ?: continue
            val idIndex = episode.optInt("id", -1)
            if (idIndex >= 0) return data.optInt(idIndex, -1).takeIf { it > 0 }
        }
        return null
    }

    // ---------------------------------- Videos ----------------------------------

    override suspend fun getVideoList(hoster: Hoster): List<Video> =
        resolveVideo(hoster.hosterUrl, hoster.hosterName, 0)

    private fun resolveVideo(url: String, title: String, depth: Int): List<Video> {
        if (url.isBlank() || depth > 3) return emptyList()

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

        // Voe and similar front-ends may redirect through JavaScript.
        JS_REDIRECT_REGEX.find(html)?.groupValues?.get(1)?.let { redirect ->
            if (redirect.startsWith("http") && redirect != url) {
                return resolveVideo(redirect, title, depth + 1)
            }
        }

        val iframe = runCatching { org.jsoup.Jsoup.parse(html).selectFirst("iframe[src]")?.attr("abs:src") }
            .getOrNull()
        if (!iframe.isNullOrBlank() && iframe != url) {
            return resolveVideo(iframe, title, depth + 1)
        }

        return emptyList()
    }

    // ---------------------------------- Helpers ---------------------------------

    private fun absolute(path: String): String = when {
        path.isBlank() -> ""
        path.startsWith("http") -> path
        else -> baseUrl + path
    }

    private fun parseStatus(label: String): Int = when {
        label.contains("emisi", true) || label.contains("airing", true) -> SAnime.ONGOING
        label.contains("finaliz", true) || label.contains("finished", true) -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val M3U8_URL_REGEX = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
        private val MP4_URL_REGEX = Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""")
        private val JS_REDIRECT_REGEX = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")
    }
}
