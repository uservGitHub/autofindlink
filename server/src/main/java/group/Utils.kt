package group

/**
 * 整数大端格式转换成byte数组，不要截断负数（截断时，保留下来的是低字节，且带符号移位）
 * @param byte数量（推荐值：1,2,4）
 */
inline fun Int.toByteArray(byteCount:Int = 4):ByteArray {
    val target = ByteArray(byteCount)
    val lastIndex = Math.min(4, byteCount) - 1
    var index = lastIndex
    while (index >= 0) {
        target[index] = (this.shr(8 * (lastIndex - index)) and 0xff).toByte()
        index--
    }
    return target
}

/**
 * byte数组中大端格式取整数（不要截断负数）
 * @param offset 在数组中的偏移量
 * @param byteCoun 取字节长度
 */
inline fun ByteArray.toInt(offset: Int, byteCount:Int = 4): Int {
    var target = 0
    val lastIndex = Math.min(4, byteCount) + offset - 1
    var index = lastIndex
    while (index >= offset) {
        target += (this[index].toInt() and 0xff).shl(8 * (lastIndex - index))
        index--
    }
    return target
}

inline fun ByteArray.fromInt(value:Int, offset: Int, byteCount: Int=4) {
    val buf = value.toByteArray(byteCount)
    System.arraycopy(buf, 0, this, offset, byteCount)
}


