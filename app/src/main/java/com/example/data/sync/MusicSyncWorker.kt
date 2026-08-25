package com.example.data.sync

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.WryaMusicApplication
import com.example.data.local.AppDatabase
import com.example.data.local.SongEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.regex.Pattern

class MusicSyncWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "MusicSyncWorker"
        private const val NOTIFICATION_ID = 1001
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        private const val POLITE_DELAY_MS = 300L
        private const val MAX_PAGES_PER_RUN = 500
    }

    private val db = AppDatabase.getInstance(appContext)
    private val songDao = db.songDao()
    private val syncPrefs = SyncPreferences(appContext)

    override suspend fun doWork(): Result {
        val language = inputData.getString("language") ?: "کوردی"
        val sourceId = inputData.getString("sourceId") ?: "source_1"
        val isFullRescan = inputData.getBoolean("isFullRescan", false)
        val defaultUrl = when (sourceId) {
            "source_2", "کوردی_1" -> "https://hawrami.ir"
            "source_3", "فارسی_0" -> "https://gitarmuzic.com"
            else -> "https://musickordi.com"
        }
        val baseUrl = inputData.getString("baseUrl") ?: defaultUrl

        Log.d(TAG, "Starting MusicSyncWorker for $sourceId ($language) at $baseUrl")
        syncPrefs.setSyncState(sourceId = sourceId, isSyncing = true, status = "در حال اتصال به سرور...")

        val initialNotification = createNotification("آماده‌سازی همگام‌سازی $language...", 0, 0)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setForeground(
                    ForegroundInfo(
                        NOTIFICATION_ID,
                        initialNotification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                )
            } else {
                setForeground(ForegroundInfo(NOTIFICATION_ID, initialNotification))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to set foreground info: ${e.message}")
        }

        return when {
            baseUrl.contains("gitarmuzic.com") || sourceId == "source_3" || sourceId == "فارسی_0" -> {
                doWorkGitarmuzic(sourceId, language, baseUrl, isFullRescan)
            }
            baseUrl.contains("hawrami.ir") || sourceId == "source_2" || sourceId == "کوردی_1" -> {
                doWorkHawrami(sourceId, language, baseUrl, isFullRescan)
            }
            else -> {
                doWorkMusickordi(sourceId, language, baseUrl, isFullRescan)
            }
        }
    }

    // ==========================================
    // SOURCE 1: MUSICKORDI.COM
    // ==========================================

    private suspend fun doWorkMusickordi(
        sourceId: String,
        language: String,
        baseUrl: String,
        isFullRescan: Boolean
    ): Result {
        var totalSyncedInSession = 0
        var shouldStopSync = false

        try {
            val isCompleted = syncPrefs.isSyncCompleted(sourceId)
            val lastCompletedPage = if (isFullRescan || isCompleted) 0 else syncPrefs.getLastCompletedPage(sourceId)
            val startPage = lastCompletedPage + 1
            var currentPage = startPage

            Log.d(TAG, "Musickordi syncing from page $startPage. Completed: $isCompleted, Rescan: $isFullRescan")

            while (coroutineContext.isActive && !shouldStopSync && !isStopped && currentPage <= startPage + MAX_PAGES_PER_RUN) {
                val pageUrl = if (currentPage == 1) "$baseUrl/" else "$baseUrl/page/$currentPage/".replace("musickordi.com/page/", "musickordi.com/pages/")
                
                updateNotification("بررسی صفحه $currentPage ($language)...", totalSyncedInSession)
                syncPrefs.setSyncState(
                    sourceId = sourceId,
                    isSyncing = true,
                    status = "در حال دریافت صفحه $currentPage...",
                    count = totalSyncedInSession
                )

                delay(POLITE_DELAY_MS)

                val doc: Document? = try {
                    Jsoup.connect(pageUrl)
                        .userAgent(USER_AGENT)
                        .referrer(baseUrl)
                        .header("Accept-Language", "fa,ku,en;q=0.9")
                        .timeout(15000)
                        .get()
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching page $pageUrl: ${e.message}")
                    null
                }

                if (doc == null) {
                    Log.w(TAG, "Failed to load page $currentPage. Retrying next or ending.")
                    break
                }

                val postElements = doc.select("article.post, .center-right-panels article, article, .post-item")
                val articles = postElements.filter {
                    !it.hasClass("vip") && !it.hasClass("sticky") && !it.parents().any { p -> p.id() == "vip-music" }
                }

                if (articles.isEmpty()) {
                    Log.d(TAG, "No more articles found on page $currentPage. Finishing.")
                    syncPrefs.setSyncCompleted(sourceId, true)
                    break
                }

                var hitExistingInQuickSync = false

                for (article in articles) {
                    if (!coroutineContext.isActive || isStopped) break

                    if (article.hasClass("vip") || article.hasClass("sticky") || article.hasClass("banner-post")) {
                        continue
                    }

                    val postId = extractNumericPostId(article) ?: continue

                    val alreadyExists = songDao.exists(postId)
                    if (alreadyExists && isCompleted && !isFullRescan) {
                        Log.d(TAG, "Post ID $postId already exists. Quick sync hit boundary.")
                        hitExistingInQuickSync = true
                        break
                    }

                    val song = parseMusickordiArticle(article, postId, baseUrl, language)
                    if (song != null) {
                        songDao.upsertPreservingUserState(song)
                        totalSyncedInSession++
                        updateNotification("دریافت شد: ${song.title}", totalSyncedInSession)
                    }

                    delay(POLITE_DELAY_MS)
                }

                if (hitExistingInQuickSync) {
                    syncPrefs.setSyncCompleted(sourceId, true)
                    break
                }

                if (!isCompleted || isFullRescan) {
                    syncPrefs.setLastCompletedPage(sourceId, currentPage)
                }
                
                currentPage++
            }

            val now = System.currentTimeMillis()
            syncPrefs.setLastSyncTime(sourceId, now)
            
            val lastPage = syncPrefs.getLastCompletedPage(sourceId)
            val finishStatus = if (isStopped || !coroutineContext.isActive) {
                "همگام‌سازی متوقف شد (صفحه $lastPage • $totalSyncedInSession آهنگ)"
            } else if (totalSyncedInSession == 0) {
                "آرشیو به‌روز است (بدون آهنگ جدید)"
            } else {
                "همگام‌سازی پایان یافت ($totalSyncedInSession آهنگ جدید دریافت شد)"
            }
            
            syncPrefs.setSyncState(
                sourceId = sourceId,
                isSyncing = false,
                status = finishStatus,
                count = totalSyncedInSession
            )

            return Result.success()

        } catch (e: kotlinx.coroutines.CancellationException) {
            val lastPage = syncPrefs.getLastCompletedPage(sourceId)
            syncPrefs.setSyncState(sourceId = sourceId, isSyncing = false, status = "همگام‌سازی متوقف شد (صفحه $lastPage)")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Fatal sync error: ${e.message}", e)
            syncPrefs.setSyncState(sourceId = sourceId, isSyncing = false, status = "خطا: ${e.localizedMessage ?: "خطای شبکه"}")
            return Result.failure()
        }
    }

    private fun extractNumericPostId(article: Element): Long? {
        val allLinks = article.select("h2 a, h1 a, header a, .post-title a, a[href]").map { it.attr("href") }
        for (link in allLinks) {
            val musicMatcher = Pattern.compile("(?:music/|/music/|/)(\\d+)/").matcher(link)
            if (musicMatcher.find()) {
                val id = musicMatcher.group(1)?.toLongOrNull()
                if (id != null && id > 0) return id
            }
        }

        val idAttr = article.id()
        if (idAttr.isNotEmpty()) {
            val matcher = Pattern.compile("(\\d+)").matcher(idAttr)
            if (matcher.find()) {
                val id = matcher.group(1)?.toLongOrNull()
                if (id != null && id > 0) return id
            }
        }

        val classAttr = article.className()
        val classMatcher = Pattern.compile("post-(\\d+)").matcher(classAttr)
        if (classMatcher.find()) {
            val id = classMatcher.group(1)?.toLongOrNull()
            if (id != null && id > 0) return id
        }

        return null
    }

    private suspend fun parseMusickordiArticle(article: Element, postId: Long, baseUrl: String, language: String): SongEntity? {
        try {
            val h3Text = article.selectFirst(".post-short-content h3, h3")?.text()?.trim()
            val postTitleA = article.selectFirst(".post-title a, h2 a, h1 a")
            val rawTitle = postTitleA?.text()?.trim() ?: h3Text ?: "آهنگ کوردی"
            val postUrl = postTitleA?.attr("href") ?: article.selectFirst("a[href]")?.attr("href") ?: ""

            var artist = "هنرمند"
            var cleanTitle = rawTitle

            if (!h3Text.isNullOrEmpty() && h3Text.contains(" ")) {
                val words = h3Text.split("\\s+".toRegex())
                if (words.size >= 2) {
                    if (words.size == 2) {
                        artist = words[0]
                        cleanTitle = words[1]
                    } else if (words.size == 3) {
                        artist = "${words[0]} ${words[1]}"
                        cleanTitle = words[2]
                    } else {
                        val vIndex = words.indexOf("و")
                        if (vIndex in 1..2 && words.size > vIndex + 2) {
                            artist = words.take(vIndex + 3).joinToString(" ")
                            cleanTitle = words.drop(vIndex + 3).joinToString(" ")
                        } else {
                            artist = "${words[0]} ${words[1]}"
                            cleanTitle = words.drop(2).joinToString(" ")
                        }
                    }
                }
            } else if (rawTitle.contains(" به نام ")) {
                val parts = rawTitle.split(" به نام ")
                artist = parts[0].replace("دانلود آهنگ جدید", "").replace("دانلود آهنگ", "").replace("آهنگ", "").trim()
                cleanTitle = parts[1].trim()
            } else if (rawTitle.contains(" - ")) {
                val parts = rawTitle.split(" - ")
                artist = parts[0].replace("دانلود آهنگ جدید", "").replace("دانلود آهنگ", "").replace("آهنگ", "").trim()
                cleanTitle = parts[1].trim()
            } else if (rawTitle.contains(" از ")) {
                val parts = rawTitle.split(" از ")
                cleanTitle = parts[0].replace("دانلود آهنگ جدید", "").replace("دانلود آهنگ", "").replace("آهنگ", "").trim()
                artist = parts[1].trim()
            }

            cleanTitle = cleanTitle
                .replace("دانلود آهنگ جدید", "")
                .replace("دانلود آهنگ", "")
                .replace("دانلود", "")
                .replace("+ متن آهنگ", "")
                .replace("+ متن", "")
                .trim()

            val imgElem = article.selectFirst(".this-post-img-box img, img.post-image, img")
            var coverUrl = imgElem?.attr("data-src")?.ifEmpty { null }
                ?: imgElem?.attr("data-lazy-src")?.ifEmpty { null }
                ?: imgElem?.attr("src")?.ifEmpty { null }

            var streamUrl = article.selectFirst("audio source[src]")?.attr("src")
                ?: article.selectFirst("audio[src]")?.attr("src")
                ?: article.selectFirst("source[src]")?.attr("src")

            if (streamUrl.isNullOrEmpty() && postUrl.isNotEmpty()) {
                val fullPostUrl = if (postUrl.startsWith("http")) postUrl else "$baseUrl/$postUrl".replace("//", "/").replace("https:/", "https://")
                delay(POLITE_DELAY_MS)
                val detailDoc = try {
                    Jsoup.connect(fullPostUrl)
                        .userAgent(USER_AGENT)
                        .referrer(baseUrl)
                        .timeout(10000)
                        .get()
                } catch (e: Exception) {
                    null
                }

                if (detailDoc != null) {
                    streamUrl = detailDoc.selectFirst("audio source[src]")?.attr("src")
                        ?: detailDoc.selectFirst("audio[src]")?.attr("src")
                        ?: detailDoc.select("a[href*=.mp3]").firstOrNull { it.attr("href").contains("128") }?.attr("href")
                        ?: detailDoc.select("a[href*=.mp3]").firstOrNull { it.attr("href").contains("320") }?.attr("href")
                        ?: detailDoc.selectFirst("a[href$=.mp3], a[href*=.mp3?], a.dl[href*=.mp3]")?.attr("href")
                }
            }

            if (streamUrl.isNullOrEmpty()) {
                return null
            }

            val tagElements = article.select(".tags a, .post-tags a, .cat-links a, rel[tag]")
            val tagList = tagElements.map { it.text().trim() }.filter { it.isNotEmpty() }.toMutableList()
            val textToScan = "$rawTitle $h3Text"
            if (textToScan.contains("شاد") && !tagList.contains("شاد")) tagList.add("شاد")
            if (textToScan.contains("غمگین") && !tagList.contains("غمگین")) tagList.add("غمگین")
            if (textToScan.contains("ریمیکس") && !tagList.contains("ریمیکس")) tagList.add("ریمیکس")
            if (textToScan.contains("هلپرکی") || textToScan.contains("هلپرکه")) tagList.add("هلپرکی")
            if (tagList.isEmpty()) tagList.add("کوردی")

            val tagsString = tagList.joinToString(", ")
            val publishDate = 1700000000000L + postId * 1000L

            return SongEntity(
                id = postId,
                title = cleanTitle.ifEmpty { "آهنگ کوردی" },
                artist = artist.ifEmpty { "هنرمند" },
                coverUrl = coverUrl,
                streamUrl = streamUrl,
                publishDate = publishDate,
                tags = tagsString,
                isFavorite = false,
                isAvailable = true,
                downloadedUri = null,
                language = "کوردی",
                sourceNumber = 1 // سورس ۱: موزیک کردی
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing musickordi song article: ${e.message}")
            return null
        }
    }

    // ==========================================
    // SOURCE 2: HAWRAMI.IR
    // ==========================================

    private suspend fun doWorkHawrami(
        sourceId: String,
        language: String,
        baseUrl: String,
        isFullRescan: Boolean
    ): Result {
        Log.d(TAG, "Starting dedicated Hawrami sync for $sourceId at $baseUrl")
        var totalSyncedInSession = 0

        try {
            val isCompleted = syncPrefs.isSyncCompleted(sourceId)
            val lastCompletedPage = if (isFullRescan || isCompleted) 0 else syncPrefs.getLastCompletedPage(sourceId)
            val startPage = lastCompletedPage + 1
            var currentPage = startPage

            while (coroutineContext.isActive && !isStopped && currentPage <= startPage + MAX_PAGES_PER_RUN) {
                val pageUrl = if (currentPage == 1) {
                    if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                } else {
                    val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                    "${base}page/$currentPage"
                }

                updateNotification("بررسی صفحه $currentPage (هورامی)...", totalSyncedInSession)
                syncPrefs.setSyncState(
                    sourceId = sourceId,
                    isSyncing = true,
                    status = "در حال دریافت صفحه $currentPage...",
                    count = totalSyncedInSession
                )

                delay(POLITE_DELAY_MS)

                val doc: Document? = try {
                    Jsoup.connect(pageUrl)
                        .userAgent(USER_AGENT)
                        .referrer(baseUrl)
                        .header("Accept-Language", "fa,ku,en;q=0.9")
                        .timeout(15000)
                        .followRedirects(true)
                        .get()
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching Hawrami page $pageUrl: ${e.message}")
                    null
                }

                if (doc == null) {
                    break
                }

                val articles = doc.select("article.music_posts, article.song_tw")
                if (articles.isEmpty()) {
                    syncPrefs.setSyncCompleted(sourceId, true)
                    break
                }

                var hitExistingInQuickSync = false

                for (article in articles) {
                    if (!coroutineContext.isActive || isStopped) break

                    val postLinkElem = article.selectFirst("header a[href], h2 a[href], a[href]")
                    val postUrl = postLinkElem?.attr("href") ?: continue
                    if (postUrl.isBlank()) continue

                    val postId = generateHawramiPostId(postUrl)

                    val alreadyExists = songDao.exists(postId)
                    if (alreadyExists && isCompleted && !isFullRescan) {
                        hitExistingInQuickSync = true
                        break
                    }

                    val song = parseHawramiSongArticle(article, postId, postUrl, baseUrl)
                    if (song != null) {
                        songDao.upsertPreservingUserState(song)
                        totalSyncedInSession++
                        updateNotification("دریافت شد: ${song.title}", totalSyncedInSession)
                    }

                    delay(POLITE_DELAY_MS)
                }

                if (hitExistingInQuickSync) {
                    syncPrefs.setSyncCompleted(sourceId, true)
                    break
                }

                if (!isCompleted || isFullRescan) {
                    syncPrefs.setLastCompletedPage(sourceId, currentPage)
                }

                currentPage++
            }

            val now = System.currentTimeMillis()
            syncPrefs.setLastSyncTime(sourceId, now)

            val lastPage = syncPrefs.getLastCompletedPage(sourceId)
            val finishStatus = if (isStopped || !coroutineContext.isActive) {
                "همگام‌سازی متوقف شد (صفحه $lastPage • $totalSyncedInSession آهنگ)"
            } else if (totalSyncedInSession == 0) {
                "آرشیو به‌روز است (بدون آهنگ جدید)"
            } else {
                "همگام‌سازی پایان یافت ($totalSyncedInSession آهنگ جدید دریافت شد)"
            }

            syncPrefs.setSyncState(
                sourceId = sourceId,
                isSyncing = false,
                status = finishStatus,
                count = totalSyncedInSession
            )

            return Result.success()

        } catch (e: kotlinx.coroutines.CancellationException) {
            val lastPage = syncPrefs.getLastCompletedPage(sourceId)
            syncPrefs.setSyncState(sourceId = sourceId, isSyncing = false, status = "همگام‌سازی متوقف شد (صفحه $lastPage)")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Fatal Hawrami sync error: ${e.message}", e)
            syncPrefs.setSyncState(sourceId = sourceId, isSyncing = false, status = "خطا: ${e.localizedMessage ?: "خطای شبکه"}")
            return Result.failure()
        }
    }

    private fun generateHawramiPostId(url: String): Long {
        val slug = url.trim().lowercase()
            .substringAfterLast("/")
            .substringBefore(".html")
            .removeSuffix("/")
        val crc = java.util.zip.CRC32()
        crc.update(slug.toByteArray(Charsets.UTF_8))
        return 5_000_000_000L + crc.value
    }

    private suspend fun parseHawramiSongArticle(
        article: Element,
        postId: Long,
        postUrl: String,
        baseUrl: String
    ): SongEntity? {
        try {
            var artist = "هنرمند"
            var title = "آهنگ کوردی"

            val artistSpan = article.selectFirst(".artist_names")?.text()?.trim()
            val songSpan = article.selectFirst(".song_names")?.text()?.trim()

            if (!artistSpan.isNullOrBlank() || !songSpan.isNullOrBlank()) {
                if (!artistSpan.isNullOrBlank()) artist = artistSpan
                if (!songSpan.isNullOrBlank()) title = songSpan
            } else {
                val h2Text = article.selectFirst("h2 a, h1 a, header a")?.text()?.trim() ?: ""
                var clean = h2Text
                    .replace("دانلود موزیک ویدیو", "")
                    .replace("دانلود آهنگ جدید", "")
                    .replace("دانلود آهنگ", "")
                    .replace("دانلود موزیک", "")
                    .replace("دانلود", "")
                    .trim()

                if (clean.contains(" به نام ")) {
                    val parts = clean.split(" به نام ")
                    artist = parts[0].trim()
                    title = parts[1].trim()
                } else if (clean.contains(" - ")) {
                    val parts = clean.split(" - ")
                    artist = parts[0].trim()
                    title = parts[1].trim()
                } else if (clean.contains(" از ")) {
                    val parts = clean.split(" از ")
                    title = parts[0].trim()
                    artist = parts[1].trim()
                } else {
                    val words = clean.split("\\s+".toRegex())
                    if (words.size >= 2) {
                        artist = "${words[0]} ${words.getOrNull(1) ?: ""}".trim()
                        title = words.drop(2).joinToString(" ").ifEmpty { clean }
                    } else {
                        title = clean.ifEmpty { "آهنگ کوردی" }
                    }
                }
            }

            val imgElem = article.selectFirst("img")
            var coverUrl = imgElem?.attr("src")?.ifEmpty { null }
                ?: imgElem?.attr("data-src")?.ifEmpty { null }

            val fullPostUrl = if (postUrl.startsWith("http")) postUrl else {
                val b = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                b + postUrl.removePrefix("/")
            }

            delay(POLITE_DELAY_MS)

            val detailDoc = try {
                Jsoup.connect(fullPostUrl)
                    .userAgent(USER_AGENT)
                    .referrer(baseUrl)
                    .timeout(10000)
                    .followRedirects(true)
                    .get()
            } catch (e: Exception) {
                null
            }

            var streamUrl: String? = null
            if (detailDoc != null) {
                val ogImage = detailDoc.selectFirst("meta[property=og:image]")?.attr("content")
                if (!ogImage.isNullOrBlank()) {
                    coverUrl = ogImage
                }

                streamUrl = detailDoc.selectFirst(".singlles_box_cv audio[src]")?.attr("src")
                    ?: detailDoc.selectFirst("audio source[src]")?.attr("src")
                    ?: detailDoc.selectFirst("audio[src]")?.attr("src")
                    ?: detailDoc.select("a[href*=.mp3]").firstOrNull { it.attr("href").contains("128") }?.attr("href")
                    ?: detailDoc.select("a[href*=.mp3]").firstOrNull { it.attr("href").contains("320") }?.attr("href")
                    ?: detailDoc.selectFirst("a[href$=.mp3], a[href*=.mp3?], a.dl[href*=.mp3], .dl_links a[href*=.mp3]")?.attr("href")
            }

            if (streamUrl.isNullOrEmpty() || streamUrl.contains(".mp4", ignoreCase = true)) {
                return null
            }

            val normalizedStreamUrl = com.example.util.UrlHelper.normalizeAudioUrl(streamUrl)
            val normalizedCoverUrl = coverUrl?.let { com.example.util.UrlHelper.normalizeAudioUrl(it) }

            val tagElements = article.select(".song_tw_det a, .tags a, rel[tag], .categories_block a")
            val tagList = tagElements.map { it.text().trim() }.filter { it.isNotEmpty() }.toMutableList()
            val textToScan = "$title $artist ${article.text()}"
            if (textToScan.contains("شاد") && !tagList.contains("شاد")) tagList.add("شاد")
            if (textToScan.contains("غمگین") && !tagList.contains("غمگین")) tagList.add("غمگین")
            if (textToScan.contains("ریمیکس") && !tagList.contains("ریمیکس")) tagList.add("ریمیکس")
            if (textToScan.contains("هلپرکی") || textToScan.contains("هلپرکه")) tagList.add("هلپرکی")
            if (tagList.isEmpty()) tagList.add("کوردی")

            val tagsString = tagList.joinToString(", ")
            val publishDate = System.currentTimeMillis() - (postId % 1000000L * 1000L)

            return SongEntity(
                id = postId,
                title = title.ifEmpty { "آهنگ کوردی" },
                artist = artist.ifEmpty { "هنرمند" },
                coverUrl = normalizedCoverUrl,
                streamUrl = normalizedStreamUrl,
                publishDate = publishDate,
                tags = tagsString,
                isFavorite = false,
                isAvailable = true,
                downloadedUri = null,
                language = "کوردی",
                sourceNumber = 2 // سورس ۲: هورامی
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Hawrami song article: ${e.message}")
            return null
        }
    }

    // ==========================================
    // SOURCE 3: GITARMUZIC.COM (PERSIAN SOURCE)
    // ==========================================

    private suspend fun doWorkGitarmuzic(
        sourceId: String,
        language: String,
        baseUrl: String,
        isFullRescan: Boolean
    ): Result {
        Log.d(TAG, "Starting dedicated GitarMuzic sync for $sourceId at $baseUrl")
        var totalSyncedInSession = 0

        try {
            val isCompleted = syncPrefs.isSyncCompleted(sourceId)
            val lastCompletedPage = if (isFullRescan || isCompleted) 0 else syncPrefs.getLastCompletedPage(sourceId)
            val startPage = lastCompletedPage + 1
            var currentPage = startPage

            while (coroutineContext.isActive && !isStopped && currentPage <= startPage + MAX_PAGES_PER_RUN) {
                val pageUrl = if (currentPage == 1) {
                    if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                } else {
                    val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                    "${base}page/$currentPage/"
                }

                updateNotification("بررسی صفحه $currentPage (گیتار موزیک)...", totalSyncedInSession)
                syncPrefs.setSyncState(
                    sourceId = sourceId,
                    isSyncing = true,
                    status = "در حال دریافت صفحه $currentPage...",
                    count = totalSyncedInSession
                )

                delay(POLITE_DELAY_MS)

                val doc: Document? = try {
                    Jsoup.connect(pageUrl)
                        .userAgent(USER_AGENT)
                        .referrer(baseUrl)
                        .header("Accept-Language", "fa-IR,fa;q=0.9,en;q=0.8")
                        .timeout(15000)
                        .followRedirects(true)
                        .get()
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching GitarMuzic page $pageUrl: ${e.message}")
                    null
                }

                if (doc == null) {
                    break
                }

                val articles = doc.select("article.home-post, article.post, article")
                if (articles.isEmpty()) {
                    syncPrefs.setSyncCompleted(sourceId, true)
                    break
                }

                var hitExistingInQuickSync = false

                for (article in articles) {
                    if (!coroutineContext.isActive || isStopped) break

                    val postLinkElem = article.selectFirst("a[href*='gitarmuzic.com'], h2 a, h1 a, a.post-title, a[href]")
                    val postUrl = postLinkElem?.attr("href") ?: continue
                    if (postUrl.isBlank() || postUrl.contains("/page/") || postUrl.contains("/category/")) continue

                    val postId = generateGitarmuzicPostId(postUrl)

                    val alreadyExists = songDao.exists(postId)
                    if (alreadyExists && isCompleted && !isFullRescan) {
                        hitExistingInQuickSync = true
                        break
                    }

                    val song = parseGitarmuzicSongArticle(article, postId, postUrl, baseUrl)
                    if (song != null) {
                        songDao.upsertPreservingUserState(song)
                        totalSyncedInSession++
                        updateNotification("دریافت شد: ${song.title} (${song.artist})", totalSyncedInSession)
                    }

                    delay(POLITE_DELAY_MS)
                }

                if (hitExistingInQuickSync) {
                    syncPrefs.setSyncCompleted(sourceId, true)
                    break
                }

                if (!isCompleted || isFullRescan) {
                    syncPrefs.setLastCompletedPage(sourceId, currentPage)
                }

                currentPage++
            }

            val now = System.currentTimeMillis()
            syncPrefs.setLastSyncTime(sourceId, now)

            val lastPage = syncPrefs.getLastCompletedPage(sourceId)
            val finishStatus = if (isStopped || !coroutineContext.isActive) {
                "همگام‌سازی متوقف شد (صفحه $lastPage • $totalSyncedInSession آهنگ)"
            } else if (totalSyncedInSession == 0) {
                "آرشیو به‌روز است (بدون آهنگ جدید)"
            } else {
                "همگام‌سازی پایان یافت ($totalSyncedInSession آهنگ جدید دریافت شد)"
            }

            syncPrefs.setSyncState(
                sourceId = sourceId,
                isSyncing = false,
                status = finishStatus,
                count = totalSyncedInSession
            )

            return Result.success()

        } catch (e: kotlinx.coroutines.CancellationException) {
            val lastPage = syncPrefs.getLastCompletedPage(sourceId)
            syncPrefs.setSyncState(sourceId = sourceId, isSyncing = false, status = "همگام‌سازی متوقف شد (صفحه $lastPage)")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Fatal GitarMuzic sync error: ${e.message}", e)
            syncPrefs.setSyncState(sourceId = sourceId, isSyncing = false, status = "خطا: ${e.localizedMessage ?: "خطای شبکه"}")
            return Result.failure()
        }
    }

    private fun generateGitarmuzicPostId(url: String): Long {
        val slug = url.trim().lowercase()
            .removeSuffix("/")
            .substringAfterLast("/")
            .substringBefore(".html")
        val crc = java.util.zip.CRC32()
        crc.update(slug.toByteArray(Charsets.UTF_8))
        return 7_000_000_000L + crc.value
    }

    private suspend fun parseGitarmuzicSongArticle(
        article: Element,
        postId: Long,
        postUrl: String,
        baseUrl: String
    ): SongEntity? {
        try {
            val fullPostUrl = if (postUrl.startsWith("http")) postUrl else {
                val b = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                b + postUrl.removePrefix("/")
            }

            var coverUrl = article.selectFirst("img")?.attr("data-src")?.ifEmpty { null }
                ?: article.selectFirst("img")?.attr("src")?.ifEmpty { null }

            delay(POLITE_DELAY_MS)

            val detailDoc = try {
                Jsoup.connect(fullPostUrl)
                    .userAgent(USER_AGENT)
                    .referrer(baseUrl)
                    .timeout(12000)
                    .followRedirects(true)
                    .get()
            } catch (e: Exception) {
                null
            } ?: return null

            val pageHtml = detailDoc.html()

            // High-resolution Cover
            val ogImage = detailDoc.selectFirst("meta[property=og:image]")?.attr("content")
            if (!ogImage.isNullOrBlank()) {
                coverUrl = ogImage
            }

            // Stream MP3
            val mp3Links = detailDoc.select("a[href*=.mp3]")
            var streamUrl: String? = null

            // Prioritize 320kbps, then 128kbps, then any mp3
            streamUrl = mp3Links.firstOrNull { it.attr("href").contains("320") }?.attr("href")
                ?: mp3Links.firstOrNull { it.attr("href").contains("128") }?.attr("href")
                ?: mp3Links.firstOrNull()?.attr("href")
                ?: detailDoc.selectFirst("audio source[src]")?.attr("src")
                ?: detailDoc.selectFirst("audio[src]")?.attr("src")

            if (streamUrl.isNullOrEmpty()) {
                return null
            }

            // Precise Artist & Song Title Extraction Algorithm
            val rawH1 = detailDoc.selectFirst("h1")?.text()?.trim() ?: ""
            var artist = ""
            var title = ""

            // Strategy 1: Look at <p> tags with 'دانلود آهنگ ... به نام ...' or 'دانلود آهنگ ... از ...'
            val pTags = detailDoc.select("p")
            for (p in pTags) {
                val text = p.text().trim()
                val benamMatcher = Pattern.compile("دانلود آهنگ\\s+(.*?)\\s+به نام\\s+(.*?)(?:\\s+با کیفیت|\\s+در رسانه|\\s+اصلی|\\s+کامل|$)", Pattern.CASE_INSENSITIVE).matcher(text)
                if (benamMatcher.find()) {
                    artist = benamMatcher.group(1)?.trim() ?: ""
                    title = benamMatcher.group(2)?.trim() ?: ""
                    break
                }

                val azMatcher = Pattern.compile("دانلود آهنگ\\s+(.*?)\\s+از\\s+(.*?)(?:\\s+با کیفیت|\\s+در رسانه|\\s+اصلی|\\s+کامل|$)", Pattern.CASE_INSENSITIVE).matcher(text)
                if (azMatcher.find()) {
                    title = azMatcher.group(1)?.trim() ?: ""
                    artist = azMatcher.group(2)?.trim() ?: ""
                    break
                }
            }

            // Strategy 2: Check Artist Archive link tag (e.g. آرشیو آثار [خواننده])
            if (artist.isEmpty()) {
                val archiveMatcher = Pattern.compile(">آرشیو آثار\\s+([^<]+)<").matcher(pageHtml)
                if (archiveMatcher.find()) {
                    artist = archiveMatcher.group(1)?.trim() ?: ""
                }
            }

            // Strategy 3: Check "DownLoad Music: Title - Artist" English tag
            if (artist.isEmpty() || title.isEmpty()) {
                val dlEnMatcher = Pattern.compile("DownLoad Music:\\s*([^–\\-+]+)\\s*[–\\-]\\s*([^–\\-+]+)").matcher(pageHtml)
                if (dlEnMatcher.find()) {
                    if (title.isEmpty()) title = dlEnMatcher.group(1)?.trim() ?: ""
                    if (artist.isEmpty()) artist = dlEnMatcher.group(2)?.trim() ?: ""
                }
            }

            // Strategy 4: Clean up H1 with extracted artist or delimiters
            if (title.isEmpty() && rawH1.isNotEmpty()) {
                var cleanH1 = rawH1
                    .replace("دانلود آهنگ جدید", "")
                    .replace("دانلود آهنگ", "")
                    .replace("دانلود موزیک", "")
                    .replace("+ متن ترانه", "")
                    .replace("+ متن", "")
                    .trim()

                if (artist.isNotEmpty()) {
                    cleanH1 = cleanH1.replace(artist, "").trim(' ', '-', '–', '،', ':')
                    title = cleanH1
                } else if (cleanH1.contains(" - ")) {
                    val parts = cleanH1.split(" - ")
                    artist = parts[0].trim()
                    title = parts[1].trim()
                } else if (cleanH1.contains(" به نام ")) {
                    val parts = cleanH1.split(" به نام ")
                    artist = parts[0].trim()
                    title = parts[1].trim()
                } else if (cleanH1.contains(" از ")) {
                    val parts = cleanH1.split(" از ")
                    title = parts[0].trim()
                    artist = parts[1].trim()
                } else {
                    title = cleanH1
                }
            }

            // Strategy 5: Parse MP3 filename if artist or title still missing
            if (artist.isEmpty() || title.isEmpty()) {
                try {
                    val filename = URLDecoder.decode(streamUrl.substringAfterLast("/"), "UTF-8")
                        .replace(".mp3", "", ignoreCase = true)
                        .replace("\\[\\d+\\]".toRegex(), "")
                        .trim()
                    if (filename.contains(" - ")) {
                        val parts = filename.split(" - ")
                        if (artist.isEmpty()) artist = parts[0].trim()
                        if (title.isEmpty()) title = parts[1].trim()
                    }
                } catch (e: Exception) { }
            }

            val finalTitle = title.ifEmpty { rawH1.replace("دانلود آهنگ جدید", "").replace("دانلود آهنگ", "").trim().ifEmpty { "آهنگ فارسی" } }
            val finalArtist = artist.ifEmpty { "هنرمند" }

            val normalizedStreamUrl = com.example.util.UrlHelper.normalizeAudioUrl(streamUrl)
            val normalizedCoverUrl = coverUrl?.let { com.example.util.UrlHelper.normalizeAudioUrl(it) }

            val tagElements = detailDoc.select(".post-tags a, a[rel=tag], .tag a, .cat-links a")
            val tagList = tagElements.map { it.text().trim() }.filter { it.isNotEmpty() }.toMutableList()
            val textToScan = "$finalTitle $finalArtist ${detailDoc.text()}"
            if (textToScan.contains("شاد") && !tagList.contains("شاد")) tagList.add("شاد")
            if (textToScan.contains("غمگین") && !tagList.contains("غمگین")) tagList.add("غمگین")
            if (textToScan.contains("ریمیکس") && !tagList.contains("ریمیکس")) tagList.add("ریمیکس")
            if (textToScan.contains("پاپ") && !tagList.contains("پاپ")) tagList.add("پاپ")
            if (textToScan.contains("سنتی") && !tagList.contains("سنتی")) tagList.add("سنتی")
            if (tagList.isEmpty()) tagList.add("فارسی")

            val tagsString = tagList.joinToString(", ")
            val publishDate = System.currentTimeMillis() - (postId % 1000000L * 1000L)

            return SongEntity(
                id = postId,
                title = finalTitle,
                artist = finalArtist,
                coverUrl = normalizedCoverUrl,
                streamUrl = normalizedStreamUrl,
                publishDate = publishDate,
                tags = tagsString,
                isFavorite = false,
                isAvailable = true,
                downloadedUri = null,
                language = "فارسی",
                sourceNumber = 3 // سورس ۳: گیتار موزیک
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Gitarmuzic song article: ${e.message}")
            return null
        }
    }

    private fun createNotification(message: String, currentCount: Int, progress: Int): Notification {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(appContext, WryaMusicApplication.SYNC_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("WRYA MUSIC Sync")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .build()
    }

    private fun updateNotification(message: String, syncedCount: Int) {
        val notification = createNotification(message, syncedCount, 0)
        try {
            val notificationManager =
                appContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification: ${e.message}")
        }
    }
}
