package com.vslot.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PushRegistrationCoordinatorTest {
    @Test
    fun `firebase runtime remains untouched before the user answers the push prompt`() {
        listOf(false, true).forEach { notificationsEnabled ->
            listOf(false, true).forEach { runtimePermissionGranted ->
                val command = pushRegistrationCommand(
                    permissionAsked = false,
                    notificationsEnabled = notificationsEnabled,
                    runtimePermissionGranted = runtimePermissionGranted
                )

                assertEquals(
                    PushRegistrationCommand(enabled = false, deleteToken = false),
                    command
                )
                assertFalse(command.requiresRuntimeMutation)
            }
        }
    }

    @Test
    fun `answered prompt enables push only while every system gate is open`() {
        val enabled = pushRegistrationCommand(
            permissionAsked = true,
            notificationsEnabled = true,
            runtimePermissionGranted = true
        )
        val notificationsRevoked = pushRegistrationCommand(
            permissionAsked = true,
            notificationsEnabled = false,
            runtimePermissionGranted = true
        )
        val runtimePermissionDenied = pushRegistrationCommand(
            permissionAsked = true,
            notificationsEnabled = true,
            runtimePermissionGranted = false
        )

        assertEquals(PushRegistrationCommand(enabled = true, deleteToken = false), enabled)
        assertEquals(
            PushRegistrationCommand(enabled = false, deleteToken = true),
            notificationsRevoked
        )
        assertEquals(
            PushRegistrationCommand(enabled = false, deleteToken = true),
            runtimePermissionDenied
        )
        assertTrue(enabled.requiresRuntimeMutation)
        assertTrue(notificationsRevoked.requiresRuntimeMutation)
        assertTrue(runtimePermissionDenied.requiresRuntimeMutation)
    }

    @Test
    fun `failed command is retried and cached only after success`() = runTest {
        val coordinator = PushRegistrationCoordinator()
        val command = PushRegistrationCommand(enabled = true, deleteToken = false)
        var attempts = 0

        try {
            coordinator.reconcile(
                desiredCommand = { command },
                applyCommand = {
                    attempts += 1
                    throw IllegalStateException("transient push failure")
                }
            )
            fail("The first push registration attempt must fail.")
        } catch (_: IllegalStateException) {
            // Expected: failed work must not be remembered as applied.
        }
        assertEquals(PushRegistrationStatus.Failed, coordinator.status.value)

        coordinator.reconcile(
            desiredCommand = { command },
            applyCommand = { attempts += 1 }
        )
        coordinator.reconcile(
            desiredCommand = { command },
            applyCommand = { attempts += 1 }
        )

        assertEquals(2, attempts)
        assertEquals(PushRegistrationStatus.Registered, coordinator.status.value)
    }

    @Test
    fun `concurrent refreshes apply the same desired command once`() = runTest {
        val coordinator = PushRegistrationCoordinator()
        val command = PushRegistrationCommand(enabled = true, deleteToken = false)
        var applications = 0

        coroutineScope {
            List(24) {
                async {
                    coordinator.reconcile(
                        desiredCommand = { command },
                        applyCommand = { applications += 1 }
                    )
                }
            }.awaitAll()
        }

        assertEquals(1, applications)
        assertEquals(PushRegistrationStatus.Registered, coordinator.status.value)
    }

    @Test
    fun `queued refresh reads latest desired state after earlier work completes`() = runTest {
        val coordinator = PushRegistrationCoordinator()
        val disabled = PushRegistrationCommand(enabled = false, deleteToken = false)
        val enabled = PushRegistrationCommand(enabled = true, deleteToken = false)
        val firstApplyStarted = CompletableDeferred<Unit>()
        val releaseFirstApply = CompletableDeferred<Unit>()
        val applied = mutableListOf<PushRegistrationCommand>()
        var desired = disabled

        val first = async {
            coordinator.reconcile(
                desiredCommand = { desired },
                applyCommand = { command ->
                    applied += command
                    firstApplyStarted.complete(Unit)
                    releaseFirstApply.await()
                }
            )
        }
        firstApplyStarted.await()
        desired = enabled
        val second = async {
            coordinator.reconcile(
                desiredCommand = { desired },
                applyCommand = { command -> applied += command }
            )
        }

        releaseFirstApply.complete(Unit)
        awaitAll(first, second)

        assertEquals(listOf(disabled, enabled), applied)
        assertEquals(PushRegistrationStatus.Registered, coordinator.status.value)
    }

    @Test
    fun `token deletion is a distinct disabled command`() = runTest {
        val coordinator = PushRegistrationCoordinator()
        val disabled = PushRegistrationCommand(enabled = false, deleteToken = false)
        val disabledWithDeletion = PushRegistrationCommand(enabled = false, deleteToken = true)
        val applied = mutableListOf<PushRegistrationCommand>()

        coordinator.reconcile({ disabled }) { applied += it }
        coordinator.reconcile({ disabledWithDeletion }) { applied += it }
        coordinator.reconcile({ disabledWithDeletion }) { applied += it }

        assertEquals(listOf(disabled, disabledWithDeletion), applied)
        assertEquals(PushRegistrationStatus.Disabled, coordinator.status.value)
    }

    @Test
    fun `status distinguishes unavailable consent pending and registered runtime`() = runTest {
        val coordinator = PushRegistrationCoordinator()
        assertEquals(PushRegistrationStatus.Unavailable, coordinator.status.value)

        coordinator.reconcile(
            desiredCommand = {
                PushRegistrationCommand(enabled = false, deleteToken = false)
            },
            applyCommand = {}
        )
        assertEquals(PushRegistrationStatus.AwaitingPermission, coordinator.status.value)

        val releaseRegistration = CompletableDeferred<Unit>()
        val registration = async {
            coordinator.reconcile(
                desiredCommand = {
                    PushRegistrationCommand(enabled = true, deleteToken = false)
                },
                applyCommand = { releaseRegistration.await() }
            )
        }
        yield()
        assertEquals(PushRegistrationStatus.Registering, coordinator.status.value)
        releaseRegistration.complete(Unit)
        registration.await()
        assertEquals(PushRegistrationStatus.Registered, coordinator.status.value)

        coordinator.markUnavailable()
        assertEquals(PushRegistrationStatus.Unavailable, coordinator.status.value)
    }
}
