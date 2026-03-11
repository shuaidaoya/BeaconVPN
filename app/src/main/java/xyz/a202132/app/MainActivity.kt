package xyz.a202132.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import xyz.a202132.app.ui.components.StartupSplashOverlay
import xyz.a202132.app.ui.screens.MainScreen
import xyz.a202132.app.ui.screens.PerAppProxyScreen
import xyz.a202132.app.ui.theme.FireflyVPNTheme
import xyz.a202132.app.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    
    private var pendingVpnAction: (() -> Unit)? = null
    
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // VPN权限已授予，执行之前的操作
            pendingVpnAction?.invoke()
        } else {
            Toast.makeText(this, "VPN权限被拒绝", Toast.LENGTH_SHORT).show()
        }
        pendingVpnAction = null
    }
    
    // 通知权限请求 (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // 权限已授予
        }
        // 不管是否授予，都继续运行应用
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()
        
        // 请求通知权限 (Android 13+)
        requestNotificationPermission()
        
        setContent {
            FireflyVPNTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 导航状态
                    var showPerAppProxyScreen by remember { mutableStateOf(false) }
                    
                    // 获取 ViewModel (在顶层获取，以便在两个屏幕间共享逻辑)
                    val viewModel: MainViewModel = viewModel()
                    val startupUpdateCheckCompleted by viewModel.startupUpdateCheckCompleted.collectAsState()
                    val splashDurationSeconds = AppConfig.STARTUP_SPLASH_DURATION_SECONDS.coerceAtLeast(0)
                    var splashCountdownSeconds by remember(splashDurationSeconds) {
                        mutableIntStateOf(splashDurationSeconds)
                    }
                    var splashTimedOut by remember(splashDurationSeconds) {
                        mutableStateOf(splashDurationSeconds == 0)
                    }
                    var splashSkipped by remember { mutableStateOf(false) }
                    val showStartupSplash =
                        splashDurationSeconds > 0 &&
                            !splashSkipped &&
                            !splashTimedOut &&
                            !startupUpdateCheckCompleted

                    LaunchedEffect(splashDurationSeconds) {
                        if (splashDurationSeconds == 0) return@LaunchedEffect
                        splashCountdownSeconds = splashDurationSeconds
                        splashTimedOut = false
                        repeat(splashDurationSeconds) {
                            delay(1000)
                            splashCountdownSeconds = (splashCountdownSeconds - 1).coerceAtLeast(0)
                        }
                        splashTimedOut = true
                    }
                    
                    if (showStartupSplash) {
                        StartupSplashOverlay(
                            countdownSeconds = splashCountdownSeconds,
                            onSkip = { splashSkipped = true }
                        )
                    } else if (showPerAppProxyScreen) {
                        // 分应用代理设置界面
                        PerAppProxyScreen(
                            onBack = { hasChanges -> 
                                showPerAppProxyScreen = false
                                if (hasChanges) {
                                    viewModel.restartVpnIfNeeded()
                                }
                            }
                        )
                    } else {
                        // 主界面
                        MainScreen(
                            viewModel = viewModel,
                            onStartVpn = { action ->
                                requestVpnPermission(action)
                            },
                            onOpenPerAppProxy = { showPerAppProxyScreen = true }
                        )
                    }
                }
            }
        }
    }
    
    private fun requestVpnPermission(action: () -> Unit) {
        val intent = android.net.VpnService.prepare(this)
        if (intent != null) {
            pendingVpnAction = action
            vpnPermissionLauncher.launch(intent)
        } else {
            // 已有权限，直接执行
            action()
        }
    }
    
    private fun requestNotificationPermission() {
        // Android 13 (API 33) 及以上需要请求通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    
    private fun configureEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

