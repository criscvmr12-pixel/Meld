package com.metrolist.music.utils

import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the track list of a public Spotify playlist from its embed page, without the Spotify API
 * or an account. The tracks are returned as placeholder songs (title + artists) that are later
 * matched against YouTube Music.
 */
object SpotifyPlaylistReader {
    data class SpotifyPlaylist(
        val name: String,
        val songs: List<Song>,
    )

    private val playlistIdRegex = Regex("""playlist[/:]([A-Za-z0-9]{22})""")
    private val nextDataRegex =
        Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    fun extractPlaylistId(link: String): String? = playlistIdRegex.find(link.trim())?.groupValues?.get(1)

    suspend fun read(link: String): Result<SpotifyPlaylist> =
        withContext(Dispatchers.IO) {
            runCatching {
                val id = extractPlaylistId(link) ?: error("Invalid Spotify playlist link")
                val json = JSONObject(extractNextData(download("https://open.spotify.com/embed/playlist/$id")))

                val tracks = mutableListOf<Pair<String, String>>()
                var name: String? = null
                walk(json) { obj ->
                    val uri = obj.optString("uri")
                    when {
                        uri.startsWith("spotify:track:") -> {
                            val title = obj.optString("title").trim()
                            val artists = obj.optString("subtitle").trim()
                            if (title.isNotEmpty()) tracks.add(title to artists)
                        }

                        uri.startsWith("spotify:playlist:") && name == null -> {
                            name = obj.optString("name").ifBlank { obj.optString("title") }.ifBlank { null }
                        }
                    }
                }

                val songs =
                    tracks.distinct().map { (title, artists) ->
                        Song(
                            song = SongEntity(id = "", title = title),
                            artists =
                                artists
                                    .split(",", ";")
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                    .map { ArtistEntity(id = "", name = it) },
                        )
                    }
                if (songs.isEmpty()) error("No tracks found in the playlist")
                SpotifyPlaylist(name ?: "Spotify playlist", songs)
            }
        }

    private fun download(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (connection.responseCode !in 200..299) error("Spotify returned ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractNextData(html: String): String =
        nextDataRegex.find(html)?.groupValues?.get(1) ?: error("Unexpected Spotify page format")

    // Searches the whole JSON instead of a fixed path so small layout changes don't break the import.
    private fun walk(
        node: Any?,
        visit: (JSONObject) -> Unit,
    ) {
        when (node) {
            is JSONObject -> {
                visit(node)
                node.keys().forEach { walk(node.opt(it), visit) }
            }

            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), visit)
        }
    }
}
