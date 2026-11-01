package io.github.zyraxi21.nfc

import android.nfc.Tag
import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zyraxi21.nfc.util.parseNfcReadResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagDetailsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun classic1kUsesDiscoveryParametersAndKeepsPhysicalAndNdefCapacitySeparate() {
        val result = parseNfcReadResult(context, emptyList(), discoveryTag())
        val fields = result.tagDetails.associate { it.labelResId to it.value }
        assertEquals("ISO/IEC 14443-3A", fields[R.string.reader_field_tag_type])
        assertEquals("0x0004", fields[R.string.reader_field_atqa])
        assertEquals("0x08", fields[R.string.reader_field_sak])
        assertEquals("1024 字节", fields[R.string.reader_field_physical_memory])
        assertEquals("16 个扇区，每区 4 块，每块 16 字节", fields[R.string.reader_field_memory_layout])
        assertEquals("NXP MIFARE Classic", fields[R.string.reader_field_ndef_format])
        assertEquals("否", fields[R.string.reader_field_can_lock])
        assertEquals(716, result.capacity)
        assertEquals(true, result.isWritable)
        assertEquals("B5 A6 1E 28", result.tagId)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun classic4kDescribesBothSmallAndLargeSectors() {
        val result = parseNfcReadResult(context, emptyList(), discoveryTag(sak = 0x18))
        val fields = result.tagDetails.associate { it.labelResId to it.value }
        assertEquals("4096 字节", fields[R.string.reader_field_physical_memory])
        assertEquals("32 个扇区，每区 4 块，每块 16 字节；8 个扇区，每区 16 块，每块 16 字节",
            fields[R.string.reader_field_memory_layout])
    }

    @Test
    fun isoDepRetainsHistoryWithoutGuessingChipOrPhysicalMemory() {
        val a = Bundle().apply { putShort("sak", 0x20); putByteArray("atqa", byteArrayOf(4, 0)) }
        val iso = Bundle().apply { putByteArray("histbytes", byteArrayOf(0x80.toByte(), 0x01)) }
        val tag = parcelTag(intArrayOf(1, 3), arrayOf(a, iso))
        val result = parseNfcReadResult(context, emptyList(), tag)
        val fields = result.tagDetails.associate { it.labelResId to it.value }
        assertEquals("ISO/IEC 14443-4 (Type A)", fields[R.string.reader_field_tag_type])
        assertEquals("80 01", fields[R.string.reader_field_historical_bytes])
        assertFalse(fields.containsKey(R.string.reader_field_chip_family))
        assertFalse(fields.containsKey(R.string.reader_field_physical_memory))
        assertNull(result.capacity)
        assertNull(result.isWritable)
    }

    @Test
    fun absentParametersDoNotPreventDisplayingOtherTechnologies() {
        val tag = parcelTag(intArrayOf(1, 6), arrayOf(Bundle(), ndefExtras()))
        val result = parseNfcReadResult(context, emptyList(), tag)
        assertFalse(result.tagDetails.any { it.labelResId == R.string.reader_field_atqa })
        assertTrue(result.tagDetails.any { it.labelResId == R.string.reader_field_ndef_format })
        assertEquals(716, result.capacity)
    }
}

internal fun discoveryTag(sak: Int = 8): Tag = parcelTag(intArrayOf(1, 8, 6), arrayOf(
    Bundle().apply { putShort("sak", sak.toShort()); putByteArray("atqa", byteArrayOf(4, 0)) },
    Bundle(), ndefExtras(),
))

private fun ndefExtras() = Bundle().apply {
    putInt("ndefmaxlength", 716)
    putInt("ndefcardstate", 2)
    putInt("ndeftype", 101)
}

/** 按 AOSP Tag 的 Parcelable 格式构造发现快照，不连接 NFC 服务，也不使用隐藏接口。 */
private fun parcelTag(technologies: IntArray, extras: Array<Bundle>): Tag {
    val parcel = Parcel.obtain()
    return try {
        val uid = byteArrayOf(0xB5.toByte(), 0xA6.toByte(), 0x1E, 0x28)
        parcel.writeInt(uid.size)
        parcel.writeByteArray(uid)
        parcel.writeInt(technologies.size)
        parcel.writeIntArray(technologies)
        parcel.writeTypedArray(extras, 0)
        parcel.writeInt(0)
        parcel.writeLong(0)
        parcel.writeInt(1)
        parcel.setDataPosition(0)
        Tag.CREATOR.createFromParcel(parcel)
    } finally { parcel.recycle() }
}
