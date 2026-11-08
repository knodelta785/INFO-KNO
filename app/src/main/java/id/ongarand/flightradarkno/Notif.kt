package id.ongarand.flightradarkno

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

object Notif {
    const val CH = "flights"

    fun channel(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            c.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CH, "Info flight KNO", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    fun show(c: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true).build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun schedule(c: Context) {
        val req = PeriodicWorkRequestBuilder<FlightWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("kno-check", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class FlightWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val rules = Store.rules(ctx)
        if (rules.isEmpty()) return Result.success()
        val flights = try { Fr24.fetch(true) + Fr24.fetch(false) } catch (e: Exception) { return Result.retry() }
        val sp = Store.p(ctx)
        val now = System.currentTimeMillis() / 1000
        // key: nomor|jadwal|bucketDelay|batal  -> notifikasi ulang hanya jika status/delay berubah
        val seen = sp.getStringSet("seen", emptySet())!!
            .filter { (it.split("|").getOrNull(1)?.toLongOrNull() ?: 0L) > now - 86400 }.toMutableSet()
        flights.filter { it.sched >= now - 7200 }.forEach { f ->
            if (rules.any { it.matches(f) }) {
                val key = "${f.number}|${f.sched}|${if (f.alert) f.delayMin / 10 else 0}|${f.cancelled}"
                if (seen.add(key)) {
                    val dir = if (f.arrival) "dari" else "ke"
                    val info = when {
                        f.cancelled -> "DIBATALKAN"
                        f.delayed -> "DELAY +${f.delayMin} mnt, est ${Fmt.time(f.actual)}"
                        else -> f.status.ifEmpty { "Sesuai jadwal" }
                    }
                    Notif.show(ctx, key.hashCode(),
                        "${f.number} ${if (f.arrival) "Kedatangan" else "Keberangkatan"} KNO",
                        "$dir ${f.city} (${f.iata}) · jadwal ${Fmt.time(f.sched)} · $info")
                }
            }
        }
        sp.edit().putStringSet("seen", seen).apply()
        return Result.success()
    }
}
