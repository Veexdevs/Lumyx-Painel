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

    private var snapBubbleToEdge = false
    private var isCompactMode = false
    private var panelAlpha = 0.95f
    private var bubbleSizeDp = 58

    private var snapAnimator: ValueAnimator? = null
    private var sessionStartElapsed = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (currentState == OverlayState.EXPANDED) {
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
            Toast.makeText(
                this,
                R.string.perm_missing,
                Toast.LENGTH_SHORT
            ).show()
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
                hideToBubble()
            }

            ACTION_TOGGLE -> {
                if (currentState == OverlayState.EXPANDED) {
                    ensureForeground(OverlayState.HIDDEN_BUBBLE)
                    hideToBubble()
                } else {
                    ensureForeground(OverlayState.EXPANDED)
                    showExpandedPanel()
                }
            }

            ACTION_CENTER -> {
                ensureForeground(
                    if (currentState == OverlayState.HIDDEN_BUBBLE) {
                        OverlayState.HIDDEN_BUBBLE
                    } else {
                        OverlayState.EXPANDED
                    }
                )
                centerActiveOverlay()
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
            if (isPanelAdded) {
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

        val (screenW, screenH) = getScreenBounds()
        val initialPanelW = dpToPx(292)
        val initialPanelH = dpToPx(290)
        val startX = ((screenW - initialPanelW) / 2).coerceAtLeast(dpToPx(12))
        val startY = ((screenH - initialPanelH) / 3).coerceAtLeast(dpToPx(48))

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(16)
            y = startY
        }

        val pBinding = LayoutFloatingPanelBinding.inflate(inflater)
        val bBinding = LayoutFloatingBubbleBinding.inflate(inflater)
        panelBinding = pBinding
        bubbleBinding = bBinding

        pBinding.panelCard.alpha = panelAlpha
        applyBubbleSize(bubbleSizeDp)

        // Configurar arraste completo da janela flutuante
        pBinding.panelRoot.positionProvider = { panelParams.x to panelParams.y }
        pBinding.panelRoot.onDragMove = { rawTargetX, rawTargetY ->
            val (w, h) = getPanelDimensions()
            val clamped = clampToScreen(rawTargetX, rawTargetY, w, h)
            panelParams.x = clamped.first
            panelParams.y = clamped.second
            safeUpdateView(pBinding.root, panelParams)
        }

        // Botões para esconder a janela flutuante (cabeçalho e rodapé)
        val hideClickListener = View.OnClickListener {
            hideToBubble()
        }
        pBinding.btnHeaderHide.setOnClickListener(hideClickListener)
        pBinding.btnHideWindow.setOnClickListener(hideClickListener)

        // Botões para fechar a janela flutuante (cabeçalho e rodapé)
        val closeClickListener = View.OnClickListener {
            closeAllAndStop()
        }
        pBinding.btnHeaderClose.setOnClickListener(closeClickListener)
        pBinding.btnClosePanel.setOnClickListener(closeClickListener)

        // Controle de opacidade da janela flutuante (35% .. 100%)
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

        // Controle de tamanho da bolinha flutuante (46dp .. 78dp)
        pBinding.seekBubbleSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                bubbleSizeDp = (46 + progress).coerceIn(46, 78)
                pBinding.tvBubbleSizeValue.text = String.format(Locale.US, "%ddp", bubbleSizeDp)
                applyBubbleSize(bubbleSizeDp)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        // Imã opcional nas bordas para a bolinha
        pBinding.switchSnapEdge.setOnCheckedChangeListener { _, isChecked ->
            snapBubbleToEdge = isChecked
        }

        // Modo compacto da janela flutuante
        pBinding.switchCompact.setOnCheckedChangeListener { _, isChecked ->
            isCompactMode = isChecked
            pBinding.settingsContainer.visibility = if (isChecked) View.GONE else View.VISIBLE
            pBinding.root.post {
                if (isPanelAdded) {
                    val (w, h) = getPanelDimensions()
                    val clamped = clampToScreen(panelParams.x, panelParams.y, w, h)
                    panelParams.x = clamped.first
                    panelParams.y = clamped.second
                    safeUpdateView(pBinding.root, panelParams)
                }
            }
        }

        // Configurar arraste completo e toque da bolinha escondida
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
            showExpandedPanel(fromBubble = true)
        }
    }

    private fun showExpandedPanel(fromBubble: Boolean = false) {
        val pBinding = panelBinding ?: return
        snapAnimator?.cancel()

        if (fromBubble) {
            val (w, h) = getPanelDimensions()
            val clamped = clampToScreen(bubbleParams.x, bubbleParams.y, w, h)
            panelParams.x = clamped.first
            panelParams.y = clamped.second
        } else {
            val (w, h) = getPanelDimensions()
            val clamped = clampToScreen(panelParams.x, panelParams.y, w, h)
            panelParams.x = clamped.first
            panelParams.y = clamped.second
        }

        if (isBubbleAdded) {
            safeRemoveView(bubbleBinding?.root)
            isBubbleAdded = false
        }

        if (!isPanelAdded) {
            pBinding.panelCard.animate().cancel()
            pBinding.panelCard.scaleX = 0.88f
            pBinding.panelCard.scaleY = 0.88f
            pBinding.panelCard.alpha = 0f
            if (safeAddView(pBinding.root, panelParams)) {
                isPanelAdded = true
                pBinding.panelCard.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(panelAlpha)
                    .setDuration(170L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        } else {
            safeUpdateView(pBinding.root, panelParams)
        }

        updateState(OverlayState.EXPANDED)
        updateNotification(OverlayState.EXPANDED)
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.post(timerRunnable)
    }

    private fun hideToBubble() {
        val bBinding = bubbleBinding ?: return
        mainHandler.removeCallbacks(timerRunnable)

        val sizePx = getBubbleOuterSizePx()
        val synced = clampToScreen(panelParams.x, panelParams.y, sizePx, sizePx)
        bubbleParams.x = if (snapBubbleToEdge) {
            computeNearestEdgeX(synced.first, sizePx)
        } else {
            synced.first
        }
        bubbleParams.y = synced.second

        if (isPanelAdded) {
            safeRemoveView(panelBinding?.root)
            isPanelAdded = false
        }

        if (!isBubbleAdded) {
            bBinding.bubbleContainer.animate().cancel()
            bBinding.bubbleContainer.scaleX = 0.65f
            bBinding.bubbleContainer.scaleY = 0.65f
            bBinding.bubbleContainer.alpha = 0f
            if (safeAddView(bBinding.root, bubbleParams)) {
                isBubbleAdded = true
                bBinding.bubbleContainer.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(160L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        } else {
            safeUpdateView(bBinding.root, bubbleParams)
        }

        updateState(OverlayState.HIDDEN_BUBBLE)
        updateNotification(OverlayState.HIDDEN_BUBBLE)
    }

    private fun centerActiveOverlay() {
        val (screenW, screenH) = getScreenBounds()
        if (isBubbleAdded) {
            val sizePx = getBubbleOuterSizePx()
            val clamped = clampToScreen(
                (screenW - sizePx) / 2,
                (screenH - sizePx) / 2,
                sizePx,
                sizePx
            )
            bubbleParams.x = clamped.first
            bubbleParams.y = clamped.second
            safeUpdateView(bubbleBinding?.root, bubbleParams)
        } else {
            val (w, h) = getPanelDimensions()
            val clamped = clampToScreen(
                (screenW - w) / 2,
                (screenH - h) / 3,
                w,
                h
            )
            panelParams.x = clamped.first
            panelParams.y = clamped.second
            if (isPanelAdded) {
                safeUpdateView(panelBinding?.root, panelParams)
            } else {
                showExpandedPanel()
            }
        }
    }

    private fun animateBubbleToNearestEdge(fromX: Int) {
        val bBinding = bubbleBinding ?: return
        val sizePx = getBubbleOuterSizePx()
        val targetX = computeNearestEdgeX(fromX, sizePx)
        if (fromX == targetX) return

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(fromX, targetX).apply {
            duration = 180L
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

        val iconPx = (px * 0.48f).roundToInt()
        val iconLp = bBinding.ivBubbleIcon.layoutParams
        iconLp.width = iconPx
        iconLp.height = iconPx
        bBinding.ivBubbleIcon.layoutParams = iconLp

        if (isBubbleAdded) {
            safeUpdateView(bBinding.root, bubbleParams)
        }
    }

    private fun getPanelDimensions(): Pair<Int, Int> {
        val root = panelBinding?.root
        val measuredW = root?.width ?: 0
        val measuredH = root?.height ?: 0
        val w = if (measuredW > 0) measuredW else dpToPx(292)
        val h = if (measuredH > 0) {
            measuredH
        } else if (isCompactMode) {
            dpToPx(165)
        } else {
            dpToPx(310)
        }
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
            .setSmallIcon(R.drawable.ic_lumyx_logo)
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
            // Usuário pode ter desativado notificações no Android 13+; overlay continua ativo
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }

    companion object {
        private const val CHANNEL_ID = "lumyx_overlay_channel"
        private const val NOTIFICATION_ID = 4010

        const val ACTION_OPEN = "com.veexdevs.lumyxpainel.ACTION_OPEN"
        const val ACTION_HIDE = "com.veexdevs.lumyxpainel.ACTION_HIDE"
        const val ACTION_SHOW = "com.veexdevs.lumyxpainel.ACTION_SHOW"
        const val ACTION_TOGGLE = "com.veexdevs.lumyxpainel.ACTION_TOGGLE"
        const val ACTION_CENTER = "com.veexdevs.lumyxpainel.ACTION_CENTER"
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
