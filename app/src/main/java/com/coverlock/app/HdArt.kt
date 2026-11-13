package com.coverlock.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/** What we are looking for, already cleaned of emoji, symbols and tags. */
private class Query(
    val titleForms: List<String>,   // cleaned title variants (best first)
    val albumForms: List<String>,   // cleaned album variants (best first)
    val artists: List<String>       // [all artists together, artist 1, artist 2, ...]
) {
    val titleText: String get() = titleForms.firstOrNull() ?: ""
    val albumText: String get() = albumForms.firstOrNull() ?: ""
    val firstArtist: String get() = artists.getOrNull(1) ?: artists.firstOrNull() ?: ""
}

/** One search result from a catalog. [urls] are the cover images, biggest first. */
private class Cand(val title: String, val artist: String, val album: String, val urls: List<String>)

/**
 * Finds a sharper version of the album cover than the music app gives us.
 *
 *  1. Cleans the song / artist / album text: drops emoji, decorative symbols and hieroglyphs,
 *     [MC] / (Deluxe) / - Remastered style tags, accents and fancy fonts.
 *  2. Searches Apple's public iTunes catalog (covers up to 3000x3000), matching the song first and
 *     then the album itself. Matching is fuzzy, so small spelling differences are fine.
 *  3. If iTunes has nothing, tries Deezer (covers up to 1000x1000).
 *  4. If that fails too, upgrades the music app's own cover URL to its largest size.
 *
 * Downloaded covers are cached so each song is only fetched once.
 */
object HdArt {

    private const val TITLE_MIN = 0.80
    private const val ALBUM_MIN = 0.80
    private const val ARTIST_MIN = 0.70
    private const val RETRY_AFTER_MS = 10 * 60 * 1000L

    private val misses = ConcurrentHashMap<String, Long>()

    private fun maxSide(c: Context): Int {
        val dm = c.resources.displayMetrics
        return max(dm.widthPixels, dm.heightPixels)
    }

    private fun cacheFile(c: Context, key: String) = File(File(c.cacheDir, "hd"), "$key.jpg")

    private fun recentMiss(key: String): Boolean {
        val t = misses[key] ?: return false
        return System.currentTimeMillis() - t < RETRY_AFTER_MS
    }

    /** A cover we already downloaded for this song, if it is bigger than [minWidth]. */
    fun cached(c: Context, title: String, artist: String, album: String, minWidth: Int): Bitmap? {
        val key = keyFor(title, artist, album) ?: return null
        val f = cacheFile(c, key)
        if (!f.exists()) return null
        val bmp = (try { decode(f.readBytes(), maxSide(c)) } catch (e: Exception) { null }) ?: return null
        return if (bmp.width > minWidth) bmp else null
    }

    /**
     * Downloads a sharper cover. Blocks on the network, so call it off the main thread.
     * @param allowArtistPhoto when the player gave no cover at all and nothing matched, use the
     *                         artist's photo as a last resort
     */
    fun fetch(
        c: Context, title: String, artist: String, album: String,
        artUris: List<String>, minWidth: Int, allowArtistPhoto: Boolean
    ): Bitmap? {
        val key = keyFor(title, artist, album)
        if (key != null && recentMiss(key)) return null

        val q = buildQuery(title, artist, album)
        val bytes = fromItunes(q) ?: fromDeezer(q) ?: fromUris(artUris) ?:
            (if (allowArtistPhoto) fromDeezerArtist(q) else null)
        val bmp = bytes?.let { decode(it, maxSide(c)) }
        if (bytes == null || bmp == null || bmp.width <= minWidth) {
            if (key != null) misses[key] = System.currentTimeMillis()
            return null
        }
        if (key != null) {
            try {
                val f = cacheFile(c, key)
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
                prune(f.parentFile)
            } catch (_: Exception) {}
        }
        return bmp
    }

    // ───────────── text cleaning ─────────────

    /** Tags in brackets: (Deluxe) [MC] {Remastered} 【…】 「…」 */
    private val BRACKETS = Regex("[(\\[{<\\u3010\\u300C\\u300E\\u300A\\u3008].*?[)\\]}>\\u3011\\u300D\\u300F\\u300B\\u3009]")
    private val FEAT = Regex("\\s(?:feat|ft|featuring)\\b.*$", RegexOption.IGNORE_CASE)
    private val DASH_TAIL = Regex("\\s[-\\u2013\\u2014]\\s.*$")
    private val COMBINING = Regex("[\\u0300-\\u036f]")

