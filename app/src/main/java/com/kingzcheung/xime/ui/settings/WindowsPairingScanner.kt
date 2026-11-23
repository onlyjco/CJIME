package com.kingzcheung.xime.ui.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.RGBLuminanceSource
import com.kingzcheung.xime.clipboard.sync.PairingQrDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun WindowsPairingScanner(onDismiss: () -> Unit, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("从相册或截图中选择电脑 CJIME 的配对二维码，识别后自动连接") }
    var importing by remember { mutableStateOf(false) }
    val delivered = remember { AtomicBoolean(false) }
    val latestCode by rememberUpdatedState(onCode)

    fun accept(result: PairingQrDecoder.Result?, fromImage: Boolean = false) {
        when (result) {
            is PairingQrDecoder.Result.PairingCode -> if (delivered.compareAndSet(false, true)) latestCode(result.text)
            PairingQrDecoder.Result.OtherCode -> message = "已识别二维码，但不是 CJIME 配对码；请打开电脑的“手机与电脑互联”"
            PairingQrDecoder.Result.InvalidPairingCode -> message = "配对码格式不完整或版本不支持，请在电脑刷新二维码"
            null -> if (fromImage) message = "图片中未识别到二维码，请保留完整二维码和四周白边"
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                        val bounds = options
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                        options.inJustDecodeBounds = false
                        var inSampleSize = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1600) inSampleSize *= 2
                        options.inSampleSize = inSampleSize
                        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                            ?: return@runCatching null
                        try {
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            PairingQrDecoder.scan(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
                        } finally { bitmap.recycle() }
                    }.getOrNull()
                }
                accept(result, fromImage = true)
            } finally { importing = false }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.widthIn(max = 480.dp).fillMaxWidth(0.9f)
                .testTag("pairing-scanner"), shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("连接电脑", style = MaterialTheme.typography.titleLarge)
                Text(if (importing) "正在识别图片…" else message, style = MaterialTheme.typography.bodyMedium)
                Box(
                    Modifier.fillMaxWidth().height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (importing) {
                        CircularProgressIndicator()
                    } else {
                        OutlinedButton(
                            onClick = { gallery.launch("image/*") },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        ) {
                            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("从图片/相册选择二维码")
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}
