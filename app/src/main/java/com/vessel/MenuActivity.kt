package com.vessel

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.ArCoreApk
import com.vessel.databinding.ActivityMenuBinding
import kotlinx.coroutines.launch

class MenuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMenuBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var importer: VideoImporter
    private var videoUri: Uri? = null

    // Permission launcher
    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchAr()
        else Toast.makeText(this, "Camera permission required for AR", Toast.LENGTH_LONG).show()
    }

    // Video picker
    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        importVideo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs   = getPreferences(MODE_PRIVATE)
        importer = VideoImporter(this)

        GranularBridge.nativeInit()

        restorePrefs()
        wireListeners()
        updateEnterArState()
    }

    private fun restorePrefs() {
        binding.switchIdleSound.isChecked = prefs.getBoolean("idleSound", true)
        binding.switchBleedMode.isChecked = prefs.getBoolean("bleedMode", false)
        GranularBridge.nativeSetIdleMode(binding.switchIdleSound.isChecked)
    }

    private fun wireListeners() {
        binding.btnImportVideo.setOnClickListener { videoPicker.launch("video/*") }

        binding.switchIdleSound.setOnCheckedChangeListener { _, checked ->
            GranularBridge.nativeSetIdleMode(checked)
            prefs.edit().putBoolean("idleSound", checked).apply()
        }
        binding.switchBleedMode.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("bleedMode", checked).apply()
        }

        binding.btnEnterAr.setOnClickListener { requestCameraAndLaunch() }
    }

    private fun importVideo(uri: Uri) {
        binding.progressImport.visibility = View.VISIBLE
        binding.btnImportVideo.isEnabled = false

        lifecycleScope.launch {
            try {
                val result = importer.importVideo(uri)
                GranularBridge.nativeLoadPcm(result.pcmFloat, result.numFrames, result.numChannels)
                videoUri = uri
                binding.labelVideoStatus.text = "Video loaded (${result.numFrames / 48000}s)"
                updateEnterArState()
            } catch (e: VideoImporter.NoAudioTrackException) {
                Toast.makeText(this@MenuActivity, "Video has no audio track", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MenuActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progressImport.visibility = View.GONE
                binding.btnImportVideo.isEnabled = true
            }
        }
    }

    private fun updateEnterArState() {
        binding.btnEnterAr.isEnabled = videoUri != null
    }

    private fun requestCameraAndLaunch() {
        when {
            checkSelfPermission(Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED -> launchAr()
            else -> cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchAr() {
        when (ArCoreApk.getInstance().checkAvailability(this)) {
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                Toast.makeText(this, "ARCore not supported on this device", Toast.LENGTH_LONG).show()
            }
            else -> {
                val intent = Intent(this, ArActivity::class.java).apply {
                    putExtra("idle_sound", binding.switchIdleSound.isChecked)
                    putExtra("bleed_mode", binding.switchBleedMode.isChecked)
                }
                startActivity(intent)
            }
        }
    }
}
