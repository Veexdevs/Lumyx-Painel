package com.veexdevs.lumyxpainel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.veexdevs.lumyxpainel.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var openAfterPermissionGrant = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this) && openAfterPermissionGrant) {
            openAfterPermissionGrant = false
            FloatingWindowService.open(this)
            Toast.makeText(this, R.string.toast_panel_activated, Toast.LENGTH_SHORT).show()
        } else {
            openAfterPermissionGrant = false
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Permissão opcional no Android 13+
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyRedxVisualTheme()
        requestNotificationPermissionIfNeeded()
        setupClickListeners()
    }

    private fun applyRedxVisualTheme() {
        val density = resources.displayMetrics.density
        binding.mainScrollRoot.background = RedxUiDrawables.createScreenBackground()

        val pillButtons = listOf(
            binding.btnOverlayPermission,
            binding.btnActivatePanel,
            binding.btnDeactivatePanel,
            binding.btnResetPosition,
            binding.btnResetPanel,
            binding.btnVersionInfo,
            binding.btnDiscordLink,
            binding.btnTiktokLink,
            binding.btnYoutubeLink
        )
        for (pill in pillButtons) {
            pill.background = RedxUiDrawables.createPillButtonBackground(density)
        }
    }

    private fun setupClickListeners() {
        binding.btnOverlayPermission.setOnClickListener {
            openAfterPermissionGrant = false
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(
                    this,
                    R.string.toast_perm_already_granted,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                launchOverlayPermissionSettings()
            }
        }

        binding.btnOverlayPermission.setOnLongClickListener {
            openAfterPermissionGrant = false
            launchOverlayPermissionSettings()
            true
        }

        binding.btnActivatePanel.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                openAfterPermissionGrant = true
                Toast.makeText(this, R.string.toast_perm_needed, Toast.LENGTH_LONG).show()
                launchOverlayPermissionSettings()
                return@setOnClickListener
            }
            FloatingWindowService.open(this)
            Toast.makeText(this, R.string.toast_panel_activated, Toast.LENGTH_SHORT).show()
        }

        binding.btnDeactivatePanel.setOnClickListener {
            FloatingWindowService.close(this)
            Toast.makeText(this, R.string.toast_panel_deactivated, Toast.LENGTH_SHORT).show()
        }

        binding.btnResetPosition.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                openAfterPermissionGrant = true
                Toast.makeText(this, R.string.toast_perm_needed, Toast.LENGTH_LONG).show()
                launchOverlayPermissionSettings()
                return@setOnClickListener
            }
            if (FloatingWindowService.currentState != FloatingWindowService.OverlayState.CLOSED) {
                FloatingWindowService.center(this)
            }
            Toast.makeText(this, R.string.toast_position_reset, Toast.LENGTH_SHORT).show()
        }

        binding.btnResetPanel.setOnClickListener {
            FloatingWindowService.reset(this)
            Toast.makeText(this, R.string.toast_panel_reset, Toast.LENGTH_SHORT).show()
        }

        binding.btnVersionInfo.setOnClickListener {
            Toast.makeText(this, R.string.toast_version_info, Toast.LENGTH_SHORT).show()
        }

        binding.btnDiscordLink.setOnClickListener {
            openExternalUrl(getString(R.string.url_discord))
        }

        binding.btnTiktokLink.setOnClickListener {
            openExternalUrl(getString(R.string.url_tiktok))
        }

        binding.btnYoutubeLink.setOnClickListener {
            openExternalUrl(getString(R.string.url_youtube))
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

    private fun openExternalUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_SHORT).show()
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
