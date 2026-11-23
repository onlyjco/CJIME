package com.kingzcheung.xime.settings

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.RGBLuminanceSource
import com.kingzcheung.xime.clipboard.sync.PairingQrDecoder
import com.kingzcheung.xime.clipboard.sync.WindowsPairingProtocol
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PairingQrScannerTest {
    @get:Rule val rule = createComposeRule()

    @Test fun readsWindowsQRCoderOutputWithTheAndroidDecoder() {
        // Dummy protocol fixture rendered using Windows QRCoder 1.8.0, ECC M, 4 pixels/module.
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bitmap = assets.open("qr/windows-compact.png").use { BitmapFactory.decodeStream(it) }
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val start = SystemClock.elapsedRealtime()
            val result = PairingQrDecoder.scan(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
            println("Windows fixture QR decode: ${SystemClock.elapsedRealtime() - start}ms, ${bitmap.width}x${bitmap.height}")
            assertTrue(result is PairingQrDecoder.Result.PairingCode)
            val offer = WindowsPairingProtocol.offer((result as PairingQrDecoder.Result.PairingCode).text)
            assertEquals("00112233-4455-6677-8899-aabbccddeeff", offer.getString("server_id"))
            assertEquals("https://192.168.1.2:18740", offer.getJSONArray("endpoints").getString(0))
        } finally { bitmap.recycle() }
    }
}
