package com.jiangshan.atlas.mcp

import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.Random
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简 WebSocket 客户端（RFC 6455，零依赖）。
 *
 * 所有 socket IO 都在自己的线程里做：
 *   - [connect] 内部起一个线程完成 TCP 连接与握手，结果通过 onOpen/onClose 回到主线程
 *   - [sendText] 只把帧放进发送队列，由写线程实际写出
 *   - 读线程循环解析帧，文本帧通过 onMessage 回到主线程
 */
class WebSocketClient(
    private val uri: URI,
    private val onMessage: (String) -> Unit,
    private val onOpen: () -> Unit,
    private val onClose: (String?) -> Unit,
) {

    private val socket = Socket()
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private val running = AtomicBoolean(false)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val sendQueue = LinkedBlockingQueue<ByteArray>()

    /** 异步连接；结果通过回调返回 */
    fun connect() {
        Thread({
            try {
                val host = uri.host ?: run { postClose("地址无效"); return@Thread }
                val port = if (uri.port > 0) uri.port else if (uri.scheme == "wss") 443 else 80

                socket.connect(InetSocketAddress(host, port), 8000)
                socket.tcpNoDelay = true

                val key = Base64.getEncoder()
                    .encodeToString(ByteArray(16).also { Random().nextBytes(it) })
                val path = (uri.path ?: "/").ifEmpty { "/" } + (uri.query?.let { "?$it" } ?: "")
                val request = buildString {
                    append("GET $path HTTP/1.1\r\n")
                    append("Host: $host\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $key\r\n")
                    append("Sec-WebSocket-Version: 13\r\n\r\n")
                }

                outputStream = BufferedOutputStream(socket.getOutputStream(), 8192)
                outputStream!!.write(request.toByteArray(Charsets.UTF_8))
                outputStream!!.flush()

                inputStream = BufferedInputStream(socket.getInputStream(), 8192)
                val head = readHandshake(inputStream!!)
                if (head == null) {
                    postClose("握手失败：没有收到响应")
                    return@Thread
                }
                if (!head.startsWith("HTTP/1.1 101") && !head.startsWith("HTTP/1.0 101")) {
                    postClose("握手失败：${head.lineSequence().firstOrNull() ?: ""}")
                    return@Thread
                }
                if (!head.contains(wsAccept(key))) {
                    postClose("握手失败：Sec-WebSocket-Accept 不匹配")
                    return@Thread
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "connect 失败", e)
                postClose("连接失败：${e.javaClass.simpleName} ${e.message ?: ""}")
                return@Thread
            }

            running.set(true)
            Thread({ writeLoop() }, "ws-writer").start()
            Thread({ readLoop() }, "ws-reader").start()
            mainHandler.post { onOpen() }
        }, "ws-connect").start()
    }

    fun isConnected() = running.get()

    fun sendText(text: String) {
        if (!running.get()) return
        sendQueue.offer(buildFrame(0x1, text.toByteArray(Charsets.UTF_8)))
    }

    fun sendClose() {
        if (!running.getAndSet(false)) return
        sendQueue.offer(buildFrame(0x8, ByteArray(0)))
        try { Thread.sleep(60) } catch (_: InterruptedException) {}
        try { socket.close() } catch (_: Exception) {}
    }

    private fun writeLoop() {
        val out = outputStream ?: return
        try {
            while (running.get()) {
                val frame = sendQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                out.write(frame)
                out.flush()
            }
        } catch (e: Exception) {
            if (running.get()) postClose("发送中断：${e.message}")
        }
    }

    private fun readLoop() {
        val input = inputStream ?: return
        try {
            while (running.get()) {
                val first = input.read()
                if (first < 0) break
                val second = input.read()
                if (second < 0) break

                val opcode = first and 0x0F
                val masked = (second and 0x80) != 0
                var length = (second and 0x7F).toLong()

                if (length == 126L) length = readUInt(input, 2)
                else if (length == 127L) length = readUInt(input, 8)

                if (masked) readUInt(input, 4) // 服务端正常不发掩码帧
                if (length < 0 || length > MAX_FRAME) {
                    postClose("帧过大：$length")
                    return
                }

                val payload = ByteArray(length.toInt())
                if (length > 0 && !readFully(input, payload)) break

                when (opcode) {
                    0x1 -> {
                        val text = String(payload, Charsets.UTF_8)
                        mainHandler.post { onMessage(text) }
                    }
                    0x8 -> { postClose(null); return }
                    0x9 -> sendQueue.offer(buildFrame(0xA, payload))
                    else -> Unit
                }
            }
            if (running.get()) postClose("连接已关闭")
        } catch (e: Exception) {
            if (running.get()) postClose("连接中断：${e.message}")
        }
    }

    private fun buildFrame(opcode: Int, payload: ByteArray): ByteArray {
        val mask = ByteArray(4).also { Random().nextBytes(it) }
        val length = payload.size

        val headerSize = when {
            length < 126 -> 2
            length <= 0xFFFF -> 4
            else -> 10
        }
        val header = ByteArray(headerSize)
        header[0] = (0x80 or opcode).toByte()
        when {
            length < 126 -> header[1] = (0x80 or length).toByte()
            length <= 0xFFFF -> {
                header[1] = (0x80 or 126).toByte()
                header[2] = ((length shr 8) and 0xFF).toByte()
                header[3] = (length and 0xFF).toByte()
            }
            else -> {
                header[1] = (0x80 or 127).toByte()
                var big = length.toLong()
                for (i in 9 downTo 2) {
                    header[i] = (big and 0xFF).toByte()
                    big = big shr 8
                }
            }
        }

        val masked = ByteArray(length)
        for (i in 0 until length) {
            masked[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        }
        return header + mask + masked
    }

    private fun readHandshake(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.toString()
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var got = 0
        while (got < buf.size) {
            val r = input.read(buf, got, buf.size - got)
            if (r < 0) return false
            got += r
        }
        return true
    }

    private fun readUInt(input: InputStream, bytes: Int): Long {
        var v = 0L
        repeat(bytes) {
            val b = input.read()
            if (b < 0) return v
            v = (v shl 8) or b.toLong()
        }
        return v
    }

    private fun wsAccept(key: String): String {
        val guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key + guid).toByteArray(Charsets.ISO_8859_1))
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun postClose(reason: String?) {
        running.set(false)
        try { socket.close() } catch (_: Exception) {}
        mainHandler.post { onClose(reason) }
    }

    companion object {
        private const val TAG = "JiangshanWS"
        /** 单帧上限，防止异常长度导致 OOM */
        private const val MAX_FRAME = 8L * 1024 * 1024
    }
}