    /** Words that only describe an edition, never the name itself. */
    private val NOISE = setOf(
        "deluxe", "edition", "expanded", "remastered", "remaster",
        "explicit", "bonus", "anniversary", "mc"
    )

    /** Scripts real titles are written in. Anything else (hieroglyphs, runes, ...) is decoration. */
    private val ALLOWED_SCRIPTS = setOf(
        Character.UnicodeScript.LATIN, Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.GREEK,
        Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.BENGALI, Character.UnicodeScript.GURMUKHI,
        Character.UnicodeScript.GUJARATI, Character.UnicodeScript.TAMIL, Character.UnicodeScript.TELUGU,
        Character.UnicodeScript.KANNADA, Character.UnicodeScript.MALAYALAM, Character.UnicodeScript.ARABIC,
        Character.UnicodeScript.HEBREW, Character.UnicodeScript.THAI, Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL
    )

    /** Drops "badge" characters such as the negative-squared E used for Explicit, circled letters, flags. */
    private fun removeDecor(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val badge = cp in 0x1F100..0x1F1FF || cp in 0x2460..0x24FF
            if (!badge) sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun keepChar(cp: Int): Boolean {
        if (Character.isDigit(cp)) return true
        val type = Character.getType(cp)
        val isMark = type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
        if (!Character.isLetter(cp) && !isMark) return false
        val script = try { Character.UnicodeScript.of(cp) } catch (e: Exception) { return false }
        return script in ALLOWED_SCRIPTS || (isMark && script == Character.UnicodeScript.INHERITED)
    }

    /**
     * Turns any text into plain lowercase words.
     * @param strip also remove bracket tags, "feat. ...", and " - Remastered" style endings
     * @param dropNoise remove words like "deluxe" / "edition" (used for titles and albums, not artists)
     */
    private fun clean(input: String, strip: Boolean, dropNoise: Boolean): String {
        var s = removeDecor(input)
        s = Normalizer.normalize(s, Normalizer.Form.NFKD)   // fancy fonts -> plain letters
        s = COMBINING.replace(s, "")                         // é -> e
        if (strip) {
            s = BRACKETS.replace(s, " ")
            s = FEAT.replace(s, " ")
            s = DASH_TAIL.replace(s, " ")
        }
        s = s.replace("&", " and ").lowercase(Locale.ROOT)

        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (keepChar(cp)) sb.appendCodePoint(cp) else sb.append(' ')
        }
        var words = sb.toString().split(' ').filter { it.isNotEmpty() }
        if (dropNoise) words = words.filter { it !in NOISE }
        return words.joinToString(" ")
    }

    /** Fully cleaned variant first, then a gentler one that keeps bracket text. */
    private fun forms(s: String): List<String> =
        listOf(clean(s, strip = true, dropNoise = true), clean(s, strip = false, dropNoise = true))
            .filter { it.isNotEmpty() }
            .distinct()

    private val ARTIST_SPLIT = Regex(
        "\\s*(?:[,&;/\\u00B7\\u2022]|\\sx\\s|\\sfeat\\.?\\s|\\sft\\.?\\s|\\sfeaturing\\s|\\swith\\s|\\sand\\s)\\s*",
        RegexOption.IGNORE_CASE
    )

    private fun artistList(raw: String): List<String> {
        val out = ArrayList<String>()
        val full = clean(raw, strip = false, dropNoise = false)
        if (full.isNotEmpty()) out.add(full)
        for (part in raw.split(ARTIST_SPLIT)) {
            val p = clean(part, strip = false, dropNoise = false)
            if (p.isNotEmpty() && p !in out) out.add(p)
        }
        return out
    }

    private fun buildQuery(title: String, artist: String, album: String) =
        Query(forms(title), forms(album), artistList(artist))

    private fun keyFor(title: String, artist: String, album: String): String? {
        val t = forms(title).firstOrNull() ?: return null
        val artists = artistList(artist)
        val a = artists.getOrNull(1) ?: artists.firstOrNull() ?: ""
        val al = forms(album).firstOrNull() ?: ""
        val s = "$t|$a|$al"
        return Integer.toHexString(s.hashCode()) + "_" + s.length
    }

