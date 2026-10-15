package dj.sentia

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * La boule bleue de SENTIA : sa présence à l'écran. Elle bat doucement comme un cœur (repos), pulse un peu plus
 * vivement quand elle écoute, ondule quand elle parle. Purement visuelle : les réponses vocales restent prioritaires.
 * Si le téléphone a désactivé les animations, la boule reste simplement affichée, immobile.
 */
class OrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    var mode: Mode = Mode.IDLE
        set(v) { if (field != v) { field = v; invalidate() } }

    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var phase = 0.0          // avance plus ou moins vite selon l'état : pas de saut quand l'état change
    private var lastNanos = 0L
    private var appear = 1f          // 0 → 1 à l'apparition (réveil)
    private var animator: ValueAnimator? = null
    private var appearAnimator: ValueAnimator? = null

    /** Fait « apparaître » la boule au réveil : elle grandit doucement depuis le centre. */
    fun wake() {
        appearAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) { appear = 1f; invalidate(); return }
        appear = 0f
        appearAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { appear = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        lastNanos = 0L
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = null
            addUpdateListener {
                val now = System.nanoTime()
                if (lastNanos != 0L) {
                    val dt = (now - lastNanos) / 1e9
                    phase += dt * speed()
                }
                lastNanos = now
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null
        appearAnimator?.cancel(); appearAnimator = null
        super.onDetachedFromWindow()
    }

    /** Battements par seconde : calme au repos, un peu plus vif en écoute, lent quand elle réfléchit. */
    private fun speed(): Double = when (mode) {
        Mode.IDLE -> 0.32
        Mode.LISTENING -> 0.55
        Mode.THINKING -> 0.2
        Mode.SPEAKING -> 0.8
    }

    private fun wave(): Double {
        val t = phase * 2 * PI
        return when (mode) {
            // Dilatation douce puis retour : (1 - cos) / 2 va de 0 à 1 et revient, sans à-coup.
            Mode.IDLE, Mode.LISTENING, Mode.THINKING -> 0.5 - 0.5 * cos(t)
            // Parole : deux ondes lentes superposées, un mouvement organique, jamais brusque.
            Mode.SPEAKING -> 0.5 + 0.28 * sin(t) + 0.22 * sin(t * 1.7 + 1.1)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = min(w, h) / 2f * 0.62f
        val cx = w / 2f
        val cy = h / 2f
        core.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.25f,
            intArrayOf(0xFF9FD4FF.toInt(), 0xFF3B8CF2.toInt(), 0xFF1456C8.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        halo.shader = RadialGradient(cx, cy, r * 1.6f,
            intArrayOf(0x663B8CF2, 0x223B8CF2, 0x003B8CF2), floatArrayOf(0.55f, 0.8f, 1f), Shader.TileMode.CLAMP)
        ring.strokeWidth = r * 0.05f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val base = min(w, h) / 2f * 0.62f
        val cx = w / 2f
        val cy = h / 2f
        val moving = animator != null
        val b = if (moving) wave().toFloat() else 0.5f
        val amp = when (mode) { Mode.IDLE -> 0.06f; Mode.LISTENING -> 0.08f; Mode.THINKING -> 0.04f; Mode.SPEAKING -> 0.10f }
        val scale = (1f + amp * b) * (0.35f + 0.65f * appear)
        val alpha = (255 * appear).toInt().coerceIn(0, 255)

        halo.alpha = alpha
        canvas.drawCircle(cx, cy, base * 1.6f * scale, halo)
        core.alpha = alpha
        canvas.drawCircle(cx, cy, base * scale, core)

        // Anneau fin : visible seulement quand elle écoute ou parle, il s'élargit puis s'efface.
        if (moving && (mode == Mode.LISTENING || mode == Mode.SPEAKING)) {
            val p = ((phase * (if (mode == Mode.LISTENING) 0.5 else 0.8)) % 1.0).toFloat()
            ring.color = 0xFF9FD4FF.toInt()
            ring.alpha = ((1f - p) * 110 * appear).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, base * scale * (1.05f + 0.35f * p), ring)
        }
    }
}
