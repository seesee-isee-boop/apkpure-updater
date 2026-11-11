package fr.caperuisseau.apkpureupdater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Vérification périodique en arrière-plan + notification si des MAJ sont dispo. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val store = Store(applicationContext)
        val results = store.watched.sorted().mapIndexed { i, pkg ->
            if (i > 0) Thread.sleep(1000) // 1 s entre requêtes, soyons polis
            checkPackage(applicationContext, pkg)
        }
        // Tout a échoué (réseau coupé, API en panne) : on laisse WorkManager réessayer plus tard.
        if (results.isNotEmpty() && results.all { it.status == Status.ERROR }) return Result.retry()

        val fresh = results
            .filter { it.status == Status.UPDATE && it.latest?.versionName != store.lastNotified(it.pkg) }

        if (fresh.isNotEmpty()) {
            val text = fresh.joinToString("\n") { "${it.pkg} → ${it.latest?.versionName ?: "?"}" }
            notify(applicationContext, "${fresh.size} mise(s) à jour sur APKPure", text)
            fresh.forEach { r -> r.latest?.versionName?.let { store.setLastNotified(r.pkg, it) } }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "apkpure-periodic-check"
        const val CHANNEL_ID = "updates"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mises à jour", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        fun notify(context: Context, title: String, text: String) {
            // Pas checkSelfPermission(POST_NOTIFICATIONS) : cette permission n'existe pas avant
            // Android 13 et le test renvoie alors toujours "refusé".
            val nm = context.getSystemService(NotificationManager::class.java)
            if (!nm.areNotificationsEnabled()) return
            ensureChannel(context)
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_update)
                .setColor(context.getColor(R.color.launcher_bg))
                .setContentTitle(title)
                .setContentText(text.lineSequence().first())
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(1, n)
        }
    }
}
