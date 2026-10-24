package com.veexdevs.lumyxpainel

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.veexdevs.lumyxpainel.databinding.LayoutFloatingBubbleBinding
import com.veexdevs.lumyxpainel.databinding.LayoutFloatingPanelBinding
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

class FloatingWindowService : Service(), ControlePainel {

    enum class OverlayState {
        CLOSED,
        EXPANDED,
        HIDDEN_BUBBLE
    }

    override val context: Context
        get() = this

    private lateinit var windowManager: WindowManager
    private lateinit var notificationManager: NotificationManager

    private var panelBinding: LayoutFloatingPanelBinding? = null
    private var bubbleBinding: LayoutFloatingBubbleBinding? = null
    private var crosshairView: CrosshairOverlayView? = null

    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var crosshairParams: WindowManager.LayoutParams

    private var isPanelAdded = false
    private var isBubbleAdded = false
    private var isCrosshairAdded = false

    private var abasPainel: List<AbaPainel> = emptyList()
    private var selectedTabIndex = 0
    private val tabViews = mutableListOf<LinearLayout>()
    private val timerTextViews = mutableListOf<TextView>()

    private var snapBubbleToEdge = false
    private var panelAlpha = 0.98f
    private var bubbleSizeDp = 56

    private var snapAnimator: ValueAnimator? = null
    private var sessionStartElapsed = 0L

