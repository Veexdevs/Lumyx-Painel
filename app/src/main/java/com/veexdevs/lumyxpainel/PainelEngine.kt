package com.veexdevs.lumyxpainel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.annotation.DrawableRes

@DslMarker
annotation class PainelDsl

enum class IconeAba(@DrawableRes val drawableRes: Int) {
    MIRA(R.drawable.ic_aim_crosshair),
    SLIDERS(R.drawable.ic_sliders),
    TELA(R.drawable.ic_perm_overlay),
    INFO(R.drawable.ic_info_circle),
    RAIO(R.drawable.ic_bolt),
    PODER(R.drawable.ic_power)
}

interface ControlePainel {
    val context: Context

    fun aviso(mensagem: String)
    fun vibrar(duracaoMs: Long = 35L)

    fun mudarOpacidadePainel(porcentagem: Int)
    fun mudarTamanhoBolha(tamanhoDp: Int)
    fun mudarImanBordas(ativo: Boolean)

    fun mostrarMiraCentral(ativo: Boolean)
    fun mudarEstiloMira(estilo: Int)
    fun mudarTamanhoMira(tamanhoDp: Int)
    fun mudarCorMira(corHex: String)

    fun centralizarPainel()
    fun esconderPainel()
    fun fecharPainel()
    fun resetarPainel()

    fun abrirLink(url: String)
    fun abrirApp(pacote: String)
    fun copiarTexto(texto: String)

    fun salvarValor(chave: String, valor: String)
    fun lerValor(chave: String, padrao: String = ""): String
}

sealed class ItemPainel {
    data class Botao(
        val nome: String,
        val textoDireita: String,
        val aoClicar: (ControlePainel) -> Unit
    ) : ItemPainel()

    data class Toggle(
        val nome: String,
        val padrao: Boolean,
        var ativo: Boolean = padrao,
        val aoMudar: (ControlePainel, Boolean) -> Unit
    ) : ItemPainel()

    data class Check(
        val nome: String,
        val padrao: Boolean,
        var marcado: Boolean = padrao,
        val aoMudar: (ControlePainel, Boolean) -> Unit
    ) : ItemPainel()

    data class Slide(
        val nome: String,
        val min: Int,
        val max: Int,
        val padrao: Int,
        val sufixo: String,
        var valorAtual: Int = padrao.coerceIn(min, max),
        val aoMudar: (ControlePainel, Int) -> Unit
    ) : ItemPainel()

    data class Lista(
        val nome: String,
        val opcoes: List<String>,
        val padrao: Int,
        var indiceAtual: Int = if (opcoes.isEmpty()) 0 else padrao.coerceIn(0, opcoes.lastIndex),
        val aoSelecionar: (ControlePainel, Int, String) -> Unit
    ) : ItemPainel()

    data class Info(
        val nome: String,
        val valor: String
    ) : ItemPainel()

    data class TempoAtivo(
        val nome: String
    ) : ItemPainel()
}

data class AbaPainel(
    val nome: String,
    val icone: IconeAba,
    val itens: List<ItemPainel>
)

@PainelDsl
class AbaBuilder {
    private val itens = mutableListOf<ItemPainel>()

    fun botao(
        nome: String,
        textoDireita: String = "OK",
        aoClicar: (ControlePainel) -> Unit
    ) {
        itens.add(ItemPainel.Botao(nome, textoDireita, aoClicar))
    }

    fun toggle(
        nome: String,
        padrao: Boolean = false,
        aoMudar: (ControlePainel, Boolean) -> Unit
    ) {
        itens.add(ItemPainel.Toggle(nome, padrao, padrao, aoMudar))
    }

    fun check(
        nome: String,
        padrao: Boolean = false,
        aoMudar: (ControlePainel, Boolean) -> Unit
    ) {
        itens.add(ItemPainel.Check(nome, padrao, padrao, aoMudar))
    }

    fun slide(
        nome: String,
        min: Int = 0,
        max: Int = 100,
        padrao: Int = min,
        sufixo: String = "",
        aoMudar: (ControlePainel, Int) -> Unit
    ) {
        val safeMax = max.coerceAtLeast(min)
        val safePadrao = padrao.coerceIn(min, safeMax)
        itens.add(ItemPainel.Slide(nome, min, safeMax, safePadrao, sufixo, safePadrao, aoMudar))
    }

