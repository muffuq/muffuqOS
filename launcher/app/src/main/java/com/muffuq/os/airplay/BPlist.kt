package com.muffuq.os.airplay

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Minimal Apple binary property list ("bplist00") codec — enough for the AirPlay control channel.
 *
 * Decoded values: Map<String, Any?>, List<Any?>, String, ByteArray, Long, Double, Boolean, null.
 * Integers wider than 64 bits (unsigned 64-bit values are sent as 128-bit ints) keep their low 64 bits,
 * so treat ids as unsigned with [java.lang.Long.toUnsignedString].
 */
object BPlist {
    private val MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)

    fun decode(bytes: ByteArray): Any? {
        require(bytes.size >= MAGIC.size + 32 && bytes.copyOfRange(0, 8).contentEquals(MAGIC)) { "not a bplist" }
        val trailer = ByteBuffer.wrap(bytes, bytes.size - 32, 32)
        trailer.position(bytes.size - 32 + 6)
        val offsetSize = trailer.get().toInt()
        val refSize = trailer.get().toInt()
        val count = trailer.long.toInt()
        val top = trailer.long.toInt()
        val tableOffset = trailer.long.toInt()
        val offsets = IntArray(count) { readUInt(bytes, tableOffset + it * offsetSize, offsetSize).toInt() }
        return Reader(bytes, offsets, refSize).read(top, 0)
    }

    private class Reader(val bytes: ByteArray, val offsets: IntArray, val refSize: Int) {
        fun read(index: Int, depth: Int): Any? {
            require(depth < 32) { "bplist nested too deeply" }
            var pos = offsets[index]
            val marker = bytes[pos].toInt() and 0xFF
            val type = marker shr 4
            val info = marker and 0x0F
            pos++

            fun length(): Int {
                if (info != 0x0F) return info
                val intMarker = bytes[pos].toInt() and 0xFF
                val size = 1 shl (intMarker and 0x0F)
                val value = readUInt(bytes, pos + 1, size).toInt()
                pos += 1 + size
                return value
            }

            return when (type) {
                0x0 -> when (info) {
                    0x8 -> false
                    0x9 -> true
                    else -> null
                }
                0x1 -> {
                    val size = 1 shl info
                    // 16-byte ints carry unsigned 64-bit values: keep the low 8 bytes.
                    // 1/2/4-byte ints are unsigned, 8-byte ones signed: reading into a Long gives exactly that.
                    if (size > 8) readUInt(bytes, pos + size - 8, 8) else readUInt(bytes, pos, size)
                }
                0x2 -> if (info == 2) ByteBuffer.wrap(bytes, pos, 4).float.toDouble() else ByteBuffer.wrap(bytes, pos, 8).double
                0x3 -> ByteBuffer.wrap(bytes, pos, 8).double
                0x4 -> {
                    val n = length()
                    bytes.copyOfRange(pos, pos + n)
                }
                0x5 -> {
                    val n = length()
                    String(bytes, pos, n, Charsets.US_ASCII)
                }
                0x6 -> {
                    val n = length()
                    String(bytes, pos, n * 2, Charsets.UTF_16BE)
                }
                0x8 -> readUInt(bytes, pos, info + 1)
                0xA -> {
                    val n = length()
                    List(n) { read(ref(pos + it * refSize), depth + 1) }
                }
                0xD -> {
                    val n = length()
                    val map = LinkedHashMap<String, Any?>(n)
                    for (i in 0 until n) {
                        val key = read(ref(pos + i * refSize), depth + 1).toString()
                        map[key] = read(ref(pos + (n + i) * refSize), depth + 1)
                    }
                    map
                }
                else -> null
            }
        }

        private fun ref(at: Int) = readUInt(bytes, at, refSize).toInt()
    }

    fun encode(root: Any?): ByteArray {
        val objects = ArrayList<Any?>()
        val children = ArrayList<IntArray?>()
        flatten(root, objects, children)
        val refSize = if (objects.size < 256) 1 else if (objects.size < 65536) 2 else 4

        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        val offsets = IntArray(objects.size)
        for ((i, obj) in objects.withIndex()) {
            offsets[i] = out.size()
            writeObject(out, obj, children[i], refSize)
        }
        val tableOffset = out.size()
        val offsetSize = bytesNeeded(tableOffset.toLong())
        for (offset in offsets) writeUInt(out, offset.toLong(), offsetSize)

        val trailer = ByteBuffer.allocate(32)
        trailer.position(6)
        trailer.put(offsetSize.toByte()).put(refSize.toByte())
        trailer.putLong(objects.size.toLong()).putLong(0).putLong(tableOffset.toLong())
        out.write(trailer.array())
        return out.toByteArray()
    }

    /** Depth-first: assigns every value an index and records the child indices of containers. */
    private fun flatten(value: Any?, objects: MutableList<Any?>, children: MutableList<IntArray?>): Int {
        val index = objects.size
        objects += value
        children += null
        when (value) {
            is Map<*, *> -> {
                val refs = IntArray(value.size * 2)
                var i = 0
                for (key in value.keys) refs[i++] = flatten(key.toString(), objects, children)
                for (v in value.values) refs[i++] = flatten(v, objects, children)
                children[index] = refs
            }
            is List<*> -> children[index] = IntArray(value.size) { flatten(value[it], objects, children) }
        }
        return index
    }

    private fun writeObject(out: ByteArrayOutputStream, value: Any?, refs: IntArray?, refSize: Int) {
        when (value) {
            null -> out.write(0x00)
            is Boolean -> out.write(if (value) 0x09 else 0x08)
            is Int, is Long, is Short, is Byte -> writeInt(out, (value as Number).toLong())
            is Float, is Double -> {
                out.write(0x23)
                out.write(ByteBuffer.allocate(8).putDouble((value as Number).toDouble()).array())
            }
            is ByteArray -> {
                writeHeader(out, 0x4, value.size)
                out.write(value)
            }
            is String -> {
                if (value.all { it.code < 128 }) {
                    writeHeader(out, 0x5, value.length)
                    out.write(value.toByteArray(Charsets.US_ASCII))
                } else {
                    writeHeader(out, 0x6, value.length)
                    out.write(value.toByteArray(Charsets.UTF_16BE))
                }
            }
            is Map<*, *> -> {
                writeHeader(out, 0xD, value.size)
                for (ref in refs!!) writeUInt(out, ref.toLong(), refSize)
            }
            is List<*> -> {
                writeHeader(out, 0xA, value.size)
                for (ref in refs!!) writeUInt(out, ref.toLong(), refSize)
            }
            else -> error("unsupported plist value ${value::class.java}")
        }
    }

    private fun writeHeader(out: ByteArrayOutputStream, type: Int, length: Int) {
        if (length < 15) {
            out.write((type shl 4) or length)
        } else {
            out.write((type shl 4) or 0x0F)
            writeInt(out, length.toLong())
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Long) {
        val size = if (value < 0) 8 else bytesNeeded(value)
        out.write(0x10 or Integer.numberOfTrailingZeros(size))
        writeUInt(out, value, size)
    }

    private fun bytesNeeded(value: Long): Int = when {
        value < 0x100 -> 1
        value < 0x10000 -> 2
        value < 0x100000000L -> 4
        else -> 8
    }

    private fun writeUInt(out: ByteArrayOutputStream, value: Long, size: Int) {
        for (i in size - 1 downTo 0) out.write((value ushr (8 * i)).toInt() and 0xFF)
    }

    private fun readUInt(bytes: ByteArray, at: Int, size: Int): Long {
        var v = 0L
        for (i in 0 until size) v = (v shl 8) or (bytes[at + i].toLong() and 0xFF)
        return v
    }
}
