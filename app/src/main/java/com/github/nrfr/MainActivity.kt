package com.github.nrfr

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.nrfr.ui.screens.AboutScreen
import com.github.nrfr.ui.screens.MainScreen
import com.github.nrfr.ui.screens.ShizukuNotReadyScreen
import com.github.nrfr.ui.theme.NrfrTheme
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private var isShizukuReady by mutableStateOf(false)
    private var showAbout by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始化 Hidden API 访问
        HiddenApiBypass.addHiddenApiExemptions("L")
        HiddenApiBypass.addHiddenApiExemptions("I")

        // 检查 Shizuku 状态
        checkShizukuStatus()

        // 添加 Shizuku 权限监听器
        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            isShizukuReady = grantResult == PackageManager.PERMISSION_GRANTED
            if (!isShizukuReady) {
                Toast.makeText(this, getString(R.string.toast_shizuku_permission_required), Toast.LENGTH_LONG).show()
            }
        }

        // 添加 Shizuku 绑定监听器
        Shizuku.addBinderReceivedListener {
            checkShizukuStatus()
        }

        setContent {
            NrfrTheme {
                if (showAbout) {
                    AboutScreen(onBack = { showAbout = false })
                } else if (isShizukuReady) {
                    MainScreen(onShowAbout = { showAbout = true })
                } else {
                    ShizukuNotReadyScreen(
                        onCheckStatus = { checkShizukuStatus() },
                        onRequestPermission = {
                            if (Shizuku.getBinder() != null && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                                Shizuku.requestPermission(0)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun checkShizukuStatus() {
        isShizukuReady = if (Shizuku.getBinder() == null) {
            Toast.makeText(this, getString(R.string.toast_shizuku_not_installed), Toast.LENGTH_LONG).show()
            false
        } else {
            val hasPermission = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                Shizuku.requestPermission(0)
            }
            hasPermission
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener { _, _ -> }
        Shizuku.removeBinderReceivedListener { }
    }
}
