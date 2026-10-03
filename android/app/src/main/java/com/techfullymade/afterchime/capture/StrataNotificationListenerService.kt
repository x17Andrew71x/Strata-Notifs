package com.techfullymade.afterchime.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

class StrataNotificationListenerService : NotificationListenerService() {
  private val callbackExecutor = ThreadPoolExecutor(
    1,
    1,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue(CALLBACK_QUEUE_CAPACITY),
    ThreadPoolExecutor.DiscardPolicy(),
  )

  override fun onListenerConnected() {
    super.onListenerConnected()
    val repository = ObservationRuntimeRegistry.repository ?: return
    val occurredAtEpochMillis = System.currentTimeMillis()
    enqueue {
      repository.recordListenerConnected(occurredAtEpochMillis)
    }
  }

  override fun onListenerDisconnected() {
    val repository = ObservationRuntimeRegistry.repository
    val occurredAtEpochMillis = System.currentTimeMillis()
    val accessIsGranted = notificationAccessIsGranted()
    if (repository != null) {
      enqueue {
        if (accessIsGranted) {
          repository.recordListenerDisconnected(occurredAtEpochMillis)
        } else {
          repository.recordNotificationAccessRevoked(occurredAtEpochMillis)
        }
      }
    }
    super.onListenerDisconnected()
  }

  override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
    val runtime = NotificationCaptureRuntimeRegistry.runtime ?: return
    enqueue {
      runtime.reducer.reduce(statusBarNotification)?.let { reduced ->
        runtime.observationRepository.recordReducedNotification(reduced)
      }
    }
  }

  override fun onDestroy() {
    callbackExecutor.shutdown()
    super.onDestroy()
  }

  private fun enqueue(work: suspend () -> Unit) {
    callbackExecutor.execute {
      runBlocking {
        work()
      }
    }
  }

  private fun notificationAccessIsGranted(): Boolean =
    packageName in NotificationManagerCompat.getEnabledListenerPackages(this)

  private companion object {
    const val CALLBACK_QUEUE_CAPACITY = 64
  }
}

internal data class NotificationCaptureRuntime(
  val reducer: NotificationReducer,
  val observationRepository: ObservationRepository,
)

internal object ObservationRuntimeRegistry {
  @Volatile
  var repository: ObservationRepository? = null
}

internal object NotificationCaptureRuntimeRegistry {
  @Volatile
  var runtime: NotificationCaptureRuntime? = null
}
