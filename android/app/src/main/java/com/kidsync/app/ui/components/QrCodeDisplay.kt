package com.kidsync.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.kidsync.app.R

/**
 * Generates and displays a QR code bitmap from the given [data] string.
 * Uses ZXing QRCodeWriter to encode data into a QR matrix and renders
 * it as a Compose Image.
 */
@Composable
fun QrCodeDisplay(
    data: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    description: String = stringResource(R.string.cd_qr_code)
) {
    val foregroundColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val backgroundColor = MaterialTheme.colorScheme.surface.toArgb()

    val bitmap = remember(data, foregroundColor, backgroundColor) {
        generateQrBitmap(
            data = data,
            sizePx = 512,
            foregroundColor = foregroundColor,
            backgroundColor = backgroundColor
        )
    }

    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(size)
            )
        }
    }
}

private fun generateQrBitmap(
    data: String,
    sizePx: Int,
    foregroundColor: Int,
    backgroundColor: Int
): Bitmap? {
    return try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val bitMatrix = QRCodeWriter().encode(
            data,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            hints
        )
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (bitMatrix.get(x, y)) foregroundColor else backgroundColor
            }
        }
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    } catch (_: Exception) {
        null
    }
}
