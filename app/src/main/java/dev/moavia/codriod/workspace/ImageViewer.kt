package dev.moavia.codriod.workspace

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed class ImageState {
    object Loading : ImageState()
    class Failed(val message: String) : ImageState()
    class Raster(val bitmap: Bitmap?, val drawable: Drawable?, val width: Int, val height: Int, val bytes: Int, val animated: Boolean) : ImageState()
    class Svg(val base64: String, val bytes: Int) : ImageState()
}

/** Largest side the viewer decodes to (keeps big photos within memory). */
private const val MAX_SIDE = 4096

private fun decode(bytes: ByteArray, path: String): ImageState {
    if (ImageFiles.isSvg(path)) return ImageState.Svg(dev.moavia.codriod.git.Base64Codec.encode(bytes), bytes.size)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val ext = ImageFiles.extension(path)
    // Animated GIF/WebP and HEIC/AVIF: Android 9+ decoder.
    if (Build.VERSION.SDK_INT >= 28 && (ext == "gif" || ext == "webp" || bounds.outWidth <= 0)) {
        val drawable = runCatching {
            android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val side = maxOf(info.size.width, info.size.height)
                if (side > MAX_SIDE) decoder.setTargetSampleSize((side + MAX_SIDE - 1) / MAX_SIDE)
            }
        }.getOrNull()
        if (drawable != null) {
            val animated = drawable is AnimatedImageDrawable
            return ImageState.Raster(null, drawable, maxOf(bounds.outWidth, drawable.intrinsicWidth), maxOf(bounds.outHeight, drawable.intrinsicHeight),
                bytes.size, animated)
        }
    }
    if (bounds.outWidth <= 0) return ImageState.Failed("This image format can't be shown on this phone.")
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return ImageState.Failed("The image could not be decoded (damaged file?).")
    return ImageState.Raster(bitmap, null, bounds.outWidth, bounds.outHeight, bytes.size, false)
}

private fun sizeText(bytes: Int) = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** Shows an image file in the editor: pinch to zoom, drag to pan, double-tap to zoom in or reset. */
@Composable
fun ImageViewer(model: WorkspaceModel, doc: EditorDocument, modifier: Modifier = Modifier) {
    var state by remember(doc.path) { mutableStateOf<ImageState>(ImageState.Loading) }
    LaunchedEffect(doc.path, doc.revision) {
        state = try {
            val bytes = model.imageBytes(doc.path)
            withContext(Dispatchers.Default) { decode(bytes, doc.path) }
        } catch (error: Exception) { ImageState.Failed(error.message ?: "Could not open the image") }
    }
    var scale by remember(doc.path) { mutableFloatStateOf(1f) }
    var offset by remember(doc.path) { mutableStateOf(Offset.Zero) }
    var backdrop by remember { mutableIntStateOf(0) } // 0 checker, 1 dark, 2 light
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val info = when (val s = state) {
                is ImageState.Raster -> "${ImageFiles.extension(doc.path).uppercase()} · ${s.width} × ${s.height} · ${sizeText(s.bytes)}" +
                    if (s.animated) " · animated" else ""
                is ImageState.Svg -> "SVG · vector · ${sizeText(s.bytes)}"
                is ImageState.Failed -> "Can't show this image"
                ImageState.Loading -> "Loading…"
            }
            Text(info, fontSize = 12.sp, color = muted)
            Spacer(Modifier.width(12.dp))
            Chip("${(scale * 100).toInt()}%") { scale = 1f; offset = Offset.Zero }
            Chip(when (backdrop) { 0 -> "▦ Checker"; 1 -> "◼ Dark"; else -> "◻ Light" }) { backdrop = (backdrop + 1) % 3 }
            if (ImageFiles.isSvg(doc.path)) Chip("</> Edit code") { model.openAsText(doc.path) }
            Chip("✦ Ask Codriod AI") { model.askAgentAboutImage(doc.path) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(0.dp))
            .pointerInput(doc.path) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.25f, 16f)
                    offset = if (scale <= 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(doc.path) {
                detectTapGestures(onDoubleTap = { if (scale > 1.05f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f })
            }, contentAlignment = Alignment.Center) {
            when (backdrop) {
                0 -> Canvas(Modifier.fillMaxSize()) {
                    val cell = 12.dp.toPx()
                    drawRect(Color(0xFF2B2B2F))
                    var y = 0f; var row = 0
                    while (y < size.height) {
                        var x = if (row % 2 == 0) 0f else cell
                        while (x < size.width) { drawRect(Color(0xFF3A3A40), Offset(x, y), Size(cell, cell)); x += cell * 2 }
                        y += cell; row++
                    }
                }
                1 -> Box(Modifier.fillMaxSize().background(Color(0xFF111114)))
                else -> Box(Modifier.fillMaxSize().background(Color.White))
            }
            val layer = Modifier.fillMaxSize().padding(12.dp).graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            }
            when (val s = state) {
                ImageState.Loading -> CircularProgressIndicator()
                is ImageState.Failed -> Text(s.message, color = Color(0xFFFCA5A5), modifier = Modifier.padding(24.dp))
                is ImageState.Raster -> AndroidView(factory = { context ->
                    ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = false }
                }, update = { view ->
                    if (s.drawable != null) {
                        view.setImageDrawable(s.drawable)
                        if (Build.VERSION.SDK_INT >= 28) (s.drawable as? AnimatedImageDrawable)?.start()
                    } else view.setImageBitmap(s.bitmap)
                    // Small icons stay sharp: no smoothing when zoomed far in.
                    view.drawable?.isFilterBitmap = scale < 3f
                }, modifier = layer)
                is ImageState.Svg -> AndroidView(factory = { context ->
                    android.webkit.WebView(context).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        settings.javaScriptEnabled = false
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                    }
                }, update = { web ->
                    val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
                        "<body style='margin:0;height:100vh;display:flex;align-items:center;justify-content:center;background:transparent'>" +
                        "<img style='max-width:100%;max-height:100%' src='data:image/svg+xml;base64,${s.base64}'></body></html>"
                    if (web.tag != s.base64) { web.tag = s.base64; web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) }
                }, modifier = layer)
            }
        }
        Text("Pinch to zoom · double-tap to zoom in or back", fontSize = 11.sp, color = muted,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 5.dp))
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp))
}