    private val prefs by lazy {
        getSharedPreferences("redx_painel_prefs", Context.MODE_PRIVATE)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (currentState != OverlayState.CLOSED) {
                val formatted = formatSessionTime()
                for (tv in timerTextViews) {
                    tv.text = formatted
                }
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        sessionStartElapsed = SystemClock.elapsedRealtime()
        abasPainel = runCatching { PainelConfig.configurarPainel() }.getOrElse { emptyList() }
        initOverlayViews()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_OPEN

        if (action == ACTION_CLOSE) {
            ensureForeground(OverlayState.CLOSED)
            closeAllAndStop()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            ensureForeground(OverlayState.CLOSED)
            Toast.makeText(this, R.string.toast_perm_needed, Toast.LENGTH_SHORT).show()
            closeAllAndStop()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_OPEN, ACTION_SHOW -> {
                ensureForeground(OverlayState.EXPANDED)
                showExpandedPanel()
            }

            ACTION_HIDE -> {
                ensureForeground(OverlayState.HIDDEN_BUBBLE)
                hidePanelKeepBubble()
            }

            ACTION_TOGGLE -> {
                if (currentState == OverlayState.EXPANDED) {
                    ensureForeground(OverlayState.HIDDEN_BUBBLE)
                    hidePanelKeepBubble()
                } else {
                    ensureForeground(OverlayState.EXPANDED)
                    showExpandedPanel()
                }
            }

            ACTION_CENTER -> {
                val activeState = if (currentState == OverlayState.HIDDEN_BUBBLE) {
                    OverlayState.HIDDEN_BUBBLE
                } else {
                    OverlayState.EXPANDED
                }
                ensureForeground(activeState)
                centralizarPainel()
            }

            ACTION_RESET -> {
                val activeState = if (currentState == OverlayState.HIDDEN_BUBBLE) {
                    OverlayState.HIDDEN_BUBBLE
                } else {
                    OverlayState.EXPANDED
                }
                ensureForeground(activeState)
                resetarPainel()
            }

            else -> {
                ensureForeground(OverlayState.EXPANDED)
                showExpandedPanel()
            }
        }

        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        snapAnimator?.cancel()
        mainHandler.post {
            if (isPanelAdded && currentState == OverlayState.EXPANDED) {
                val (w, h) = getPanelDimensions()
                val clamped = clampToScreen(panelParams.x, panelParams.y, w, h)
                panelParams.x = clamped.first
                panelParams.y = clamped.second
                safeUpdateView(panelBinding?.root, panelParams)
            }
            if (isBubbleAdded) {
                val sizePx = getBubbleOuterSizePx()
                val clamped = clampToScreen(bubbleParams.x, bubbleParams.y, sizePx, sizePx)
                bubbleParams.x = if (snapBubbleToEdge) {
                    computeNearestEdgeX(clamped.first, sizePx)
                } else {
                    clamped.first
                }
                bubbleParams.y = clamped.second
                safeUpdateView(bubbleBinding?.root, bubbleParams)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        snapAnimator?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        removeAllOverlays()
        updateState(OverlayState.CLOSED)
        super.onDestroy()
    }

    // =========================================================================
    // Implementação do ControlePainel (usado dentro do PainelConfig.kt)
    // =========================================================================

    override fun aviso(mensagem: String) {
        mainHandler.post {
            Toast.makeText(this, mensagem, Toast.LENGTH_SHORT).show()
        }
    }

    override fun vibrar(duracaoMs: Long) {
        runCatching {
            val ms = duracaoMs.coerceIn(10L, 500L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(ms)
                }
            }
        }
    }

    override fun mudarOpacidadePainel(porcentagem: Int) {
        val pct = porcentagem.coerceIn(35, 100)
        panelAlpha = pct / 100f
        panelBinding?.panelCard?.alpha = panelAlpha
    }

    override fun mudarTamanhoBolha(tamanhoDp: Int) {
        bubbleSizeDp = tamanhoDp.coerceIn(44, 84)
        applyBubbleSize(bubbleSizeDp)
    }

    override fun mudarImanBordas(ativo: Boolean) {
        snapBubbleToEdge = ativo
        if (ativo && isBubbleAdded) {
            animateBubbleToNearestEdge(bubbleParams.x)
        }
    }

    override fun mostrarMiraCentral(ativo: Boolean) {
        val view = crosshairView ?: return
        ensureBothOverlaysAttached()
        view.visibility = if (ativo) View.VISIBLE else View.GONE
    }

    override fun mudarEstiloMira(estilo: Int) {
        crosshairView?.crosshairStyle = estilo
    }

    override fun mudarTamanhoMira(tamanhoDp: Int) {
        val clampedDp = tamanhoDp.coerceIn(8, 48)
        crosshairView?.crosshairRadiusPx = clampedDp * resources.displayMetrics.density
    }

    override fun mudarCorMira(corHex: String) {
        runCatching {
            crosshairView?.crosshairColor = Color.parseColor(corHex)
        }
    }

    override fun centralizarPainel() {
        snapAnimator?.cancel()
        val (defaultPanel, defaultBubble) = computeDefaultPositions()

        bubbleParams.x = defaultBubble.first
        bubbleParams.y = defaultBubble.second
        if (isBubbleAdded) {
            safeUpdateView(bubbleBinding?.root, bubbleParams)
        }

        panelParams.x = defaultPanel.first
        panelParams.y = defaultPanel.second
        if (isPanelAdded && currentState == OverlayState.EXPANDED) {
            safeUpdateView(panelBinding?.root, panelParams)
        }
    }

    override fun esconderPainel() {
        hidePanelKeepBubble()
    }

    override fun fecharPainel() {
        closeAllAndStop()
    }

    override fun resetarPainel() {
        for (aba in abasPainel) {
            for (item in aba.itens) {
                when (item) {
                    is ItemPainel.Toggle -> {
                        if (item.ativo != item.padrao) {
                            item.ativo = item.padrao
                            safeExecute { item.aoMudar(this, item.ativo) }
                        }
                    }

                    is ItemPainel.Check -> {
                        if (item.marcado != item.padrao) {
                            item.marcado = item.padrao
                            safeExecute { item.aoMudar(this, item.marcado) }
                        }
                    }

                    is ItemPainel.Slide -> {
                        item.valorAtual = item.padrao
                        safeExecute { item.aoMudar(this, item.valorAtual) }
                    }

                    is ItemPainel.Lista -> {
                        item.indiceAtual = item.padrao
                        if (item.opcoes.isNotEmpty()) {
                            safeExecute {
                                item.aoSelecionar(
                                    this,
                                    item.indiceAtual,
                                    item.opcoes[item.indiceAtual]
                                )
                            }
                        }
                    }

                    is ItemPainel.Botao, is ItemPainel.Info, is ItemPainel.TempoAtivo -> Unit
                }
            }
        }

        sessionStartElapsed = SystemClock.elapsedRealtime()
        selectTab(0)
        centralizarPainel()
    }

    override fun abrirLink(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }.onFailure {
            aviso("Não foi possível abrir o link")
        }
    }

    override fun abrirApp(pacote: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(pacote)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            aviso("App não encontrado: $pacote")
        }
    }

