package com.cheems.clash

import com.google.protobuf.CodedInputStream
import java.io.InputStream

// Android debuggerd/proto/tombstone.proto. Skip registers, memory and logs.
internal object NativeFrames {
    private inline fun CodedInputStream.nested(block: () -> Unit) {
        val limit = pushLimit(readRawVarint32())
        try { block() } finally { popLimit(limit) }
    }

    fun read(stream: InputStream): String {
        val input = CodedInputStream.newInstance(stream)
        input.setSizeLimit(16 * 1024 * 1024)
        var tid = -1
        val threads = mutableMapOf<Int, List<String>>()
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                48 -> tid = input.readUInt32()
                130 -> input.nested {
                    var key = -1
                    val frames = mutableListOf<String>()
                    while (!input.isAtEnd) {
                        when (val field = input.readTag()) {
                            8 -> key = input.readUInt32()
                            18 -> input.nested {
                                while (!input.isAtEnd) {
                                    val threadField = input.readTag()
                                    if (threadField == 34 && frames.size < 12) {
                                        frames.add(frame(input))
                                    } else input.skipField(threadField)
                                }
                            }
                            else -> input.skipField(field)
                        }
                    }
                    threads[key] = frames
                }
                else -> input.skipField(tag)
            }
        }
        return threads[tid].orEmpty().joinToString("\n")
    }

    private fun frame(input: CodedInputStream): String {
        var pc = 0L
        var symbol = ""
        var library = "?"
        input.nested {
            while (!input.isAtEnd) {
                when (val field = input.readTag()) {
                    8 -> pc = input.readUInt64()
                    34 -> symbol = input.readString().take(100)
                    50 -> library = input.readString().substringAfterLast('/').take(80)
                    else -> input.skipField(field)
                }
            }
        }
        return "$library +0x${pc.toString(16)} $symbol".trimEnd()
    }
}
