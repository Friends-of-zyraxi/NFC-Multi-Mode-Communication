package io.github.zyraxi21.nfc

import android.content.Context
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import io.github.zyraxi21.nfc.util.HceDataStore
import io.github.zyraxi21.nfc.util.MAX_EMULATED_NDEF_BYTES

/**
 * NFC Forum Type 4 Tag 的 Host Card Emulation 服务。
 *
 * 兼容性说明：小米 HyperOS 会把标准 T4T AID（D2760000850101）直接写进 NFC 控制器的
 * 路由表并指向 SE，这类请求不会下发到本服务（应用层的 preferred service、payment 默认
 * 服务都不起作用）。因此与自家读卡端的通信实际依赖私有 AID `F012345678`，
 * T4T 通路仅作为其它机型的兼容实现保留。
 */
class MyHostApduService : HostApduService() {

    companion object {
        const val TAG = "MyHostApduService"

        private fun hexStringToByteArray(s: String): ByteArray {
            check(s.length % 2 == 0) { "Hex string must have an even length" }
            return s.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
        }

        // 状态字
        val SW_OK = hexStringToByteArray("9000")
        val SW_INS_NOT_SUPPORTED = hexStringToByteArray("6D00")
        val SW_DATA_INVALID = hexStringToByteArray("6984")
        val SW_FILE_NOT_FOUND = hexStringToByteArray("6A82")

        /** 6B00：偏移量超出文件范围（T4T 规范要求，不能用 9000 冒充成功） */
        val SW_OFFSET_OUT_OF_RANGE = hexStringToByteArray("6B00")

        // NFC Forum Type 4 Tag 常量
        val NDEF_TAG_AID = hexStringToByteArray("D2760000850101")
        val CC_FILE_ID = hexStringToByteArray("E103")
        val NDEF_FILE_ID = hexStringToByteArray("E104")

        // 自定义 APDU 指令（其中 CMD_READ_EMULATED_DATA 用 P1P2 承载读取偏移量）
        val CMD_GET_DEVICE_NAME = hexStringToByteArray("80010000")
        val CMD_CONFIRM_CONNECTION = hexStringToByteArray("80020000")
        val CMD_READ_EMULATED_DATA = hexStringToByteArray("80030000")

        /**
         * 单次响应携带的数据上限。Le=0 表示期望 256 字节，而短 APDU 的数据域最多 255 字节，
         * 这里统一按 252 返回，由读卡端用偏移量分块取完剩余部分。
         */
        private const val MAX_APDU_DATA = 252

        /** 供读卡端一致使用的单块大小（与 MAX_APDU_DATA 相同） */
        const val CHUNK_SIZE = MAX_APDU_DATA

        private const val deviceNameToShare = "CardDevice_123"

        @Volatile
        private var connectionEstablished = false

        /** 当前待模拟的 NDEF 消息。写入请走 [setEmulatedData]，以便同步落盘。 */
        @Volatile
        var emulatedData: ByteArray? = null
            private set

        /** 单条记录保留原类型名，多条记录使用 MULTI；实际内容始终由 NDEF 消息决定。 */
        @Volatile
        var emulatedDataType: String? = null
            private set

        /**
         * 设置或清除待模拟的 NDEF 数据（由 MainActivity 调用）。
         *
         * 除更新进程内缓存外还会写入 [HceDataStore]：HCE 服务可能在应用进程被系统回收后
         * 由系统重新绑定启动，届时静态字段已经丢失，必须从磁盘恢复，否则读卡端会读到空内容。
         */
        fun setEmulatedData(context: Context, data: ByteArray?, type: String? = null) {
            require(data == null || data.size <= MAX_EMULATED_NDEF_BYTES) { "NDEF message exceeds Type 4 Tag capacity" }
            emulatedData = data
            emulatedDataType = type
            HceDataStore.save(context, data, type)
        }

        /** 读取当前模拟数据；进程内缓存为空时尝试从磁盘恢复 */
        private fun emulatedDataOrRestore(context: Context): ByteArray? {
            emulatedData?.let { return it }
            val restored = HceDataStore.load(context) ?: return null
            emulatedData = restored.first
            emulatedDataType = restored.second
            return restored.first
        }
    }

