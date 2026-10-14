package com.photoguide

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.photoguide.capture.CameraPermissionState
import com.photoguide.platform.AndroidAppContext
import com.photoguide.ui.App

class MainActivity : ComponentActivity() {

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            CameraPermissionState.granted.value = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 必须在 Compose 之前注入：createSettingsStore() 依赖它
        AndroidAppContext.init(this)

        enableEdgeToEdge()

        val alreadyGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

        CameraPermissionState.granted.value = alreadyGranted
        if (!alreadyGranted) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent { App() }
    }
}
