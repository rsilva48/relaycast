package net.silvara.relaycast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream

/** The colors the relay accepts for a profile without a photo. */
val AVATAR_COLORS = listOf("#4B49D6", "#E5484D", "#F76B15", "#E2A336", "#46A758", "#12A594", "#0090FF", "#8E4EC6", "#D6409F", "#6F6E77")
val AVATAR_EMOJIS = listOf("🦊", "🐱", "🐼", "🐸", "🐧", "🦄", "🐙", "🐯", "🌸", "⭐️", "🎮", "🍙")

fun avatarColor(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex ?: AVATAR_COLORS[0])) }.getOrDefault(Color(0xFF4B49D6))

/**
 * Each profile's name and picture (the photo travels inside, as Base64),
 * cached so the profile picker and the phone show them at once and offline.
 * The key is the one [Session] uses.
 */
internal object AccountCache {
    private fun storage(context: Context) = context.applicationContext.getSharedPreferences("relaycast", Context.MODE_PRIVATE)
    private fun key(profileId: String) = "account.$profileId"

    fun load(context: Context, profileId: String): AccountProfile? = storage(context).getString(key(profileId), null)
        ?.let { runCatching { RelayClient.json.decodeFromString(AccountProfile.serializer(), it) }.getOrNull() }

    fun store(context: Context, profileId: String, account: AccountProfile) {
        storage(context).edit().putString(key(profileId), RelayClient.json.encodeToString(AccountProfile.serializer(), account)).apply()
    }
}

/**
 * A profile's picture on the phone and the TV: its photo, or its color with
 * the emoji or the name's initial. Plain Compose, so both UIs can use it.
 */
@Composable
internal fun Avatar(account: AccountProfile?, fallbackName: String?, size: Dp, modifier: Modifier = Modifier) {
    val photo = account?.avatar?.photo
    val bitmap = remember(photo) {
        photo?.let {
            runCatching {
                val bytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(CircleShape))
        return
    }
    Box(modifier.size(size).background(avatarColor(account?.avatar?.color), CircleShape), contentAlignment = Alignment.Center) {
        val symbol = account?.avatar?.emoji?.takeIf { it.isNotEmpty() }
            ?: (account?.displayName ?: fallbackName)?.firstOrNull()?.uppercase()
            ?: "?"
        BasicText(symbol, style = TextStyle(color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold))
    }
}

/**
 * The picked photo's center as a 256 × 256 JPEG, Base64 (the relay keeps
 * it under 64 KB). ImageDecoder (Android 9+) applies the photo's rotation.
 */
internal fun avatarPhoto(context: Context, uri: Uri): String? = runCatching {
    val source: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val side = minOf(info.size.width, info.size.height)
            if (side > 1024) decoder.setTargetSampleSize(side / 1024)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) } ?: return null
    }
    val side = minOf(source.width, source.height)
    val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    val scaled = Bitmap.createScaledBitmap(square, 256, 256, true)
    val output = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 70, output)
    Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
}.getOrNull()
