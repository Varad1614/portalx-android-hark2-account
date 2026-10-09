package com.pravahax.portalx.sync

import android.content.Context
import androidx.work.*
import com.pravahax.portalx.data.RepoHost
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Sends the offline outbox once the device has a network (v0.7). Unique, so at most one sender runs. */
class OutboxWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as? RepoHost)?.repo ?: return Result.failure()
        return try {
            if (repo.drainOutbox()) Result.retry() else Result.success()
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { Result.retry() }
    }

    companion object {
        const val NAME = "portalx-outbox"

        /** Never throws (WorkManager may be uninitialised in tests). */
        fun schedule(context: Context) {
            runCatching {
                val req = OneTimeWorkRequestBuilder<OutboxWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
            }
        }
    }
}