    override fun copiarTexto(texto: String) {
        runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Redx Painel", texto))
            aviso("Copiado!")
        }
    }

    override fun salvarValor(chave: String, valor: String) {
        prefs.edit().putString(chave, valor).apply()
    }

    override fun lerValor(chave: String, padrao: String): String {
        return prefs.getString(chave, padrao) ?: padrao
    }

    // =========================================================================
    // Construção Dinâmica do Painel a partir do PainelConfig.kt
    // =========================================================================

    private fun initOverlayViews() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_LumyxPainel)
        val inflater = LayoutInflater.from(themedContext)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val (defaultPanelPos, defaultBubblePos) = computeDefaultPositions()

        crosshairParams = WindowManager.LayoutParams(
            dpToPx(104),
            dpToPx(104),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        crosshairView = CrosshairOverlayView(themedContext)

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = defaultPanelPos.first
            y = defaultPanelPos.second
        }

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = defaultBubblePos.first
            y = defaultBubblePos.second
        }

        val pBinding = LayoutFloatingPanelBinding.inflate(inflater)
        val bBinding = LayoutFloatingBubbleBinding.inflate(inflater)
        panelBinding = pBinding
        bubbleBinding = bBinding

        pBinding.tvPanelHeaderTitle.text = PainelConfig.TITULO_PAINEL
        pBinding.tvPanelHeaderSubtitle.text = PainelConfig.SUBTITULO_PAINEL
        pBinding.panelCard.alpha = panelAlpha
        applyBubbleSize(bubbleSizeDp)

        // Arraste livre da janela flutuante
        pBinding.panelRoot.positionProvider = { panelParams.x to panelParams.y }
        pBinding.panelRoot.onDragMove = { rawTargetX, rawTargetY ->
            if (currentState == OverlayState.EXPANDED) {
                val (w, h) = getPanelDimensions()
                val clamped = clampToScreen(rawTargetX, rawTargetY, w, h)
                panelParams.x = clamped.first
                panelParams.y = clamped.second
                safeUpdateView(pBinding.root, panelParams)
            }
        }

        pBinding.btnHeaderHide.setOnClickListener { hidePanelKeepBubble() }
        pBinding.btnHeaderClose.setOnClickListener { closeAllAndStop() }

        buildDynamicTabs(themedContext, pBinding)
        selectTab(0)

        // Bolha RX sempre visível (arrastável + clique rápido para abrir/esconder)
        bBinding.bubbleRoot.positionProvider = { bubbleParams.x to bubbleParams.y }
        bBinding.bubbleRoot.onDragMove = { rawTargetX, rawTargetY ->
            snapAnimator?.cancel()
            val sizePx = getBubbleOuterSizePx()
            val clamped = clampToScreen(rawTargetX, rawTargetY, sizePx, sizePx)
            bubbleParams.x = clamped.first
            bubbleParams.y = clamped.second
            safeUpdateView(bBinding.root, bubbleParams)
        }
        bBinding.bubbleRoot.onDragEnd = { finalX, _ ->
            if (snapBubbleToEdge && isBubbleAdded) {
                animateBubbleToNearestEdge(finalX)
            }
        }
        bBinding.bubbleRoot.setOnClickListener {
            if (currentState == OverlayState.EXPANDED) {
                hidePanelKeepBubble()
            } else {
                showExpandedPanel()
            }
        }
    }

    private fun buildDynamicTabs(themedContext: Context, pBinding: LayoutFloatingPanelBinding) {
        pBinding.tabsContainer.removeAllViews()
        tabViews.clear()

        for ((index, aba) in abasPainel.withIndex()) {
            val tabLayout = LinearLayout(themedContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = false
                setPadding(dpToPx(7), 0, dpToPx(6), 0)
                setBackgroundResource(
                    if (index == selectedTabIndex) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(30)
                ).apply {
                    if (index > 0) topMargin = dpToPx(8)
                }
                setOnClickListener {
                    selectTab(index)
                }
            }

            val iconView = ImageView(themedContext).apply {
                setImageResource(aba.icone.drawableRes)
                layoutParams = LinearLayout.LayoutParams(dpToPx(15), dpToPx(15))
            }

            val titleView = TextView(themedContext).apply {
                text = aba.nome
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dpToPx(6)
                }
            }

            tabLayout.addView(iconView)
            tabLayout.addView(titleView)
            pBinding.tabsContainer.addView(tabLayout)
            tabViews.add(tabLayout)
        }
    }

    private fun selectTab(tabIndex: Int) {
        val pBinding = panelBinding ?: return
        if (abasPainel.isEmpty()) return

        selectedTabIndex = tabIndex.coerceIn(0, abasPainel.lastIndex)
        for (i in tabViews.indices) {
            tabViews[i].setBackgroundResource(
                if (i == selectedTabIndex) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive
            )
        }

        renderTabItems(pBinding.root.context, pBinding.itemsContainer, abasPainel[selectedTabIndex])
        pBinding.itemsScrollView.scrollTo(0, 0)
    }

    private fun renderTabItems(
        ctx: Context,
        container: LinearLayout,
        aba: AbaPainel
    ) {
        container.removeAllViews()
        timerTextViews.clear()

        for ((idx, item) in aba.itens.withIndex()) {
            val itemView = when (item) {
                is ItemPainel.Botao -> createBotaoView(ctx, item)
                is ItemPainel.Toggle -> createToggleView(ctx, item)
                is ItemPainel.Check -> createCheckView(ctx, item)
                is ItemPainel.Slide -> createSlideView(ctx, item)
                is ItemPainel.Lista -> createListaView(ctx, item)
                is ItemPainel.Info -> createInfoView(ctx, item.nome, item.valor, isTimer = false)
                is ItemPainel.TempoAtivo -> createInfoView(ctx, item.nome, formatSessionTime(), isTimer = true)
            }

            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (idx > 0) topMargin = dpToPx(9)
            }
            container.addView(itemView, lp)
        }
    }

    private fun createBotaoView(ctx: Context, item: ItemPainel.Botao): View {
        val row = createBaseRedBar(ctx)
        val label = createBarTitle(ctx, item.nome)
        val badge = createBarBadge(ctx, item.textoDireita)

        row.addView(label)
        row.addView(badge)
        row.setOnClickListener {
            safeExecute { item.aoClicar(this) }
        }
        return row
    }

    private fun createToggleView(ctx: Context, item: ItemPainel.Toggle): View {
        val row = createBaseRedBar(ctx)
        val label = createBarTitle(ctx, item.nome)
        val badge = createBarBadge(ctx, "")

        fun syncVisual() {
            row.setBackgroundResource(
                if (item.ativo) R.drawable.bg_red_bar_active else R.drawable.bg_red_bar
            )
            badge.setBackgroundResource(
                if (item.ativo) R.drawable.bg_state_badge_on else R.drawable.bg_state_badge_off
            )
            badge.text = if (item.ativo) "ON" else "OFF"
            badge.setTextColor(
                if (item.ativo) Color.rgb(0xD9, 0x06, 0x0E) else Color.WHITE
            )
        }
        syncVisual()

        row.addView(label)
        row.addView(badge)
        row.setOnClickListener {
            item.ativo = !item.ativo
            syncVisual()
            safeExecute { item.aoMudar(this, item.ativo) }
        }
        return row
    }

    private fun createCheckView(ctx: Context, item: ItemPainel.Check): View {
        val row = createBaseRedBar(ctx)
        val label = createBarTitle(ctx, item.nome)
        val badge = createBarBadge(ctx, "")

        fun syncVisual() {
            row.setBackgroundResource(
                if (item.marcado) R.drawable.bg_red_bar_active else R.drawable.bg_red_bar
            )
            badge.setBackgroundResource(
                if (item.marcado) R.drawable.bg_state_badge_on else R.drawable.bg_state_badge_off
            )
            badge.text = if (item.marcado) "[✓]" else "[ ]"
            badge.setTextColor(
                if (item.marcado) Color.rgb(0xD9, 0x06, 0x0E) else Color.WHITE
            )
        }
        syncVisual()

        row.addView(label)
        row.addView(badge)
        row.setOnClickListener {
            item.marcado = !item.marcado
            syncVisual()
            safeExecute { item.aoMudar(this, item.marcado) }
        }
        return row
    }

    private fun createSlideView(ctx: Context, item: ItemPainel.Slide): View {
        val row = createBaseRedBar(ctx)

        val label = TextView(ctx).apply {
            text = item.nome
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            maxLines = 1
        }

        val valueTv = TextView(ctx).apply {
            text = "${item.valorAtual}${item.sufixo}"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.END
            minWidth = dpToPx(32)
        }

        val range = (item.max - item.min).coerceAtLeast(1)
        val seekBar = SeekBar(ctx).apply {
            max = range
            progress = (item.valorAtual - item.min).coerceIn(0, range)
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(0x66, 0, 0, 0))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val currentVal = (item.min + progress).coerceIn(item.min, item.max)
                    item.valorAtual = currentVal
                    valueTv.text = "${currentVal}${item.sufixo}"
                    if (fromUser) {
                        safeExecute { item.aoMudar(this@FloatingWindowService, currentVal) }
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }

        row.addView(label)
        row.addView(seekBar)
        row.addView(valueTv)
        return row
    }

    private fun createListaView(ctx: Context, item: ItemPainel.Lista): View {
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val headerRow = createBaseRedBar(ctx)
        val label = createBarTitle(ctx, "${item.nome} ▾")
        val badge = createBarBadge(ctx, "◀ ${item.opcoes[item.indiceAtual]} ▶")

        val dropdownBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundResource(R.drawable.bg_list_dropdown)
            setPadding(dpToPx(6), dpToPx(4), dpToPx(6), dpToPx(4))
        }

        val optionViews = mutableListOf<TextView>()

        fun refreshSelectionUi() {
            val selectedText = item.opcoes[item.indiceAtual]
            badge.text = "◀ $selectedText ▶"
            for ((optIdx, tv) in optionViews.withIndex()) {
                val isSelected = optIdx == item.indiceAtual
                tv.text = if (isSelected) "● ${item.opcoes[optIdx]}" else "  ${item.opcoes[optIdx]}"
                tv.setTextColor(
                    if (isSelected) Color.rgb(0xFF, 0x3B, 0x47) else Color.WHITE
                )
            }
        }

        for ((optIdx, optText) in item.opcoes.withIndex()) {
            val optTv = TextView(ctx).apply {
                text = optText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
                setBackgroundResource(R.drawable.bg_list_option_item)
                isClickable = true
                isFocusable = false
                setOnClickListener {
                    item.indiceAtual = optIdx
                    refreshSelectionUi()
                    dropdownBox.visibility = View.GONE
                    safeExecute { item.aoSelecionar(this@FloatingWindowService, optIdx, optText) }
                }
            }
            optionViews.add(optTv)
            dropdownBox.addView(
                optTv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        refreshSelectionUi()

        // Tocar no selo ◀ Opção ▶ alterna rapidamente para o próximo item
        badge.isClickable = true
        badge.setOnClickListener {
            item.indiceAtual = (item.indiceAtual + 1) % item.opcoes.size
            refreshSelectionUi()
            val chosen = item.opcoes[item.indiceAtual]
            safeExecute { item.aoSelecionar(this, item.indiceAtual, chosen) }
        }

        // Tocar na barra abre/fecha a lista completa de opções logo abaixo
        headerRow.setOnClickListener {
            dropdownBox.visibility =
                if (dropdownBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        headerRow.addView(label)
        headerRow.addView(badge)

        wrapper.addView(
            headerRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(38)
            )
        )
        wrapper.addView(
            dropdownBox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return wrapper
    }

    private fun createInfoView(
        ctx: Context,
        nome: String,
        valorInicial: String,
        isTimer: Boolean
    ): View {
        val row = createBaseRedBar(ctx).apply {
            isClickable = false
        }
        val label = createBarTitle(ctx, nome)
        val badge = createBarBadge(ctx, valorInicial)
        if (isTimer) {
            timerTextViews.add(badge)
        }
        row.addView(label)
        row.addView(badge)
        return row
    }

    private fun createBaseRedBar(ctx: Context): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpToPx(38)
            setPadding(dpToPx(10), dpToPx(4), dpToPx(8), dpToPx(4))
            setBackgroundResource(R.drawable.bg_red_bar)
            isClickable = true
            isFocusable = false
        }
    }

    private fun createBarTitle(ctx: Context, title: String): TextView {
        return TextView(ctx).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
    }

    private fun createBarBadge(ctx: Context, label: String): TextView {
        return TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_state_badge_off)
            setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
        }
    }

    private inline fun safeExecute(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            aviso("Erro na ação: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    // =========================================================================
    // Gerenciamento de Janelas (Painel + Bolha RX Sempre Visível)
    // =========================================================================

    private fun ensureBothOverlaysAttached() {
        val pBinding = panelBinding ?: return
        val bBinding = bubbleBinding ?: return
        val cView = crosshairView

        if (cView != null && !isCrosshairAdded) {
            cView.visibility = View.GONE
            if (safeAddView(cView, crosshairParams)) {
                isCrosshairAdded = true
            }
        }
        if (!isPanelAdded) {
            if (safeAddView(pBinding.root, panelParams)) {
                isPanelAdded = true
            }
        }
        if (!isBubbleAdded) {
            bBinding.root.visibility = View.VISIBLE
            if (safeAddView(bBinding.root, bubbleParams)) {
                isBubbleAdded = true
            }
        } else {
            bBinding.root.visibility = View.VISIBLE
        }
    }

    private fun showExpandedPanel() {
        val pBinding = panelBinding ?: return
        val bBinding = bubbleBinding ?: return

        val (w, h) = getPanelDimensions()
        val clamped = clampToScreen(panelParams.x, panelParams.y, w, h)
        panelParams.x = clamped.first
        panelParams.y = clamped.second
        panelParams.width = WindowManager.LayoutParams.WRAP_CONTENT
        panelParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        panelParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        ensureBothOverlaysAttached()

        pBinding.root.visibility = View.VISIBLE
        pBinding.panelCard.alpha = panelAlpha
        safeUpdateView(pBinding.root, panelParams)

        bBinding.root.visibility = View.VISIBLE
        bBinding.viewBubbleDot.alpha = 1f

        updateState(OverlayState.EXPANDED)
        updateNotification(OverlayState.EXPANDED)
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.post(timerRunnable)
    }

    private fun hidePanelKeepBubble() {
        val pBinding = panelBinding ?: return
        val bBinding = bubbleBinding ?: return

        ensureBothOverlaysAttached()

        pBinding.root.visibility = View.GONE
        panelParams.width = 0
        panelParams.height = 0
        panelParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        safeUpdateView(pBinding.root, panelParams)

        bBinding.root.visibility = View.VISIBLE
        bBinding.viewBubbleDot.alpha = 0.75f

        updateState(OverlayState.HIDDEN_BUBBLE)
        updateNotification(OverlayState.HIDDEN_BUBBLE)
    }

    private fun computeDefaultPositions(): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val (screenW, screenH) = getScreenBounds()
        val estW = dpToPx(348)
        val estH = dpToPx(240)
        val bubbleOuter = getBubbleOuterSizePx()

        val bubbleX = dpToPx(12)
        val bubbleY = dpToPx(44)

        val rawPanelX = (screenW - estW) / 2
        val rawPanelY = if (screenH > screenW) {
            (bubbleY + bubbleOuter + dpToPx(12)).coerceAtLeast((screenH - estH) / 4)
        } else {
            ((screenH - estH) / 2).coerceAtLeast(dpToPx(24))
        }

        val clampedPanel = clampToScreen(rawPanelX, rawPanelY, estW, estH)
        val clampedBubble = clampToScreen(bubbleX, bubbleY, bubbleOuter, bubbleOuter)
        return clampedPanel to clampedBubble
    }

    private fun animateBubbleToNearestEdge(fromX: Int) {
        val bBinding = bubbleBinding ?: return
        val sizePx = getBubbleOuterSizePx()
        val targetX = computeNearestEdgeX(fromX, sizePx)
        if (fromX == targetX) return

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(fromX, targetX).apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                if (isBubbleAdded) {
                    bubbleParams.x = anim.animatedValue as Int
                    safeUpdateView(bBinding.root, bubbleParams)
                }
            }
            start()
        }
    }

    private fun computeNearestEdgeX(currentX: Int, viewWidth: Int): Int {
        val (screenW, _) = getScreenBounds()
        val margin = dpToPx(8)
        return if (currentX + viewWidth / 2 < screenW / 2) {
            margin
        } else {
            (screenW - viewWidth - margin).coerceAtLeast(0)
        }
    }

    private fun applyBubbleSize(sizeDp: Int) {
        val bBinding = bubbleBinding ?: return
        val px = dpToPx(sizeDp)
        val lp = bBinding.bubbleContainer.layoutParams
        lp.width = px
        lp.height = px
        bBinding.bubbleContainer.layoutParams = lp

        val iconPx = (px * 0.62f).roundToInt()
        val iconLp = bBinding.ivBubbleIcon.layoutParams
        iconLp.width = iconPx
        iconLp.height = iconPx
        bBinding.ivBubbleIcon.layoutParams = iconLp

        if (isBubbleAdded) {
            safeUpdateView(bBinding.root, bubbleParams)
        }
    }

    private fun formatSessionTime(): String {
        val elapsedSec = ((SystemClock.elapsedRealtime() - sessionStartElapsed) / 1000L)
            .coerceAtLeast(0L)
        val minutes = elapsedSec / 60L
        val seconds = elapsedSec % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun getPanelDimensions(): Pair<Int, Int> {
        val card = panelBinding?.panelCard
        val measuredW = card?.width ?: 0
        val measuredH = card?.height ?: 0
        val pad = dpToPx(8)
        val w = if (measuredW > 0) measuredW + pad else dpToPx(348)
        val h = if (measuredH > 0) measuredH + pad else dpToPx(240)
        return w to h
    }

    private fun getBubbleOuterSizePx(): Int {
        return dpToPx(bubbleSizeDp + 8)
    }

    private fun getScreenBounds(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun clampToScreen(x: Int, y: Int, viewWidth: Int, viewHeight: Int): Pair<Int, Int> {
        val (screenW, screenH) = getScreenBounds()
        val maxX = (screenW - viewWidth).coerceAtLeast(0)
        val maxY = (screenH - viewHeight - dpToPx(24)).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    private fun safeAddView(view: View?, params: WindowManager.LayoutParams): Boolean {
        if (view == null) return false
        return try {
            if (view.parent == null) {
                windowManager.addView(view, params)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun safeUpdateView(view: View?, params: WindowManager.LayoutParams) {
        if (view == null || view.parent == null) return
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Ignora se a view foi destacada entre frames
        }
    }

    private fun safeRemoveView(view: View?) {
        if (view == null) return
        try {
            if (view.parent != null) {
                windowManager.removeView(view)
            }
        } catch (_: Exception) {
            // Ignora se já removida
        }
    }

    private fun removeAllOverlays() {
        if (isCrosshairAdded) {
            safeRemoveView(crosshairView)
            isCrosshairAdded = false
        }
        if (isPanelAdded) {
            safeRemoveView(panelBinding?.root)
            isPanelAdded = false
        }
        if (isBubbleAdded) {
            safeRemoveView(bubbleBinding?.root)
            isBubbleAdded = false
        }
    }

    private fun closeAllAndStop() {
        snapAnimator?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        removeAllOverlays()
        updateState(OverlayState.CLOSED)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(state: OverlayState): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPending = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, FloatingWindowService::class.java).apply {
            action = ACTION_TOGGLE
        }
        val togglePending = PendingIntent.getService(
            this,
            101,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val closeIntent = Intent(this, FloatingWindowService::class.java).apply {
            action = ACTION_CLOSE
        }
        val closePending = PendingIntent.getService(
            this,
            102,
            closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isHidden = state == OverlayState.HIDDEN_BUBBLE
        val bodyText = if (isHidden) {
            getString(R.string.notif_body_hidden)
        } else {
            getString(R.string.notif_body_expanded)
        }
        val toggleLabel = if (isHidden) {
            getString(R.string.notif_action_show)
        } else {
            getString(R.string.notif_action_hide)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_redx)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(bodyText)
            .setContentIntent(openAppPending)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, toggleLabel, togglePending)
            .addAction(0, getString(R.string.notif_action_close), closePending)
            .build()
    }

    private fun ensureForeground(state: OverlayState) {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: OverlayState) {
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
        } catch (_: SecurityException) {
            // Permissão de notificação opcional no Android 13+
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }

    companion object {
        private const val CHANNEL_ID = "redx_overlay_channel"
        private const val NOTIFICATION_ID = 4010

        const val ACTION_OPEN = "com.veexdevs.lumyxpainel.ACTION_OPEN"
        const val ACTION_HIDE = "com.veexdevs.lumyxpainel.ACTION_HIDE"
        const val ACTION_SHOW = "com.veexdevs.lumyxpainel.ACTION_SHOW"
        const val ACTION_TOGGLE = "com.veexdevs.lumyxpainel.ACTION_TOGGLE"
        const val ACTION_CENTER = "com.veexdevs.lumyxpainel.ACTION_CENTER"
        const val ACTION_RESET = "com.veexdevs.lumyxpainel.ACTION_RESET"
        const val ACTION_CLOSE = "com.veexdevs.lumyxpainel.ACTION_CLOSE"

        @Volatile
        var currentState: OverlayState = OverlayState.CLOSED
            private set

        private val stateListeners = CopyOnWriteArraySet<(OverlayState) -> Unit>()

        fun addStateListener(listener: (OverlayState) -> Unit) {
            stateListeners.add(listener)
            listener(currentState)
        }

        fun removeStateListener(listener: (OverlayState) -> Unit) {
            stateListeners.remove(listener)
        }

        private fun updateState(newState: OverlayState) {
            currentState = newState
            for (listener in stateListeners) {
                listener(newState)
            }
        }

        fun open(context: Context) = dispatch(context, ACTION_OPEN)
        fun show(context: Context) = dispatch(context, ACTION_SHOW)
        fun hide(context: Context) = dispatch(context, ACTION_HIDE)
        fun toggle(context: Context) = dispatch(context, ACTION_TOGGLE)
        fun center(context: Context) = dispatch(context, ACTION_CENTER)

        fun reset(context: Context) {
            if (currentState == OverlayState.CLOSED) return
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_RESET
            }
            context.startService(intent)
        }

        fun close(context: Context) {
            if (currentState == OverlayState.CLOSED) return
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_CLOSE
            }
            context.startService(intent)
        }

        private fun dispatch(context: Context, actionName: String) {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = actionName
            }
            if (currentState == OverlayState.CLOSED) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