    // 当前选中的文件 ID（null = 未选中）
    private var selectedFileId: ByteArray? = null

    override fun onCreate() {
        super.onCreate()
        // 进程可能被系统回收后重建，这里预热一次，避免首个 APDU 才去读磁盘
        emulatedDataOrRestore(this)
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        Log.i(TAG, "APDU: ${commandApdu.toHexString()}")

        if (commandApdu.size < 4) return SW_DATA_INVALID

        val ins = commandApdu[1].toInt() and 0xFF
        val p1 = commandApdu[2].toInt() and 0xFF
        val p2 = commandApdu[3].toInt() and 0xFF

        // ================================================================
        // SELECT 命令
        // ================================================================
        if (ins == 0xA4) {
            if (commandApdu.size < 5) return SW_DATA_INVALID
            val lc = commandApdu[4].toInt() and 0xFF
            if (commandApdu.size < 5 + lc) return SW_DATA_INVALID
            val data = commandApdu.copyOfRange(5, 5 + lc)

            // SELECT by AID (P1=04)
            if (p1 == 0x04) {
                Log.i(TAG, "SELECT AID: ${data.toHexString()}")
                // T4T 标准 AID 与私有 AID 都接受；未知 AID 交由读卡端自行判断
                selectedFileId = null
                return SW_OK
            }

            // SELECT by File ID (P2=0C)
            if (p2 == 0x0C && data.size == 2) {
                Log.i(TAG, "SELECT File: ${data.toHexString()}")
                if (data.contentEquals(CC_FILE_ID)) {
                    selectedFileId = CC_FILE_ID
                    return SW_OK
                }
                if (data.contentEquals(NDEF_FILE_ID)) {
                    selectedFileId = NDEF_FILE_ID
                    return SW_OK
                }
                return SW_FILE_NOT_FOUND
            }
        }

        // ================================================================
        // READ_BINARY 命令
        // ================================================================
        if (ins == 0xB0) {
            val offset = ((p1 shl 8) or p2)
            // Le is the last byte (expected response length)
            val le = if (commandApdu.size >= 5) (commandApdu.last().toInt() and 0xFF) else 0xFF

            val fileId = selectedFileId
            if (fileId == null) {
                Log.w(TAG, "READ_BINARY: no file selected")
                return SW_DATA_INVALID
            }

            if (fileId.contentEquals(CC_FILE_ID)) {
                val cc = buildCapabilityContainer()
                Log.i(TAG, "READ_BINARY CC offset=$offset len=$le")
                return readFromFile(cc, offset, le)
            }

            if (fileId.contentEquals(NDEF_FILE_ID)) {
                val ndef = ndefFile()
                Log.i(TAG, "READ_BINARY NDEF offset=$offset len=$le, total=${ndef.size}")
                return readFromFile(ndef, offset, le)
            }
        }

        // ================================================================
        // 自定义命令
        // ================================================================
        val header = commandApdu.copyOfRange(0, 4)

        if (header.contentEquals(CMD_GET_DEVICE_NAME)) {
            Log.i(TAG, "CMD: GET_DEVICE_NAME")
            return deviceNameToShare.toByteArray(Charsets.UTF_8) + SW_OK
        }

        if (header.contentEquals(CMD_CONFIRM_CONNECTION)) {
            Log.i(TAG, "CMD: CONFIRM_CONNECTION")
            connectionEstablished = true
            return "CONN_ACK".toByteArray(Charsets.UTF_8) + SW_OK
        }

        // P1P2 是可变偏移量，不能与常量 80030000 比较整个四字节头，否则只能读第一块。
        if ((commandApdu[0].toInt() and 0xFF) == 0x80 && ins == 0x03) {
            // P1P2 = 读取偏移量，便于读卡端把大于单块上限的 NDEF 消息分块取回
            val offset = (p1 shl 8) or p2
            val data = emulatedDataOrRestore(this) ?: return SW_DATA_INVALID
            Log.i(TAG, "CMD: READ_EMULATED_DATA offset=$offset total=${data.size}")
            // readFromFile 返回的字节串已附带 SW_OK，不要再追加，否则读卡端会解析出 trailing data
            return readFromFile(data, offset, MAX_APDU_DATA)
        }

        Log.w(TAG, "Unknown command INS=${String.format("%02X", ins)}")
        return SW_INS_NOT_SUPPORTED
    }

