package com.musicplayer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Paints ──────────────────────────────────────────────────────────────
    private val waveGradient1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val waveGradient2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val barPaint      = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style      = Paint.Style.STROKE
        strokeWidth = 3f
        maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
    }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // ── State ────────────────────────────────────────────────────────────────
    private var waveformData = ByteArray(0)
    private var fftData      = ByteArray(0)
    private var smoothFft    = FloatArray(64)
    private var animPhase    = 0f
    private var isAnimating  = false
    private var bgHue        = 220f          // slowly rotating hue
    private val particles    = Array(30) { Particle() }
    private var pulseRadius  = 0f
    private var pulseAlpha   = 0f

    private val waveAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration     = 2000
        repeatCount  = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            animPhase = it.animatedValue as Float
            bgHue = (bgHue + 0.3f) % 360f
            updateParticles()
            invalidate()
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────
    fun updateWaveform(data: ByteArray) { waveformData = data.copyOf() }

    fun updateFft(data: ByteArray) {
        fftData = data.copyOf()
        val bars = min(smoothFft.size, data.size / 2)
        for (i in 0 until bars) {
            val mag = (sqrt((data[2 * i].toFloat().pow(2) +
                    (if (2 * i + 1 < data.size) data[2 * i + 1].toFloat() else 0f).pow(2)))).coerceIn(0f, 128f)
            smoothFft[i] = smoothFft[i] * 0.6f + mag * 0.4f   // smooth lerp
        }
        triggerPulse()
    }

    fun startAnimation() {
        isAnimating = true
        particles.forEach { it.reset(width.toFloat(), height.toFloat()) }
        if (!waveAnimator.isRunning) waveAnimator.start()
    }

    fun pauseAnimation() {
        isAnimating = false
        if (waveAnimator.isRunning) waveAnimator.pause()
        invalidate()
    }

    // ── Drawing ──────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        drawBackground(canvas, w, h)
        drawPulseRing(canvas, cx, cy)
        drawFftBars(canvas, w, h)
        drawWave(canvas, w, h, animPhase, waveGradient1, offset = 0f, alpha = 200)
        drawWave(canvas, w, h, animPhase + 60f, waveGradient2, offset = 0.3f, alpha = 130)
        drawGlowLine(canvas, w, h)
        drawParticles(canvas)
        if (!isAnimating) drawPausedOverlay(canvas, w, h)
    }

    // Animated gradient background
    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        val bg = Paint()
        bg.shader = RadialGradient(
            w / 2, h / 2,
            maxOf(w, h) * 0.7f,
            intArrayOf(
                Color.HSVToColor(floatArrayOf(bgHue, 0.85f, 0.18f)),
                Color.HSVToColor(floatArrayOf((bgHue + 40) % 360f, 0.9f, 0.06f)),
                Color.BLACK
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, bg)
    }

    // Equalizer bar chart (FFT)
    private fun drawFftBars(canvas: Canvas, w: Float, h: Float) {
        val bars = smoothFft.size
        val barW = w / (bars * 1.6f)
        val spacing = (w - barW * bars) / (bars + 1)
        val baseY = h * 0.80f
        val maxBarH = h * 0.42f

        for (i in 0 until bars) {
            val x = spacing + i * (barW + spacing)
            val barH = (smoothFft[i] / 128f) * maxBarH + 4f
            val hue = (bgHue + i * 4f) % 360f
            barPaint.shader = LinearGradient(
                x, baseY - barH, x, baseY,
                Color.HSVToColor(floatArrayOf(hue, 1f, 1f)),
                Color.HSVToColor(floatArrayOf((hue + 30f) % 360f, 0.7f, 0.4f)),
                Shader.TileMode.CLAMP
            )
            barPaint.alpha = 210
            val r = barW / 2f
            canvas.drawRoundRect(x, baseY - barH, x + barW, baseY, r, r, barPaint)

            // Mirror below baseline
            barPaint.alpha = 80
            canvas.drawRoundRect(x, baseY, x + barW, baseY + barH * 0.3f, r, r, barPaint)
        }
    }

    // Sine wave drawn from waveform data
    private fun drawWave(canvas: Canvas, w: Float, h: Float, phase: Float,
                         paint: Paint, offset: Float, alpha: Int) {
        val path = Path()
        val cy = h * 0.80f
        val amp = if (isAnimating && waveformData.isNotEmpty()) {
            val rms = waveformData.map { abs(it.toInt()).toFloat() }.average().toFloat()
            (rms / 128f * h * 0.18f).coerceAtLeast(8f)
        } else 8f

        val phaseRad = Math.toRadians(phase.toDouble()).toFloat()
        val points = 120
        path.moveTo(0f, cy)
        for (i in 0..points) {
            val x = i / points.toFloat() * w
            val t = i / points.toFloat() * 2 * PI.toFloat() + phaseRad + offset * 2 * PI.toFloat()
            val y = cy + sin(t) * amp + sin(t * 3f) * amp * 0.3f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.lineTo(w, h); path.lineTo(0f, h); path.close()

        val hue = (bgHue + 120f) % 360f
        paint.shader = LinearGradient(
            0f, cy - amp, 0f, cy + amp,
            Color.HSVToColor(alpha, floatArrayOf(hue, 1f, 1f)),
            Color.HSVToColor(alpha / 3, floatArrayOf((hue + 60f) % 360f, 0.8f, 0.5f)),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, paint)
    }

    // Glowing top-line stroke
    private fun drawGlowLine(canvas: Canvas, w: Float, h: Float) {
        val cy = h * 0.80f
        val amp = 20f
        val path = Path()
        val phaseRad = Math.toRadians(animPhase.toDouble()).toFloat()
        for (i in 0..150) {
            val x = i / 150f * w
            val t = i / 150f * 2 * PI.toFloat() + phaseRad
            val y = cy + sin(t) * amp + sin(t * 2.5f) * amp * 0.4f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val hue = (bgHue + 60f) % 360f
        glowPaint.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        canvas.drawPath(path, glowPaint)
    }

    // Expanding ring on beat
    private fun triggerPulse() {
        if (smoothFft.take(8).average() > 30) {
            pulseRadius = 10f
            pulseAlpha = 255f
        }
    }

    private fun drawPulseRing(canvas: Canvas, cx: Float, cy: Float) {
        if (pulseAlpha <= 0) return
        pulseRadius += 8f
        pulseAlpha -= 15f
        val hue = (bgHue + 180f) % 360f
        circlePaint.color = Color.HSVToColor(pulseAlpha.toInt().coerceIn(0, 255),
            floatArrayOf(hue, 1f, 1f))
        canvas.drawCircle(cx, cy * 0.4f, pulseRadius, circlePaint)
    }

    // Floating particles
    private fun updateParticles() {
        particles.forEach { it.update(width.toFloat(), height.toFloat()) }
    }

    private fun drawParticles(canvas: Canvas) {
        particles.forEach { p ->
            particlePaint.color = Color.HSVToColor((p.alpha * 255).toInt(),
                floatArrayOf((bgHue + p.hueOffset) % 360f, 1f, 1f))
            canvas.drawCircle(p.x, p.y, p.size, particlePaint)
        }
    }

    private fun drawPausedOverlay(canvas: Canvas, w: Float, h: Float) {
        val p = Paint().apply { color = Color.argb(90, 0, 0, 0) }
        canvas.drawRect(0f, 0f, w, h, p)
    }

    // Particle data class
    inner class Particle {
        var x = 0f; var y = 0f; var vx = 0f; var vy = 0f
        var size = 0f; var alpha = 0f; var hueOffset = 0f

        init { reset(200f, 400f) }

        fun reset(w: Float, h: Float) {
            x = (Math.random() * w).toFloat()
            y = (Math.random() * h).toFloat()
            vx = ((Math.random() - 0.5) * 1.5).toFloat()
            vy = (-(Math.random() * 2 + 0.5)).toFloat()
            size = (Math.random() * 4 + 1).toFloat()
            alpha = (Math.random() * 0.8 + 0.2).toFloat()
            hueOffset = (Math.random() * 120).toFloat()
        }

        fun update(w: Float, h: Float) {
            x += vx; y += vy
            alpha -= 0.008f
            if (alpha <= 0f || y < -10f) reset(w, h)
        }
    }
}
