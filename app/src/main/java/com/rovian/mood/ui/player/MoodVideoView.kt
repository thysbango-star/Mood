package com.rovian.mood.ui.player

import android.content.Context
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RequiresApi

/**
 * Mood's video surface: a TextureView-backed player so the picture can be
 * processed on the GPU before it reaches the screen.
 *
 * **Mood Enhance** (Android 13+): when the video is shown larger than it was
 * encoded (a 720p file on a 1080p phone, a phone video on a TV), the picture
 * is scaled up and edges soften. Enhance measures that scale factor and applies
 * a matching sharpening pass (an unsharp mask whose radius follows the upscale),
 * restoring crispness the way TVs' own processors do. It is not AI super-
 * resolution — it doesn't invent detail — and it's off at 1:1 where it isn't needed.
 */
class MoodVideoView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var uri: Uri? = null
    private var prepared = false
    private var pendingSeek = -1
    private var videoW = 0
    private var videoH = 0

    var onPrepared: ((durationMs: Int) -> Unit)? = null
    var onCompletion: (() -> Unit)? = null
    var onError: ((what: Int) -> Unit)? = null
    /** Called when the measured upscale factor changes (1.0 = shown at native size). */
    var onScaleChanged: ((Float) -> Unit)? = null

    var enhance: Boolean = true
        set(value) {
            field = value
            applyEffect()
        }

    /** How much the video is being enlarged on this display. */
    var upscale: Float = 1f
        private set

    val enhanceSupported: Boolean get() = Build.VERSION.SDK_INT >= 33

    init {
        surfaceTextureListener = this
    }

    fun setVideoURI(u: Uri) {
        uri = u
        pendingSeek = -1
        open()
    }

    private fun open() {
        val u = uri ?: return
        val st = surfaceTexture ?: return // opened once the surface exists
        releasePlayer()
        prepared = false
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            mp.setDataSource(context, u)
            surface = Surface(st)
            mp.setSurface(surface)
            mp.setOnPreparedListener {
                prepared = true
                videoW = it.videoWidth
                videoH = it.videoHeight
                updateTransform()
                if (pendingSeek >= 0) it.seekTo(pendingSeek)
                pendingSeek = -1
                onPrepared?.invoke(it.duration)
            }
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoW = w
                videoH = h
                updateTransform()
            }
            mp.setOnCompletionListener { onCompletion?.invoke() }
            mp.setOnErrorListener { _, what, _ ->
                onError?.invoke(what)
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open $u", e)
            mp.release()
            onError?.invoke(-1)
        }
    }

    fun start() { if (prepared) player?.start() }
    fun pause() { if (prepared) player?.pause() }

    fun seekTo(ms: Int) {
        if (prepared) player?.seekTo(ms) else pendingSeek = ms
    }

    val currentPosition: Int get() = if (prepared) player?.currentPosition ?: 0 else maxOf(pendingSeek, 0)
    val duration: Int get() = if (prepared) player?.duration ?: 0 else 0
    val isPlaying: Boolean get() = prepared && player?.isPlaying == true

    // VideoView-compatible capability checks (MediaPlayer files are pausable/seekable).
    fun canPause() = prepared
    fun canSeekForward() = prepared && duration > 0
    fun canSeekBackward() = prepared && duration > 0

    fun stopPlayback() {
        releasePlayer()
        uri = null
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.reset(); it.release() } }
        player = null
        surface?.release()
        surface = null
        prepared = false
    }

    /** Fit-centre the video inside the view and measure the upscale factor. */
    private fun updateTransform() {
        if (videoW <= 0 || videoH <= 0 || width <= 0 || height <= 0) return
        val scale = minOf(width.toFloat() / videoW, height.toFloat() / videoH)
        val sx = videoW * scale / width
        val sy = videoH * scale / height
        setTransform(Matrix().apply { setScale(sx, sy, width / 2f, height / 2f) })
        if (scale != upscale) {
            upscale = scale
            onScaleChanged?.invoke(scale)
        }
        applyEffect()
    }

    private fun applyEffect() {
        if (Build.VERSION.SDK_INT < 33) return
        setRenderEffect(if (enhance && upscale > ENHANCE_FROM) Enhance.effect(upscale) else null)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        if (uri != null && player == null) open()
        updateTransform()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = updateTransform()

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        val keepAt = currentPosition
        releasePlayer()
        pendingSeek = keepAt
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    @RequiresApi(33)
    private object Enhance {
        // Unsharp mask whose sampling radius follows the upscale, so softened edges
        // (which spread over ~upscale pixels) are restored without halos at 1:1.
        private const val AGSL = """
            uniform shader content;
            uniform float radius;
            uniform float amount;
            half4 main(float2 p) {
                half4 c = content.eval(p);
                half3 n = content.eval(p + float2(0.0, -radius)).rgb
                        + content.eval(p + float2(0.0,  radius)).rgb
                        + content.eval(p + float2(-radius, 0.0)).rgb
                        + content.eval(p + float2( radius, 0.0)).rgb;
                half3 detail = c.rgb - n * 0.25;
                return half4(clamp(c.rgb + detail * amount, 0.0, 1.0), c.a);
            }
        """

        fun effect(upscale: Float): RenderEffect {
            val shader = RuntimeShader(AGSL)
            shader.setFloatUniform("radius", (upscale * 0.75f).coerceIn(1f, 3f))
            shader.setFloatUniform("amount", (0.35f + 0.18f * (upscale - 1f)).coerceIn(0.35f, 0.85f))
            return RenderEffect.createRuntimeShaderEffect(shader, "content")
        }
    }

    private companion object {
        const val TAG = "MoodVideoView"
        const val ENHANCE_FROM = 1.15f
    }
}
