package com.vslot.app

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.vslot.app.analytics.AppMetricaAnalyticsTracker
import com.vslot.app.analytics.AnalyticsConsentCoordinator
import com.vslot.app.analytics.AnalyticsRuntime
import com.vslot.app.analytics.LazyAnalyticsRuntime
import com.vslot.app.analytics.NoOpAnalyticsTracker
import com.vslot.app.analytics.SharedPreferencesAnalyticsRevocationGuard
import io.appmetrica.analytics.AppMetrica
import io.appmetrica.analytics.AppMetricaConfig
import io.appmetrica.analytics.push.AppMetricaPush
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun processObserverRetryDelayMs(attempt: Long): Long {
    val exponent = attempt.coerceIn(0L, PROCESS_OBSERVER_MAX_RETRY_EXPONENT.toLong()).toInt()
    return (PROCESS_OBSERVER_RETRY_BASE_DELAY_MS * (1L shl exponent))
        .coerceAtMost(PROCESS_OBSERVER_RETRY_MAX_DELAY_MS)
}

internal fun <T> Flow<T>.retryProcessObserverPersistence(
    onPersistenceFailure: suspend (IOException) -> Unit,
    retryDelay: suspend (Long) -> Unit = { delay(it) }
): Flow<T> = retryWhen { cause, attempt ->
    if (cause !is IOException) return@retryWhen false
    onPersistenceFailure(cause)
    retryDelay(processObserverRetryDelayMs(attempt))
    true
}

internal val PROCESS_PUSH_FAIL_CLOSED_COMMAND = PushRegistrationCommand(
    enabled = false,
    deleteToken = true
)

internal suspend fun observeAnalyticsConsentState(
    consentState: Flow<Boolean>,
    setAnalyticsEnabled: (Boolean) -> Unit,
    onPersistenceFailure: (IOException) -> Unit = {},
    retryDelay: suspend (Long) -> Unit = { delay(it) }
) {
    consentState
        .distinctUntilChanged()
        .retryProcessObserverPersistence(
            onPersistenceFailure = { error ->
                setAnalyticsEnabled(false)
                onPersistenceFailure(error)
            },
            retryDelay = retryDelay
        )
        .collect { enabled -> setAnalyticsEnabled(enabled) }
}

internal suspend fun observePushConsentState(
    consentState: Flow<Boolean>,
    reconcileConsent: suspend (Boolean) -> Unit,
    failClosed: suspend () -> Unit,
    onPersistenceFailure: (IOException) -> Unit = {},
    retryDelay: suspend (Long) -> Unit = { delay(it) }
) {
    consentState
        .distinctUntilChanged()
        .retryProcessObserverPersistence(
            onPersistenceFailure = { error ->
                failClosed()
                onPersistenceFailure(error)
            },
            retryDelay = retryDelay
        )
        .collect { permissionAsked -> reconcileConsent(permissionAsked) }
}

class VSlotApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pushRegistrationCoordinator = PushRegistrationCoordinator()
    private var appMetricaRuntime: LazyAnalyticsRuntime? = null
    internal val pushRegistrationStatus get() = pushRegistrationCoordinator.status

    override fun onCreate() {
        super.onCreate()
        val analyticsRuntime = createAnalyticsRuntime()
        val analyticsRevocationGuard = SharedPreferencesAnalyticsRevocationGuard(this)
        val analyticsConsentCoordinator = AnalyticsConsentCoordinator(
            analyticsRuntime,
            analyticsRevocationGuard
        )
        AppGraph.init(
            this,
            analyticsConsentCoordinator,
            analyticsConsentCoordinator,
            analyticsRevocationGuard
        )
        applicationScope.launch {
            observeAnalyticsConsentState(
                consentState = AppGraph.playerRepository.playerState
                    .map { state -> state.analyticsEnabled },
                setAnalyticsEnabled = { enabled ->
                    analyticsConsentCoordinator.setAnalyticsEnabled(enabled)
                },
                onPersistenceFailure = { error ->
                    if (BuildConfig.QA_ENABLED) {
                        Log.e("VSlotApplication", "Player state analytics observer failed; retrying", error)
                    }
                }
            )
        }
        applicationScope.launch {
            observePushConsentState(
                consentState = AppGraph.playerRepository.playerState
                    .map { state -> state.pushPermissionAsked },
                reconcileConsent = { permissionAsked ->
                    reconcilePushRegistration {
                        desiredPushRegistrationCommand(permissionAsked)
                    }
                },
                failClosed = {
                    reconcilePushRegistration { PROCESS_PUSH_FAIL_CLOSED_COMMAND }
                },
                onPersistenceFailure = { error ->
                    if (BuildConfig.QA_ENABLED) {
                        Log.e("VSlotApplication", "Player state push observer failed; retrying", error)
                    }
                }
            )
        }
        refreshPushRegistration()
    }

    private fun createAnalyticsRuntime(): AnalyticsRuntime {
        val apiKey = BuildConfig.APP_METRICA_API_KEY
        if (apiKey.isBlank()) {
            return NoOpAnalyticsTracker()
        }

        return LazyAnalyticsRuntime {
            activateAppMetricaRuntime(apiKey)
        }.also { runtime ->
            appMetricaRuntime = runtime
        }
    }

    private fun activateAppMetricaRuntime(apiKey: String): AnalyticsRuntime? {
        return runCatching {
            val config = AppMetricaConfig.newConfigBuilder(apiKey)
                .withDataSendingEnabled(false)
                .withAdvIdentifiersTracking(false)
                .withLocationTracking(false)
                .withCrashReporting(false)
                .withNativeCrashReporting(false)
                .withSessionsAutoTrackingEnabled(false)
                .withAppOpenTrackingEnabled(false)
                .withAnrMonitoring(false)
                .withRevenueAutoTrackingEnabled(false)
                .build()
            AppMetrica.activate(this, config)
            AppMetricaAnalyticsTracker(dataSendingEnabled = false)
        }.getOrElse { error ->
            if (BuildConfig.QA_ENABLED) {
                Log.e("VSlotApplication", "AppMetrica initialization failed", error)
            }
            null
        }
    }

    fun refreshPushRegistration() {
        applicationScope.launch {
            reconcilePushRegistration {
                val state = AppGraph.playerRepository.playerState.first()
                desiredPushRegistrationCommand(state.pushPermissionAsked)
            }
        }
    }

    private fun desiredPushRegistrationCommand(permissionAsked: Boolean): PushRegistrationCommand {
        val notificationsEnabled = areNotificationsDeliverable()
        val runtimePermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        return pushRegistrationCommand(
            permissionAsked = permissionAsked,
            notificationsEnabled = notificationsEnabled,
            runtimePermissionGranted = runtimePermissionGranted
        )
    }

    private suspend fun reconcilePushRegistration(
        desiredCommand: suspend () -> PushRegistrationCommand
    ) {
        if (!BuildConfig.FIREBASE_CONFIGURED || BuildConfig.APP_METRICA_API_KEY.isBlank()) {
            pushRegistrationCoordinator.markUnavailable()
            return
        }
        try {
            pushRegistrationCoordinator.reconcile(
                desiredCommand = desiredCommand,
                applyCommand = { command ->
                    if (!command.requiresRuntimeMutation) return@reconcile
                    val enabled = command.enabled
                    val firebaseMessaging = FirebaseMessaging.getInstance().apply {
                        // Never persist auto-init=true: consent recovery must remain fail-closed.
                        isAutoInitEnabled = false
                    }
                    if (enabled) {
                        check(appMetricaRuntime?.ensureActivatedForPush() == true) {
                            "AppMetrica core activation is required before push activation."
                        }
                        AppMetricaPush.activate(applicationContext)
                        firebaseMessaging.awaitLegacyRegistrationToken()
                    } else if (command.deleteToken) {
                        firebaseMessaging.deleteLegacyRegistrationToken()
                        FirebaseInstallations.getInstance().delete().awaitCompletion()
                    }
                }
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            if (BuildConfig.QA_ENABLED) {
                Log.e("VSlotApplication", "Push consent state update failed", error)
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun FirebaseMessaging.awaitLegacyRegistrationToken() {
        token.awaitStringCompletion()
    }

    @Suppress("DEPRECATION")
    private suspend fun FirebaseMessaging.deleteLegacyRegistrationToken() {
        deleteToken().awaitCompletion()
    }

    private suspend fun Task<Void>.awaitCompletion() {
        if (isComplete) {
            if (isSuccessful) return
            throw exception ?: IllegalStateException("Firebase task failed without an exception.")
        }
        suspendCancellableCoroutine { continuation ->
            addOnCompleteListener { completedTask ->
                if (!continuation.isActive) return@addOnCompleteListener
                if (completedTask.isSuccessful) {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(
                        completedTask.exception
                            ?: IllegalStateException("Firebase task failed without an exception.")
                    )
                }
            }
        }
    }

    private suspend fun Task<String>.awaitStringCompletion(): String {
        if (isComplete) {
            if (isSuccessful) return result
            throw exception ?: IllegalStateException("Firebase task failed without an exception.")
        }
        return suspendCancellableCoroutine { continuation ->
            addOnCompleteListener { completedTask ->
                if (!continuation.isActive) return@addOnCompleteListener
                if (completedTask.isSuccessful) {
                    continuation.resume(completedTask.result)
                } else {
                    continuation.resumeWithException(
                        completedTask.exception
                            ?: IllegalStateException("Firebase task failed without an exception.")
                    )
                }
            }
        }
    }

}

private const val PROCESS_OBSERVER_RETRY_BASE_DELAY_MS = 1_000L
private const val PROCESS_OBSERVER_RETRY_MAX_DELAY_MS = 30_000L
private const val PROCESS_OBSERVER_MAX_RETRY_EXPONENT = 5
