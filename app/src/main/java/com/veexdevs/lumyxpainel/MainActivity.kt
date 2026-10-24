package com.veexdevs.lumyxpainel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.veexdevs.lumyxpainel.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var openAfterPermissionGrant = false

    private val overlayStateListener: (FloatingWindowService.OverlayState) -> Unit = { state ->
        runOnUiThread {
            renderOverlayState(state)
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshPermissionUi()
        if (Settings.canDrawOverlays(this) && openAfterPermissionGrant) {
            openAfterPermissionGrant = false
            FloatingWindowService.open(this)
        } else {
            openAfterPermissionGrant = false
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Permissão opcional no Android 13+; serviço funciona normalmente de qualquer forma
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        setupClickListeners()
        refreshPermissionUi()
        renderOverlayState(FloatingWindowService.currentState)
    }

    override fun onStart() {
        super.onStart()
        FloatingWindowService.addStateListener(overlayStateListener)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUi()
    }

    override fun onStop() {
        FloatingWindowService.removeStateListener(overlayStateListener)
        super.onStop()
    }

    private fun setupClickListeners() {
        binding.btnGrantPermission.setOnClickListener {
            openAfterPermissionGrant = false
            launchOverlayPermissionSettings()
        }

        binding.btnOpenWindow.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                openAfterPermissionGrant = true
                Toast.makeText(
                    this,
                    R.string.perm_missing,
                    Toast.LENGTH_LONG
                ).show()
                launchOverlayPermissionSettings()
                return@setOnClickListener
            }
            FloatingWindowService.open(this)
        }

        binding.btnToggleHide.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                openAfterPermissionGrant = true
                launchOverlayPermissionSettings()
                return@setOnClickListener
            }
            FloatingWindowService.toggle(this)
        }

        binding.btnCenterWindow.setOnClickListener {
            if (FloatingWindowService.currentState != FloatingWindowService.OverlayState.CLOSED) {
                FloatingWindowService.center(this)
            }
        }

        binding.btnCloseWindow.setOnClickListener {
            FloatingWindowService.close(this)
        }
    }

    private fun refreshPermissionUi() {
        val granted = Settings.canDrawOverlays(this)
        if (granted) {
            binding.tvPermissionState.setText(R.string.perm_granted)
            binding.tvPermissionState.setTextColor(
                ContextCompat.getColor(this, R.color.lumyx_success)
            )
            binding.btnGrantPermission.visibility = View.GONE
        } else {
            binding.tvPermissionState.setText(R.string.perm_missing)
            binding.tvPermissionState.setTextColor(
                ContextCompat.getColor(this, R.color.lumyx_warning)
            )
            binding.btnGrantPermission.visibility = View.VISIBLE
        }
    }

    private fun renderOverlayState(state: FloatingWindowService.OverlayState) {
        when (state) {
            FloatingWindowService.OverlayState.CLOSED -> {
                binding.tvStatusBadge.setText(R.string.status_closed)
                binding.tvStatusBadge.setTextColor(
                    ContextCompat.getColor(this, R.color.text_secondary)
                )
                binding.btnToggleHide.setText(R.string.btn_hide_window)
                binding.btnToggleHide.setIconResource(R.drawable.ic_visibility_off)
                binding.btnToggleHide.isEnabled = false
                binding.btnToggleHide.alpha = 0.5f
                binding.btnCenterWindow.isEnabled = false
                binding.btnCenterWindow.alpha = 0.5f
                binding.btnCloseWindow.isEnabled = false
                binding.btnCloseWindow.alpha = 0.5f
            }

            FloatingWindowService.OverlayState.EXPANDED -> {
                binding.tvStatusBadge.setText(R.string.status_open)
                binding.tvStatusBadge.setTextColor(
                    ContextCompat.getColor(this, R.color.lumyx_success)
                )
                binding.btnToggleHide.setText(R.string.btn_hide_window)
                binding.btnToggleHide.setIconResource(R.drawable.ic_visibility_off)
                binding.btnToggleHide.isEnabled = true
                binding.btnToggleHide.alpha = 1f
                binding.btnCenterWindow.isEnabled = true
                binding.btnCenterWindow.alpha = 1f
                binding.btnCloseWindow.isEnabled = true
                binding.btnCloseWindow.alpha = 1f
            }

            FloatingWindowService.OverlayState.HIDDEN_BUBBLE -> {
                binding.tvStatusBadge.setText(R.string.status_hidden)
                binding.tvStatusBadge.setTextColor(
                    ContextCompat.getColor(this, R.color.lumyx_accent)
                )
                binding.btnToggleHide.setText(R.string.btn_show_window)
                binding.btnToggleHide.setIconResource(R.drawable.ic_visibility_on)
                binding.btnToggleHide.isEnabled = true
                binding.btnToggleHide.alpha = 1f
                binding.btnCenterWindow.isEnabled = true
                binding.btnCenterWindow.alpha = 1f
                binding.btnCloseWindow.isEnabled = true
                binding.btnCloseWindow.alpha = 1f
            }
        }
    }

    private fun launchOverlayPermissionSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } catch (_: Exception) {
            overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
