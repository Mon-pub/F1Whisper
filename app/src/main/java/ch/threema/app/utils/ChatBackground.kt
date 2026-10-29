package ch.threema.app.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * F1Whisper: a built-in catalog of gradient chat backgrounds (Telegram-style), rendered in code so
 * they add essentially nothing to the APK (no bitmap assets). A background is identified by a stable
 * [id] string that is persisted per chat / globally; the gradient is generated on demand.
 */
data class ChatBackground(
    val id: String,
    @get:JvmName("getColors") val colors: IntArray,
    val angleDegrees: Int = 45,
) {
    /**
     * Render this gradient into a [width] x [height] bitmap. RGB_565 is used because a chat
     * background needs no alpha; this keeps the bitmap small. The gradient runs diagonally
     * according to [angleDegrees].
     */
    fun toBitmap(width: Int, height: Int): Bitmap {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        val radians = Math.toRadians(angleDegrees.toDouble())
        val endX = (cos(radians) * safeWidth).toFloat()
        val endY = (sin(radians) * safeHeight).toFloat()
        val paint = Paint().apply {
            isDither = true
            shader = LinearGradient(0f, 0f, endX, endY, colors, null, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, safeWidth.toFloat(), safeHeight.toFloat(), paint)
        return bitmap
    }

    // data class with an array field: override equals/hashCode on the stable id only
    override fun equals(other: Any?): Boolean = other is ChatBackground && other.id == id

    override fun hashCode(): Int = id.hashCode()
}

object ChatBackgrounds {
    /** The built-in gradient catalog. Order is stable (used by [stableForUid]). */
    @JvmField
    val ALL: List<ChatBackground> = listOf(
        ChatBackground("sunset", intArrayOf(0xFFFF7E5F.toInt(), 0xFFFEB47B.toInt())),
        ChatBackground("ocean", intArrayOf(0xFF2193B0.toInt(), 0xFF6DD5ED.toInt())),
        ChatBackground("forest", intArrayOf(0xFF11998E.toInt(), 0xFF38EF7D.toInt())),
        ChatBackground("lavender", intArrayOf(0xFF834D9B.toInt(), 0xFFD04ED6.toInt())),
        ChatBackground("dusk", intArrayOf(0xFF355C7D.toInt(), 0xFF6C5B7B.toInt(), 0xFFC06C84.toInt())),
        ChatBackground("peach", intArrayOf(0xFFFF9A8B.toInt(), 0xFFFF6A88.toInt(), 0xFFFF99AC.toInt())),
        ChatBackground("mint", intArrayOf(0xFF00B09B.toInt(), 0xFF96C93D.toInt())),
        ChatBackground("berry", intArrayOf(0xFFC94B4B.toInt(), 0xFF4B134F.toInt())),
        ChatBackground("sky", intArrayOf(0xFF1FA2FF.toInt(), 0xFF12D8FA.toInt(), 0xFFA6FFCB.toInt())),
        ChatBackground("ember", intArrayOf(0xFFF12711.toInt(), 0xFFF5AF19.toInt())),
        // Sourced from Telegram's built-in animated gradient wallpapers.
        ChatBackground("candy", intArrayOf(0xFF837CFF.toInt(), 0xFFB063FF.toInt(), 0xFFFF72A9.toInt(), 0xFFE269FF.toInt())),
        ChatBackground("aqua", intArrayOf(0xFF4D8DFF.toInt(), 0xFF2BBFFF.toInt(), 0xFF20E2CD.toInt(), 0xFF0EE1F1.toInt())),
        ChatBackground("lime", intArrayOf(0xFF00D2D5.toInt(), 0xFF09E279.toInt(), 0xFFC7EF60.toInt(), 0xFF6DD957.toInt())),
        ChatBackground("coral", intArrayOf(0xFFFF7866.toInt(), 0xFFFF82A5.toInt(), 0xFFFEB055.toInt(), 0xFFFF8E51.toInt())),
        ChatBackground("rose", intArrayOf(0xFFF94BA0.toInt(), 0xFFFB5C80.toInt(), 0xFFFFB23A.toInt(), 0xFFFE7E62.toInt())),
    )

    @JvmStatic
    fun byId(id: String?): ChatBackground? = ALL.firstOrNull { it.id == id }

    /**
     * Deterministically map a conversation's unique id string to one of the built-in backgrounds.
     * This gives existing chats a varied-but-stable background after the update without storing
     * anything per chat.
     */
    @JvmStatic
    fun stableForUid(uniqueIdString: String): ChatBackground =
        ALL[abs(uniqueIdString.hashCode()) % ALL.size]
}
