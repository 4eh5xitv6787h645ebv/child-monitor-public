/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package de.rochefort.childmonitor

import android.content.Context
import de.rochefort.childmonitor.parent.PinnedTls
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertificateException
import javax.net.ssl.HttpsURLConnection

/**
 * Shared parent-side pinned-HTTP connector.
 *
 * [#231] Both the inline snapshot loop (ListenActivity) and the fullscreen view
 * (FullscreenCameraActivity) must open connections through the SAME certificate-pinning
 * logic. Duplicating it risks drift where one path silently trusts the platform CA set
 * (MITM). The bearer token is attached by the caller via [ParentSessionStore].
 */
object PinnedHttp {

    /**
     * Open a connection to [rawUrl] for child [deviceId]. When the URL is HTTPS and a TLS
     * fingerprint is on record, the connection is pinned to it; when HTTPS is requested but
     * no pin is stored, this fails closed with [CertificateException] rather than trusting
     * the platform trust store. Plain HTTP URLs (only used for never-pinned devices, per
     * the #203 transport policy) are returned unpinned.
     */
    fun openPinned(context: Context, rawUrl: String, deviceId: String?): HttpURLConnection {
        val conn = URL(rawUrl).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            val pin = ParentSessionStore.fingerprintFor(context, deviceId)
                ?: throw CertificateException("HTTPS requested but no TLS pin stored — re-pair required")
            PinnedTls.apply(conn, pin)
        }
        return conn
    }
}
