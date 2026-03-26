package com.ai.assistance.operit.services

import com.ai.assistance.operit.util.AppLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PBBP2 协议编解码器
 *
 * 飞书 WebSocket 使用 PBBP2 协议，基于 protobuf 编码
 */
object Pbbp2Codec {

    private const val TAG = "Pbbp2Codec"

    // 帧类型
    const val FRAME_TYPE_CONTROL = 1
    const val FRAME_TYPE_DATA = 2

    // 消息类型（Header key = "type"）
    const val MESSAGE_TYPE_PING = "ping"
    const val MESSAGE_TYPE_PONG = "pong"
    const val MESSAGE_TYPE_EVENT = "event"

    // Header Key
    const val HEADER_KEY_TYPE = "type"

    /**
     * PBBP2 帧
     */
    data class Frame(
        val headers: List<Header> = emptyList(),
        val service: Int = 0,
        val method: Int = 0,
        val seqId: Int = 0,
        val logId: Int = 0,
        val payload: ByteArray? = null
    ) {
        fun getHeaderValue(key: String): String? {
            return headers.find { it.key == key }?.value
        }
    }

    /**
     * PBBP2 Header
     */
    data class Header(
        val key: String,
        val value: String
    )

    /**
     * 编码帧为字节数组
     *
     * PBBP2 帧结构：
     * - Field 1: service (varint)
     * - Field 2: method (varint)
     * - Field 3: seqId (varint)
     * - Field 4: logId (varint)
     * - Field 5: headers (repeated nested message)
     * - Field 6: payload (bytes)
     */
    fun encode(frame: Frame): ByteArray {
        val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB buffer
        buffer.order(ByteOrder.BIG_ENDIAN)

        // 编码 service (field 1)
        encodeVarint(buffer, makeTag(1, WIRETYPE_VARINT), frame.service)

        // 编码 method (field 2)
        encodeVarint(buffer, makeTag(2, WIRETYPE_VARINT), frame.method)

        // 编码 seqId (field 3)
        encodeVarint(buffer, makeTag(3, WIRETYPE_VARINT), frame.seqId)

        // 编码 logId (field 4)
        encodeVarint(buffer, makeTag(4, WIRETYPE_VARINT), frame.logId)

        // 编码 headers (field 5)
        for (header in frame.headers) {
            encodeHeader(buffer, 5, header)
        }

        // 编码 payload (field 6)
        if (frame.payload != null && frame.payload.isNotEmpty()) {
            encodeBytes(buffer, 6, frame.payload)
        }

        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }

    /**
     * 解码字节数组为帧
     *
     * PBBP2 帧结构：
     * - Field 1: service (varint)
     * - Field 2: method (varint)
     * - Field 3: seqId (varint)
     * - Field 4: logId (varint)
     * - Field 5: headers (repeated nested message)
     * - Field 6: payload (bytes)
     * - Field 7+: 可能包含事件数据
     */
    fun decode(data: ByteArray): Frame? {
        return try {
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.BIG_ENDIAN)

            val headers = mutableListOf<Header>()
            var service = 0
            var method = 0
            var seqId = 0
            var logId = 0
            var payload: ByteArray? = null

            while (buffer.hasRemaining()) {
                val tag = decodeVarint(buffer)
                if (tag == 0) break

                val fieldNumber = tag shr 3
                val wireType = tag and 0x7

                try {
                    when (fieldNumber) {
                        1 -> service = decodeVarint(buffer)
                        2 -> method = decodeVarint(buffer)
                        3 -> seqId = decodeVarint(buffer)
                        4 -> logId = decodeVarint(buffer)
                        5 -> {
                            val header = decodeHeader(buffer, wireType)
                            if (header != null) {
                                headers.add(header)
                            }
                        }
                        6 -> payload = decodeBytes(buffer, wireType)
                        // 字段 7, 8 可能包含事件体数据
                        7, 8 -> {
                            val extraData = decodeBytes(buffer, wireType)
                            if (extraData.isNotEmpty()) {
                                AppLogger.d(TAG, "Field $fieldNumber 数据: ${extraData.size} 字节")
                                // 如果 payload 为空，把第一个非空字段作为 payload
                                if (payload == null || payload.isEmpty()) {
                                    payload = extraData
                                    AppLogger.d(TAG, "使用 field $fieldNumber 作为 payload")
                                }
                            }
                        }
                        else -> skipField(buffer, wireType)
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "字段 $fieldNumber 解码异常: ${e.message}")
                    break
                }
            }

            Frame(headers, service, method, seqId, logId, payload)
        } catch (e: Exception) {
            AppLogger.e(TAG, "解码帧失败", e)
            null
        }
    }

    /**
     * 创建心跳帧
     */
    fun createPingFrame(serviceId: Int): ByteArray {
        val frame = Frame(
            headers = listOf(Header(HEADER_KEY_TYPE, MESSAGE_TYPE_PING)),
            service = serviceId,
            method = FRAME_TYPE_CONTROL,
            seqId = 0,
            logId = 0
        )
        return encode(frame)
    }

    // ========== 私有方法 ==========

    private fun encodeHeader(buffer: ByteBuffer, fieldNumber: Int, header: Header) {
        // 计算嵌套消息大小
        val headerBuffer = ByteBuffer.allocate(256)
        headerBuffer.order(ByteOrder.BIG_ENDIAN)

        // 编码 key (field 1)
        encodeString(headerBuffer, 1, header.key)

        // 编码 value (field 2)
        encodeString(headerBuffer, 2, header.value)

        val headerData = ByteArray(headerBuffer.position())
        headerBuffer.rewind()
        headerBuffer.get(headerData)

        // 写入 tag 和长度
        encodeVarint(buffer, makeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED), headerData.size)
        buffer.put(headerData)
    }

    private fun decodeHeader(buffer: ByteBuffer, wireType: Int): Header? {
        return try {
            if (wireType != WIRETYPE_LENGTH_DELIMITED) return null

            val length = decodeVarint(buffer)
            if (length <= 0 || length > 10000) return null

            val headerData = ByteArray(length)
            buffer.get(headerData)

            val headerBuffer = ByteBuffer.wrap(headerData)
            headerBuffer.order(ByteOrder.BIG_ENDIAN)

            var key = ""
            var value = ""

            while (headerBuffer.hasRemaining()) {
                val innerTag = decodeVarint(headerBuffer)
                if (innerTag == 0) break

                val innerField = innerTag shr 3
                val innerWire = innerTag and 0x7

                when (innerField) {
                    1 -> key = decodeString(headerBuffer, innerWire)
                    2 -> value = decodeString(headerBuffer, innerWire)
                    else -> skipField(headerBuffer, innerWire)
                }
            }

            if (key.isNotEmpty()) Header(key, value) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun encodeString(buffer: ByteBuffer, fieldNumber: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        encodeVarint(buffer, makeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED), bytes.size)
        buffer.put(bytes)
    }

    private fun decodeString(buffer: ByteBuffer, wireType: Int): String {
        val length = if (wireType == WIRETYPE_LENGTH_DELIMITED) {
            decodeVarint(buffer)
        } else {
            return ""
        }

        val bytes = ByteArray(length)
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun encodeBytes(buffer: ByteBuffer, fieldNumber: Int, value: ByteArray) {
        encodeVarint(buffer, makeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED), value.size)
        buffer.put(value)
    }

    private fun decodeBytes(buffer: ByteBuffer, wireType: Int): ByteArray {
        val length = if (wireType == WIRETYPE_LENGTH_DELIMITED) {
            decodeVarint(buffer)
        } else {
            return ByteArray(0)
        }

        val bytes = ByteArray(length)
        buffer.get(bytes)
        return bytes
    }

    private fun encodeVarint(buffer: ByteBuffer, tag: Int, value: Int) {
        encodeVarint(buffer, tag)
        encodeVarint(buffer, value)
    }

    private fun encodeVarint(buffer: ByteBuffer, value: Int) {
        var v = value
        while (v and 0x80 != 0) {
            buffer.put((v and 0x7F or 0x80).toByte())
            v = v ushr 7
        }
        buffer.put(v.toByte())
    }

    private fun decodeVarint(buffer: ByteBuffer): Int {
        var result = 0
        var shift = 0

        while (buffer.hasRemaining()) {
            val b = buffer.get().toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)

            if (b and 0x80 == 0) {
                break
            }
            shift += 7

            if (shift >= 32) {
                break
            }
        }

        return result
    }

    private fun skipField(buffer: ByteBuffer, wireType: Int) {
        try {
            when (wireType) {
                WIRETYPE_VARINT -> decodeVarint(buffer)
                WIRETYPE_FIXED64 -> {
                    if (buffer.remaining() >= 8) {
                        buffer.position(buffer.position() + 8)
                    }
                }
                WIRETYPE_LENGTH_DELIMITED -> {
                    val length = decodeVarint(buffer)
                    if (length > 0 && buffer.remaining() >= length) {
                        buffer.position(buffer.position() + length)
                    }
                }
                WIRETYPE_FIXED32 -> {
                    if (buffer.remaining() >= 4) {
                        buffer.position(buffer.position() + 4)
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略越界错误
        }
    }

    private fun makeTag(fieldNumber: Int, wireType: Int): Int {
        return (fieldNumber shl 3) or wireType
    }

    // Wire types
    private const val WIRETYPE_VARINT = 0
    private const val WIRETYPE_FIXED64 = 1
    private const val WIRETYPE_LENGTH_DELIMITED = 2
    private const val WIRETYPE_FIXED32 = 5
}