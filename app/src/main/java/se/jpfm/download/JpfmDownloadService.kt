package se.jpfm.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import se.jpfm.JpfmApp
import se.jpfm.MainActivity
import se.jpfm.R

/** Laddar ner i bakgrunden med en notis som visar hur det går. */
class JpfmDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.downloads_channel,
    R.string.downloads_channel_description,
) {
    private val helper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = (application as JpfmApp).graph.downloads.manager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val active = downloads.count { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        val message = when {
            notMetRequirements != 0 -> "Väntar på wifi"
            active > 0 -> "$active låtar kvar"
            else -> null
        }
        return helper.buildProgressNotification(this, R.drawable.ic_notification, open, message, downloads, notMetRequirements)
    }

    companion object {
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "downloads"
    }
}