    // ───────────── fuzzy matching ─────────────

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 0.0 (different) … 1.0 (same), for two already-cleaned strings. */
    private fun sim(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val ka = a.replace(" ", "")
        val kb = b.replace(" ", "")
        if (ka == kb) return 1.0
        val shorter = min(ka.length, kb.length)
        val longer = max(ka.length, kb.length)
        if (shorter >= 3 && (ka.contains(kb) || kb.contains(ka))) {
            return if (shorter.toDouble() / longer >= 0.5) 0.88 else 0.70
        }
        val ta = a.split(' ').toSet()
        val tb = b.split(' ').toSet()
        val inter = ta.intersect(tb).size.toDouble()
        val jaccard = inter / (ta.size + tb.size - inter)
        val editSim = 1.0 - lev(ka, kb).toDouble() / longer
        return max(jaccard, editSim)
    }

    private fun bestSim(xs: List<String>, ys: List<String>): Double {
        var best = 0.0
        for (x in xs) for (y in ys) best = max(best, sim(x, y))
        return best
    }

    private fun artistOk(queryArtists: List<String>, resultArtist: String): Boolean {
        if (queryArtists.isEmpty()) return true
        val results = artistList(resultArtist)
        if (results.isEmpty()) return false
        return queryArtists.any { qa -> results.any { ra -> sim(qa, ra) >= ARTIST_MIN } }
    }

    private fun albumSim(q: Query, c: Cand): Double =
        if (q.albumForms.isEmpty()) 0.0 else bestSim(q.albumForms, forms(c.album))

    /** Best song result: right title, right artist, and preferably on the right album. */
    private fun pickSong(cands: List<Cand>, q: Query): Cand? {
        var best: Cand? = null
        var bestScore = 0.0
        for (c in cands) {
            val t = bestSim(q.titleForms, forms(c.title))
            if (t < TITLE_MIN) continue
            if (!artistOk(q.artists, c.artist)) continue
            val score = t + albumSim(q, c)
            if (score > bestScore) { best = c; bestScore = score }
        }
        return best
    }

    private fun pickAlbum(cands: List<Cand>, q: Query): Cand? {
        var best: Cand? = null
        var bestScore = 0.0
        for (c in cands) {
            val a = albumSim(q, c)
            if (a < ALBUM_MIN) continue
            if (!artistOk(q.artists, c.artist)) continue
            if (a > bestScore) { best = c; bestScore = a }
        }
        return best
    }

    /**
     * Song match first (preferring the one on the same album). If the song only exists on other
     * releases, look for the album itself. Otherwise settle for the song match.
     */
    private fun resolve(q: Query, songs: () -> List<Cand>, albums: () -> List<Cand>): ByteArray? {
        var songBest: Cand? = null
        if (q.titleForms.isNotEmpty()) {
            songBest = pickSong(songs(), q)
            if (songBest != null && (q.albumForms.isEmpty() || albumSim(q, songBest) >= ALBUM_MIN)) {
                download(songBest.urls)?.let { return it }
            }
        }
        if (q.albumForms.isNotEmpty()) {
            pickAlbum(albums(), q)?.let { a -> download(a.urls)?.let { return it } }
        }
        return songBest?.let { download(it.urls) }
    }

    private fun download(urls: List<String>): ByteArray? {
        for (u in urls) httpGet(u)?.let { return it }
        return null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ───────────── iTunes ─────────────

    private fun fromItunes(q: Query): ByteArray? {
        if (q.titleForms.isEmpty() && q.albumForms.isEmpty()) return null
        val countries = listOfNotNull(Locale.getDefault().country.takeIf { it.length == 2 }, "US").distinct()
        for (country in countries) {
            val hit = resolve(
                q,
                songs = { itunes("song", "${q.titleText} ${q.firstArtist}".trim(), country) },
                albums = { itunes("album", "${q.albumText} ${q.firstArtist}".trim(), country) }
            )
            if (hit != null) return hit
        }
        return null
    }

    private fun itunes(entity: String, term: String, country: String): List<Cand> {
        if (term.isBlank()) return emptyList()
        try {
            val url = "https://itunes.apple.com/search?media=music&entity=$entity&limit=15" +
                "&country=$country&term=" + enc(term)
            val body = httpGet(url)?.toString(Charsets.UTF_8) ?: return emptyList()
            val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
            val out = ArrayList<Cand>()
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val small = r.optString("artworkUrl100")
                if (small.isBlank()) continue
                val album = r.optString("collectionName")
                val title = if (entity == "album") album else r.optString("trackName")
                val urls = listOf(3000, 1600, 1000).map { size ->
                    small.replace(Regex("\\d+x\\d+bb"), size.toString() + "x" + size + "bb")
                }
                out.add(Cand(title, r.optString("artistName"), album, urls))
            }
            return out
        } catch (e: Exception) {
            return emptyList()
        }
    }

