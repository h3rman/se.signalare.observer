/*
 * Copyright 2025 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.service.interceptor

import Logger
import com.celzero.bravedns.service.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * Lightweight HTTP forward-proxy that intercepts HTTP POST requests destined for
 * configured target hosts and modifies the request body before forwarding.
 *
 * Architecture:
 *   Go firestack (HTTP proxy client)
 *     → 127.0.0.1:[proxyPort]  (this class)
 *     → real destination server  (protected socket, bypasses VPN tunnel)
 *
 * The Go firestack backend routes matching TCP connections here by returning
 * ProxyManager.ID_HTTP_INTERCEPT in the flow() Mark.  It then speaks the
 * standard HTTP forward-proxy protocol, i.e. it sends:
 *
 *   POST http://servername/path HTTP/1.1
 *   Host: servername
 *   Content-Length: N
 *   ...
 *   <body>
 *
 * This proxy:
 *   1. Reads the full HTTP request (headers + body).
 *   2. Modifies the POST body according to InterceptorConfig rules.
 *   3. Connects to the real server using a VPN-protected socket.
 *   4. Forwards the modified request (with updated Content-Length).
 *   5. Pipes the response back to the caller.
 *
 * Safety:
 *   - VpnController.protectSocket() is called before connecting outward,
 *     preventing the outgoing socket from re-entering the VPN tunnel.
 *   - All sockets have explicit read/connect timeouts.
 *   - Each connection is handled in its own coroutine under a SupervisorJob
 *     so individual failures do not crash the accept loop.
 */
class HttpPostInterceptProxy {

    companion object {
        private const val TAG = "HttpInterceptProxy"

        private const val CONNECT_TIMEOUT_MS = 30_000   // 30 s
        private const val READ_TIMEOUT_MS    = 60_000   // 60 s
        private const val ACCEPT_TIMEOUT_MS  = 1_000    //  1 s (lets accept() be interruptible)

        private const val MAX_HEADER_BYTES   = 64 * 1024  // 64 KiB
        private const val MAX_BODY_BYTES     = 10 * 1024 * 1024  // 10 MiB
        private const val RELAY_BUFFER_SIZE  = 8 * 1024  //  8 KiB
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun start() {
        if (serverSocket?.isClosed == false) {
            Logger.w(TAG, "Proxy already running on port ${InterceptorConfig.proxyPort}")
            return
        }
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.soTimeout = ACCEPT_TIMEOUT_MS
            ss.bind(InetSocketAddress("127.0.0.1", InterceptorConfig.proxyPort))
            serverSocket = ss
            Logger.i(TAG, "Proxy listening on 127.0.0.1:${InterceptorConfig.proxyPort}")
            acceptJob = scope.launch { acceptLoop(ss) }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to start proxy: ${e.message}", e)
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: IOException) {}
        serverSocket = null
        Logger.i(TAG, "Proxy stopped")
    }

    fun isRunning(): Boolean = serverSocket?.isClosed == false

