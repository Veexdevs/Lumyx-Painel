package com.veexdevs.lumyxpainel

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
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

class FloatingWindowService : Service() {

    enum class OverlayState {
        CLOSED,
        EXPANDED,
        HIDDEN_BUBBLE
    }

    private lateinit var windowManager: WindowManager
    private lateinit var notificationManager: NotificationManager

    private var panelBinding: LayoutFloatingPanelBinding? = null
    private var bubbleBinding: LayoutFloatingBubbleBinding? = null

    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var bubbleParams: WindowManager.LayoutParams

    private var isPanelAdded = false
    private var isBubbleAdded = false

    private var aimAssistEnabled = false
    private var noRecoilEnabled = false
    private var precisionEnabled = false
    private var fastTouchEnabled = false
    private var snapBubbleToEdge = false

    private var panelAlpha = 0.98f
    private var bubbleSizeDp = 56

    private var snapAnimator: ValueAnimator? = null
    private var sessionStartElapsed = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (currentState != OverlayState.CLOSED) {
                val elapsedSec = ((SystemClock.elapsedRealtime() - sessionStartElapsed) / 1000L)
                    .coerceAtLeast(0L)
                val minutes = elapsedSec / 60L
                val seconds = elapsedSec % 60L
                panelBinding?.tvSessionTimer?.text =
                    String.format(Locale.US, "%02d:%02d", minutes, seconds)
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
                centerAllOverlays()
            }