    // ───────────── Deezer (backup) ─────────────

    private fun fromDeezer(q: Query): ByteArray? {
        if (q.titleForms.isEmpty() && q.albumForms.isEmpty()) return null
        return resolve(
            q,
            songs = { deezer("search?limit=15&q=" + enc("${q.titleText} ${q.firstArtist}".trim()), false) },
            albums = { deezer("search/album?limit=15&q=" + enc("${q.albumText} ${q.firstArtist}".trim()), true) }
        )
    }

    private fun deezer(path: String, isAlbum: Boolean): List<Cand> {
        try {
            val body = httpGet("https://api.deezer.com/$path")?.toString(Charsets.UTF_8) ?: return emptyList()
            val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val out = ArrayList<Cand>()
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val artist = r.optJSONObject("artist")?.optString("name") ?: ""
                val title = r.optString("title")
                val albumTitle: String
                val covers: List<String>
                if (isAlbum) {
                    albumTitle = title
                    covers = listOf(r.optString("cover_xl"), r.optString("cover_big"))
                } else {
                    val al = r.optJSONObject("album")
                    albumTitle = al?.optString("title") ?: ""
                    covers = listOf(al?.optString("cover_xl") ?: "", al?.optString("cover_big") ?: "")
                }
                val urls = covers.filter { it.isNotBlank() && !it.contains("/cover//") }
                if (urls.isEmpty()) continue
                out.add(Cand(title, artist, albumTitle, urls))
            }
            return out
        } catch (e: Exception) {
            return emptyList()
        }
    }

    /** Last resort: the artist's photo from Deezer (1000x1000). */
    private fun fromDeezerArtist(q: Query): ByteArray? {
        val name = q.firstArtist
        if (name.isEmpty()) return null
        try {
            val body = httpGet("https://api.deezer.com/search/artist?limit=5&q=" + enc(name))
                ?.toString(Charsets.UTF_8) ?: return null
            val arr = JSONObject(body).optJSONArray("data") ?: return null
            var best: String? = null
            var bestSim = 0.0
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val s = sim(name, clean(r.optString("name"), strip = false, dropNoise = false))
                if (s < 0.85 || s <= bestSim) continue
                val pic = listOf(r.optString("picture_xl"), r.optString("picture_big"))
                    .firstOrNull { it.isNotBlank() && !it.contains("/artist//") }
                    ?: continue
                best = pic
                bestSim = s
            }
            return best?.let { httpGet(it) }
        } catch (e: Exception) {
            return null
        }
    }

    // ───────────── the music app's own cover link ─────────────

    private fun fromUris(uris: List<String>): ByteArray? {
        for (u in uris) {
            val upgraded = upgradeKnownUrl(u)
            httpGet(upgraded)?.let { return it }
            if (upgraded != u) httpGet(u)?.let { return it }
        }
        return null
    }

    /** Known URL patterns that have a bigger variant of the same image. */
    private fun upgradeKnownUrl(u: String): String {
        var out = u
        // Spotify: 300px / 64px variants -> 640px (their maximum)
        out = out.replace("ab67616d00001e02", "ab67616d0000b273").replace("ab67616d00004851", "ab67616d0000b273")
        // Google-hosted (YouTube Music): =w120-h120-... -> =w1600-h1600
        if (out.contains("googleusercontent.com") || out.contains("ytimg.com")) {
            out = out.replace(Regex("=w\\d+-h\\d+"), "=w1600-h1600")
        }
        return out
    }

    // ───────────── plumbing ─────────────

    private fun httpGet(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 12000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "CoverLock/1.0")
            if (conn.responseCode != 200) return null
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 15_000_000) return null
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } catch (e: Exception) {
            return null
        } finally {
            conn?.disconnect()
        }
    }

    /** Decodes without going far beyond what the screen can show (keeps memory in check). */
    private fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun prune(dir: File?) {
        val files = dir?.listFiles() ?: return
        if (files.size > 60) {
            files.sortedBy { it.lastModified() }.take(files.size - 40).forEach { it.delete() }
        }
    }
}