    // -------------------------------------------------------------------------
    // Accept loop
    // -------------------------------------------------------------------------

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (isActive && !ss.isClosed) {
            try {
                val client = ss.accept()
                scope.launch { handleConnection(client) }
            } catch (_: SocketTimeoutException) {
                // soTimeout expired – check isActive and loop
            } catch (e: IOException) {
                if (!isActive) break
                Logger.w(TAG, "Accept error: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Per-connection handler
    // -------------------------------------------------------------------------

    private fun handleConnection(clientSock: Socket) {
        clientSock.use { cs ->
            try {
                cs.soTimeout = READ_TIMEOUT_MS
                val input  = cs.inputStream
                val output = cs.outputStream

                val (requestLine, headers, bodyBytes) = parseHttpRequest(input) ?: run {
                    Logger.w(TAG, "Failed to parse HTTP request, closing connection")
                    return
                }

                val (method, rawUrl, httpVersion) = requestLine

                // Resolve target host:port from the absolute URL supplied by the proxy client
                val uri  = URI(rawUrl)
                val host = uri.host ?: run {
                    Logger.w(TAG, "Cannot resolve host from URL: $rawUrl")
                    return
                }
                val port = if (uri.port > 0) uri.port else 80
                val path = buildRelativePath(uri)

                // Decide whether to modify the body
                val contentType = headers["content-type"]
                val maybeModified: ByteArray
                val newContentLength: Int

                if (method.equals("POST", ignoreCase = true) && bodyBytes.isNotEmpty()) {
                    val result = PostBodyModifier.modify(bodyBytes, contentType, InterceptorConfig.postModificationRules)
                    maybeModified    = result.body
                    newContentLength = result.contentLength
                } else {
                    maybeModified    = bodyBytes
                    newContentLength = bodyBytes.size
                }

                // Create outgoing socket, PROTECT it so it exits via the physical network
                val outSock = Socket()
                VpnController.protectSocket(outSock)    // ← loop-prevention critical path

                outSock.use { os ->
                    os.soTimeout = READ_TIMEOUT_MS
                    os.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

                    val outStream = os.outputStream
                    sendRequest(outStream, method, path, httpVersion, headers, maybeModified, newContentLength)

                    // Relay response back to the Go proxy client
                    relay(os.inputStream, output)
                }

                Logger.d(TAG, "Handled $method $rawUrl → $host:$port (body ${bodyBytes.size}→${newContentLength}B)")

            } catch (e: SocketTimeoutException) {
                Logger.w(TAG, "Socket timeout: ${e.message}")
            } catch (e: IOException) {
                Logger.w(TAG, "IO error: ${e.message}")
            } catch (e: Exception) {
                Logger.e(TAG, "Unexpected error: ${e.message}", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // HTTP parsing
    // -------------------------------------------------------------------------

    /**
     * Reads the HTTP request from [input].
     * Returns a Triple of (requestLine, headers map, body bytes), or null on parse failure.
     *
     * Body is only read when Content-Length is present; chunked transfer-encoding is NOT
     * supported (uncommon for plain HTTP/1.1 POST to legacy servers).
     */
    private fun parseHttpRequest(input: InputStream): Triple<Triple<String, String, String>, Map<String, String>, ByteArray>? {
        // Read until "\r\n\r\n" (header terminator), cap at MAX_HEADER_BYTES
        val headerBuffer = readUntilDoubleNewline(input) ?: return null
        val headerText   = String(headerBuffer, StandardCharsets.ISO_8859_1)
        val lines        = headerText.split("\r\n")

        if (lines.isEmpty()) return null
        val requestLineParts = lines[0].split(" ", limit = 3)
        if (requestLineParts.size < 3) {
            Logger.w(TAG, "Malformed request line: '${lines[0]}'")
            return null
        }
        val method      = requestLineParts[0]
        val rawUrl      = requestLineParts[1]
        val httpVersion = requestLineParts[2]

        // Parse headers (case-insensitive, lowercase keys)
        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val k = line.substring(0, idx).trim().lowercase()
                val v = line.substring(idx + 1).trim()
                headers[k] = v
            }
        }

        // Read body using Content-Length
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            if (contentLength > MAX_BODY_BYTES) {
                Logger.w(TAG, "Content-Length $contentLength exceeds max, capping at $MAX_BODY_BYTES")
            }
            val toRead = minOf(contentLength, MAX_BODY_BYTES)
            readExact(input, toRead) ?: ByteArray(0)
        } else {
            ByteArray(0)
        }

        return Triple(Triple(method, rawUrl, httpVersion), headers, body)
    }

    /**
     * Reads bytes from [input] until "\r\n\r\n" is seen, returning everything
     * BEFORE the terminator.  Returns null on EOF or if cap exceeded.
     */
    private fun readUntilDoubleNewline(input: InputStream): ByteArray? {
        val buf = ByteArray(MAX_HEADER_BYTES)
        var pos = 0
        var prev3 = -1; var prev2 = -1; var prev1 = -1

        while (pos < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null  // unexpected EOF
            buf[pos++] = b.toByte()
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                // Found \r\n\r\n – return everything up to (but not including) the double CRLF
                return buf.copyOf(pos - 4)
            }
            prev3 = prev2; prev2 = prev1; prev1 = b
        }
        Logger.w(TAG, "Header section exceeds $MAX_HEADER_BYTES bytes")
        return null
    }

    /** Reads exactly [n] bytes from [input], returns null if EOF occurs before that. */
    private fun readExact(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = input.read(buf, offset, n - offset)
            if (read < 0) return null
            offset += read
        }
        return buf
    }

    // -------------------------------------------------------------------------
    // HTTP writing
    // -------------------------------------------------------------------------

    private fun sendRequest(
        out: OutputStream,
        method: String,
        path: String,
        httpVersion: String,
        headers: Map<String, String>,
        body: ByteArray,
        contentLength: Int
    ) {
        val sb = StringBuilder()
        sb.append("$method $path $httpVersion\r\n")
        for ((k, v) in headers) {
            when (k.lowercase()) {
                "content-length" -> continue   // will be rewritten below
                "proxy-connection" -> continue  // hop-by-hop, not forwarded
                "proxy-authorization" -> continue
                else -> sb.append("$k: $v\r\n")
            }
        }
        if (body.isNotEmpty()) {
            sb.append("content-length: $contentLength\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
        if (body.isNotEmpty()) {
            out.write(body)
        }
        out.flush()
    }

    // -------------------------------------------------------------------------
    // Response relay
    // -------------------------------------------------------------------------

    /** Copies all bytes from [src] to [dst] until EOF. */
    private fun relay(src: InputStream, dst: OutputStream) {
        val buf = ByteArray(RELAY_BUFFER_SIZE)
        var n: Int
        while (src.read(buf).also { n = it } >= 0) {
            dst.write(buf, 0, n)
        }
        dst.flush()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildRelativePath(uri: URI): String {
        val path  = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath
        val query = uri.rawQuery
        return if (query != null) "$path?$query" else path
    }
}