            ACTION_RESET -> {
                val activeState = if (currentState == OverlayState.HIDDEN_BUBBLE) {
                    OverlayState.HIDDEN_BUBBLE
                } else {
                    OverlayState.EXPANDED
                }
                ensureForeground(activeState)
                resetPanelDefaults()
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

        pBinding.panelCard.alpha = panelAlpha
        applyBubbleSize(bubbleSizeDp)

        // Arraste livre da janela flutuante inteira
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

        // Botão [—]: Esconde instantaneamente o painel mantendo a bolha RX visível
        pBinding.btnHeaderHide.setOnClickListener {
            hidePanelKeepBubble()
        }

        // Botão [✕]: Fecha o painel e a bolha completamente
        pBinding.btnHeaderClose.setOnClickListener {
            closeAllAndStop()
        }

        // Abas laterais (Mira, Sensi, Tela, Info)
        pBinding.tabAim.setOnClickListener { selectTab(0) }
        pBinding.tabSensi.setOnClickListener { selectTab(1) }
        pBinding.tabScreen.setOnClickListener { selectTab(2) }
        pBinding.tabInfo.setOnClickListener { selectTab(3) }

        // Barras interativas da aba Mira
        pBinding.barAimAssist.setOnClickListener {
            aimAssistEnabled = !aimAssistEnabled
            applyToggleBarState(pBinding.barAimAssist, pBinding.badgeAimAssist, aimAssistEnabled)
        }
        pBinding.barNoRecoil.setOnClickListener {
            noRecoilEnabled = !noRecoilEnabled
            applyToggleBarState(pBinding.barNoRecoil, pBinding.badgeNoRecoil, noRecoilEnabled)
        }
        pBinding.barPrecision.setOnClickListener {
            precisionEnabled = !precisionEnabled
            applyToggleBarState(pBinding.barPrecision, pBinding.badgePrecision, precisionEnabled)
        }

        // Barras interativas da aba Sensi
        pBinding.seekSensiGeneral.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                pBinding.tvSensiGeneralVal.text = String.format(Locale.US, "%d", progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        pBinding.seekSensiRedDot.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                pBinding.tvSensiRedDotVal.text = String.format(Locale.US, "%d", progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        pBinding.barFastTouch.setOnClickListener {
            fastTouchEnabled = !fastTouchEnabled
            applyToggleBarState(pBinding.barFastTouch, pBinding.badgeFastTouch, fastTouchEnabled)
        }

        // Barras interativas da aba Tela (Opacidade, Tamanho da Bolha RX, Imã nas Bordas)
        pBinding.seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = (35 + progress).coerceIn(35, 100)
                panelAlpha = pct / 100f
                pBinding.tvOpacityValue.text = String.format(Locale.US, "%d%%", pct)
                pBinding.panelCard.alpha = panelAlpha
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        pBinding.seekBubbleSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                bubbleSizeDp = (46 + progress).coerceIn(46, 78)
                pBinding.tvBubbleSizeValue.text = String.format(Locale.US, "%ddp", bubbleSizeDp)
                applyBubbleSize(bubbleSizeDp)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        pBinding.barSnapEdge.setOnClickListener {
            snapBubbleToEdge = !snapBubbleToEdge
            applyToggleBarState(pBinding.barSnapEdge, pBinding.badgeSnapEdge, snapBubbleToEdge)
            if (snapBubbleToEdge && isBubbleAdded) {
                animateBubbleToNearestEdge(bubbleParams.x)
            }
        }

        // Barras de ação da aba Info
        pBinding.barCenterPanel.setOnClickListener {
            centerAllOverlays()
        }
        pBinding.barResetAll.setOnClickListener {
            resetPanelDefaults()
        }

        // Bolha RX sempre visível: arrastável + toque alterna abrir/esconder instantaneamente
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

    /**
     * Garante que o painel seja adicionado primeiro e a bolha RX por último,
     * mantendo a bolha sempre visível e acima do painel (Z-order superior) para abrir/fechar rápido.
     */
    private fun ensureBothOverlaysAttached() {
        val pBinding = panelBinding ?: return
        val bBinding = bubbleBinding ?: return

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

        // Bolha continua visível na tela mesmo com o painel aberto
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

        // Esconde o painel instantaneamente sem remover a view, liberando 100% do toque da tela
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

    private fun selectTab(tabIndex: Int) {
        val pBinding = panelBinding ?: return
        val tabs = listOf(pBinding.tabAim, pBinding.tabSensi, pBinding.tabScreen, pBinding.tabInfo)
        val pages = listOf(pBinding.pageAim, pBinding.pageSensi, pBinding.pageScreen, pBinding.pageInfo)

        for (i in tabs.indices) {
            val active = (i == tabIndex)
            tabs[i].setBackgroundResource(
                if (active) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive
            )
            pages[i].visibility = if (active) View.VISIBLE else View.GONE
        }
    }

    private fun applyToggleBarState(bar: View, badge: TextView, enabled: Boolean) {
        bar.setBackgroundResource(
            if (enabled) R.drawable.bg_red_bar_active else R.drawable.bg_red_bar
        )
        badge.setBackgroundResource(
            if (enabled) R.drawable.bg_state_badge_on else R.drawable.bg_state_badge_off
        )
        badge.setText(if (enabled) R.string.state_on else R.string.state_off)
        badge.setTextColor(
            if (enabled) Color.rgb(0xD9, 0x06, 0x0E) else Color.WHITE
        )
    }

    private fun computeDefaultPositions(): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val (screenW, screenH) = getScreenBounds()
        val estW = dpToPx(344)
        val estH = dpToPx(235)
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

    private fun centerAllOverlays() {
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

    private fun resetPanelDefaults() {
        val pBinding = panelBinding ?: return

        aimAssistEnabled = false
        noRecoilEnabled = false
        precisionEnabled = false
        fastTouchEnabled = false
        snapBubbleToEdge = false

        applyToggleBarState(pBinding.barAimAssist, pBinding.badgeAimAssist, false)
        applyToggleBarState(pBinding.barNoRecoil, pBinding.badgeNoRecoil, false)
        applyToggleBarState(pBinding.barPrecision, pBinding.badgePrecision, false)
        applyToggleBarState(pBinding.barFastTouch, pBinding.badgeFastTouch, false)
        applyToggleBarState(pBinding.barSnapEdge, pBinding.badgeSnapEdge, false)

        pBinding.seekSensiGeneral.progress = 165
        pBinding.tvSensiGeneralVal.text = "165"
        pBinding.seekSensiRedDot.progress = 150
        pBinding.tvSensiRedDotVal.text = "150"

        pBinding.seekOpacity.progress = 63
        panelAlpha = 0.98f
        pBinding.tvOpacityValue.text = "98%"
        pBinding.panelCard.alpha = panelAlpha

        pBinding.seekBubbleSize.progress = 10
        bubbleSizeDp = 56
        pBinding.tvBubbleSizeValue.text = "56dp"
        applyBubbleSize(56)

        sessionStartElapsed = SystemClock.elapsedRealtime()
        pBinding.tvSessionTimer.text = "00:00"

        selectTab(0)
        centerAllOverlays()
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

    private fun getPanelDimensions(): Pair<Int, Int> {
        val card = panelBinding?.panelCard
        val measuredW = card?.width ?: 0
        val measuredH = card?.height ?: 0
        val pad = dpToPx(8)
        val w = if (measuredW > 0) measuredW + pad else dpToPx(344)
        val h = if (measuredH > 0) measuredH + pad else dpToPx(235)
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
