/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package de.rochefort.childmonitor

import android.annotation.TargetApi
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.InetAddress
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** One-shot NSD resolution with the Android 14 callback API and an Android 5 fallback. */
internal object NsdResolver {
    fun resolve(
        manager: NsdManager,
        service: NsdServiceInfo,
        executor: Executor,
        onResolved: (NsdServiceInfo) -> Unit,
        onFailure: (Int) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveModern(manager, service, executor, onResolved, onFailure)
        } else {
            resolveLegacy(manager, service, onResolved, onFailure)
        }
    }

    fun addresses(service: NsdServiceInfo): List<InetAddress> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.hostAddresses.orEmpty()
        } else {
            legacyAddresses(service)
        }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun resolveModern(
        manager: NsdManager,
        service: NsdServiceInfo,
        executor: Executor,
        onResolved: (NsdServiceInfo) -> Unit,
        onFailure: (Int) -> Unit,
    ) {
        val completed = AtomicBoolean(false)
        lateinit var callback: NsdManager.ServiceInfoCallback
        callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                if (!completed.compareAndSet(false, true)) return
                runCatching { manager.unregisterServiceInfoCallback(callback) }
                onResolved(serviceInfo)
            }

            override fun onServiceLost() {
                if (completed.compareAndSet(false, true)) onFailure(SERVICE_LOST)
            }

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                if (completed.compareAndSet(false, true)) onFailure(errorCode)
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        manager.registerServiceInfoCallback(service, executor, callback)
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacy(
        manager: NsdManager,
        service: NsdServiceInfo,
        onResolved: (NsdServiceInfo) -> Unit,
        onFailure: (Int) -> Unit,
    ) {
        manager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) =
                onFailure(errorCode)

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) =
                onResolved(serviceInfo)
        })
    }

    @Suppress("DEPRECATION")
    private fun legacyAddresses(service: NsdServiceInfo): List<InetAddress> =
        listOfNotNull(service.host)

    private const val SERVICE_LOST = -1
}