    fun lista(
        nome: String,
        opcoes: List<String>,
        padrao: Int = 0,
        aoSelecionar: (ControlePainel, Int, String) -> Unit
    ) {
        val safeList = if (opcoes.isEmpty()) listOf("Padrão") else opcoes
        val safeIdx = padrao.coerceIn(0, safeList.lastIndex)
        itens.add(ItemPainel.Lista(nome, safeList, safeIdx, safeIdx, aoSelecionar))
    }

    fun info(nome: String, valor: String) {
        itens.add(ItemPainel.Info(nome, valor))
    }

    fun tempoAtivo(nome: String = "Tempo Ativo") {
        itens.add(ItemPainel.TempoAtivo(nome))
    }

    internal fun build(): List<ItemPainel> = itens.toList()
}

@PainelDsl
class PainelBuilder {
    private val abas = mutableListOf<AbaPainel>()

    fun aba(
        nome: String,
        icone: IconeAba = IconeAba.INFO,
        bloco: AbaBuilder.() -> Unit
    ) {
        val builder = AbaBuilder().apply(bloco)
        abas.add(AbaPainel(nome, icone, builder.build()))
    }

    internal fun build(): List<AbaPainel> = abas.toList()
}

fun criarPainel(bloco: PainelBuilder.() -> Unit): List<AbaPainel> {
    return PainelBuilder().apply(bloco).build()
}

/**
 * Mira visual de sobreposição (overlay legítimo e não-clicável no centro da tela).
 */
class CrosshairOverlayView(context: Context) : View(context) {

    var crosshairStyle: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var crosshairColor: Int = Color.rgb(0xFF, 0x17, 0x24)
        set(value) {
            field = value
            strokePaint.color = value
            fillPaint.color = value
            invalidate()
        }

    var crosshairRadiusPx: Float = 18f * context.resources.displayMetrics.density
        set(value) {
            field = value
            invalidate()
        }

    private val density = context.resources.displayMetrics.density

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(180, 0, 0, 0)
        strokeWidth = 3.6f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = crosshairColor
        strokeWidth = 2.0f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = crosshairColor
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = crosshairRadiusPx.coerceAtMost(width / 2f - 4f * density)
        val gap = (r * 0.28f).coerceAtLeast(2f * density)

        when (crosshairStyle) {
            // 0: Cruz Clássica
            0 -> {
                canvas.drawLine(cx - r, cy, cx - gap, cy, outlinePaint)
                canvas.drawLine(cx + gap, cy, cx + r, cy, outlinePaint)
                canvas.drawLine(cx, cy - r, cx, cy - gap, outlinePaint)
                canvas.drawLine(cx, cy + gap, cx, cy + r, outlinePaint)

                canvas.drawLine(cx - r, cy, cx - gap, cy, strokePaint)
                canvas.drawLine(cx + gap, cy, cx + r, cy, strokePaint)
                canvas.drawLine(cx, cy - r, cx, cy - gap, strokePaint)
                canvas.drawLine(cx, cy + gap, cx, cy + r, strokePaint)
                canvas.drawCircle(cx, cy, 1.8f * density, fillPaint)
            }
            // 1: Ponto Central
            1 -> {
                val dotR = (r * 0.35f).coerceAtLeast(3f * density)
                canvas.drawCircle(cx, cy, dotR + 1.2f * density, outlinePaint)
                canvas.drawCircle(cx, cy, dotR, fillPaint)
            }
            // 2: Círculo + Ponto
            2 -> {
                canvas.drawCircle(cx, cy, r * 0.72f, outlinePaint)
                canvas.drawCircle(cx, cy, r * 0.72f, strokePaint)
                canvas.drawCircle(cx, cy, 2.0f * density, fillPaint)
            }
            // 3: Mira em T
            else -> {
                canvas.drawLine(cx - r, cy, cx - gap, cy, outlinePaint)
                canvas.drawLine(cx + gap, cy, cx + r, cy, outlinePaint)
                canvas.drawLine(cx, cy + gap, cx, cy + r, outlinePaint)

                canvas.drawLine(cx - r, cy, cx - gap, cy, strokePaint)
                canvas.drawLine(cx + gap, cy, cx + r, cy, strokePaint)
                canvas.drawLine(cx, cy + gap, cx, cy + r, strokePaint)
            }
        }
    }
}