    override fun onDeactivated(reason: Int) {
        Log.i(TAG, "Deactivated: reason=$reason")
        connectionEstablished = false
        selectedFileId = null
    }

    // ---- 辅助方法 ----

    /**
     * T4T 的 NDEF 文件内容 = 2 字节 NLEN（NDEF 消息长度，大端）+ NDEF 消息本身。
     * 缺少 NLEN 会让标准读卡端把 NDEF 记录的头部误读成长度，导致解析失败。
     */
    private fun ndefFile(): ByteArray {
        val ndef = emulatedDataOrRestore(this) ?: return ByteArray(2)
        val len = ndef.size
        return byteArrayOf(
            ((len shr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte()
        ) + ndef
    }

    /** 构建 NFC Forum Type 4 Tag Capability Container */
    private fun buildCapabilityContainer(): ByteArray {
        val ndefSize = emulatedDataOrRestore(this)?.size ?: 0
        // 能力容器声明的是整个文件的容量，必须包含两字节 NLEN。
        // Android 的标准检测会校验 NLEN + 2 <= Max NDEF File Size。
        val maxFileSize = maxOf(ndefSize + 2, 0x00FF).coerceAtMost(0xFFFE)

        return byteArrayOf(
            // CCLEN = 0x000F (15 bytes)
            0x00, 0x0F,
            // Mapping version 2.0
            0x20.toByte(),
            // MLe 与 readFromFile 实际支持的单块长度一致。
            0x00, MAX_APDU_DATA.toByte(),
            // MLc (max C-APDU data size) = 0x00FF
            0x00, 0xFF.toByte(),
            // NDEF File Control TLV: T=0x04, L=0x06
            0x04, 0x06,
            // NDEF File ID = E104
            0xE1.toByte(), 0x04,
            // Max NDEF File Size（2 bytes，包含 NLEN）
            ((maxFileSize shr 8) and 0xFF).toByte(),
            (maxFileSize and 0xFF).toByte(),
            // Read access = always (0x00)
            0x00,
            // Write access = never (0xFF, read-only emulation)
            0xFF.toByte()
        )
    }

    /** 按偏移和长度从文件内容中读取；越界时按 ISO 7816-4 返回 6B00 */
    private fun readFromFile(fileData: ByteArray, offset: Int, reqLen: Int): ByteArray {
        if (offset < 0 || offset > fileData.size) return SW_OFFSET_OUT_OF_RANGE

        // Le=0 表示请求 256 字节；但短 APDU 数据域最多 255 字节，这里统一截到 252
        val maxRead = minOf(
            if (reqLen == 0) 256 else reqLen,
            fileData.size - offset,
            MAX_APDU_DATA
        )
        // offset == size 时读到的长度为 0，属于合法情况（已到文件末尾）
        if (maxRead <= 0) return SW_OK

        val result = ByteArray(maxRead + 2)
        System.arraycopy(fileData, offset, result, 0, maxRead)
        result[maxRead] = 0x90.toByte()
        result[maxRead + 1] = 0x00
        return result
    }

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
}
