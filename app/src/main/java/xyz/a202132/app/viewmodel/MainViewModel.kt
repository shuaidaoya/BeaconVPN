package xyz.a202132.app.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import xyz.a202132.app.AppConfig
import xyz.a202132.app.BuildConfig
import xyz.a202132.app.UnlockPrioritySite
import xyz.a202132.app.data.local.AppDatabase
import xyz.a202132.app.data.model.*
import xyz.a202132.app.data.repository.SettingsRepository
import xyz.a202132.app.network.LatencyTester
import xyz.a202132.app.network.NetworkClient
import xyz.a202132.app.network.SubscriptionParser
import xyz.a202132.app.network.DownloadManager
import xyz.a202132.app.network.UnlockTestManager
import xyz.a202132.app.service.BoxVpnService
import xyz.a202132.app.service.ServiceManager
import xyz.a202132.app.util.NetworkUtils
import xyz.a202132.app.util.UnlockTestsRunner
import xyz.a202132.app.util.CryptoUtils
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.UUID

enum class StartupDefaultTestMode {
    NONE,
    TCPING,
    URL_TEST
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val tag = "MainViewModel"
    private val database = AppDatabase.getInstance(application)
    private val nodeDao = database.nodeDao()
    private val settingsRepository = SettingsRepository(application)
    private val subscriptionParser = SubscriptionParser()
    private val latencyTester = LatencyTester()
    private val gson = Gson()
    
    // 节流控制
    private val THROTTLE_INTERVAL = 5000L // 5秒节流间隔
    private var lastFetchNodesTime = 0L
    private var lastBackupSwitchTime = 0L
    private var lastCheckUpdateTime = 0L
    // 缓存最新拉取的节点，供launchStartupDefaultTestIfNeeded使用（避免 Room Flow 延迟导致测旧节点）
    private var lastFetchedNodes: List<Node>? = null
    
    // UI状态
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    
    private val _isTesting = MutableStateFlow(false)
    val isTesting = _isTesting.asStateFlow()
    
    private val _showNodeList = MutableStateFlow(false)
    val showNodeList = _showNodeList.asStateFlow()
    
    // 测试类型标签 (用于 UI 显示)
    private val _testingLabel = MutableStateFlow<String?>(null)
    val testingLabel = _testingLabel.asStateFlow()
    
    // 过滤不可用节点
    private val _filterUnavailable = MutableStateFlow(false)
    val filterUnavailable = _filterUnavailable.asStateFlow()
    
    private val _notice = MutableStateFlow<NoticeInfo?>(null)
    val notice = _notice.asStateFlow()
    
    // 持久通知配置（与对话框可见性无关）
    private val _noticeConfig = MutableStateFlow<NoticeInfo?>(null)
    val noticeConfig = _noticeConfig.asStateFlow()
    
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo = _updateInfo.asStateFlow()
    private val _startupUpdateCheckCompleted =
        MutableStateFlow(AppConfig.STARTUP_SPLASH_DURATION_SECONDS <= 0)
    val startupUpdateCheckCompleted = _startupUpdateCheckCompleted.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)

    val error = _error.asStateFlow()
    private val _infoDialogMessage = MutableStateFlow<String?>(null)
    val infoDialogMessage = _infoDialogMessage.asStateFlow()
    private val _showStartupDefaultTestChoiceDialog = MutableStateFlow(false)
    val showStartupDefaultTestChoiceDialog = _showStartupDefaultTestChoiceDialog.asStateFlow()
    
    // 阻止自动选择状态
    private val _isAutoSelecting = MutableStateFlow(false)
    val isAutoSelecting = _isAutoSelecting.asStateFlow()

    // 自动化测试状态
    private val _autoTestProgress = MutableStateFlow(AutoTestProgress())
    val autoTestProgress = _autoTestProgress.asStateFlow()
    private val _autoTestResultSnapshot = MutableStateFlow<List<Node>>(emptyList())
    val autoTestResultSnapshot = _autoTestResultSnapshot.asStateFlow()
    private var autoTestJob: Job? = null
    
    // Data
    val nodes = combine(
        nodeDao.getAllNodes(),
        _filterUnavailable
    ) { list, filterOut ->
        val filtered = if (filterOut) list.filterNot { shouldHideByQuickCleanup(it) } else list
        filtered.sortedWith(
            compareByDescending<Node> { it.isAvailable }
                .thenBy { it.sortOrder }
                .thenBy { if (it.latency >= 0) it.latency else Int.MAX_VALUE }
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        emptyList()
    )
    
    // 初始化完成标志 - 用于防止 UI 闪烁
    private val _isInitialized = MutableStateFlow(false)
    
    val selectedNodeId = combine(
        settingsRepository.selectedNodeId,
        _isInitialized
    ) { nodeId, initialized ->
        // 只有在初始化完成后才发出真实值，否则返回 null
        if (initialized) nodeId else null
    }.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        null
    )
    
    val proxyMode = settingsRepository.proxyMode.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        ProxyMode.SMART
    )
    
    val bypassLan = settingsRepository.bypassLan.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        true // 默认开启绕过局域网
    )
    
    val ipv6RoutingMode = settingsRepository.ipv6RoutingMode.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        IPv6RoutingMode.DISABLED
    )
    
    val isUserAgreementAccepted = settingsRepository.isUserAgreementAccepted.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        true // 默认 true 防止闪烁，init 里会 reset 状态或者第一次启动时读出来是 false
    )

    // 自动化测试设置
    val autoTestEnabled = settingsRepository.autoTestEnabled.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val autoTestFilterUnavailable = settingsRepository.autoTestFilterUnavailable.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val autoTestLatencyEnabled = settingsRepository.autoTestLatencyEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val autoTestLatencyMode = settingsRepository.autoTestLatencyMode.stateIn(viewModelScope, SharingStarted.Lazily, AutoTestLatencyMode.URL_TEST)
    val autoTestLatencyThresholdMs = settingsRepository.autoTestLatencyThresholdMs.stateIn(viewModelScope, SharingStarted.Lazily, 600)
    val autoTestBandwidthEnabled = settingsRepository.autoTestBandwidthEnabled.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val autoTestBandwidthDownloadEnabled = settingsRepository.autoTestBandwidthDownloadEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val autoTestBandwidthUploadEnabled = settingsRepository.autoTestBandwidthUploadEnabled.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val autoTestBandwidthDownloadThresholdMbps = settingsRepository.autoTestBandwidthDownloadThresholdMbps.stateIn(viewModelScope, SharingStarted.Lazily, 10)
    val autoTestBandwidthUploadThresholdMbps = settingsRepository.autoTestBandwidthUploadThresholdMbps.stateIn(viewModelScope, SharingStarted.Lazily, 10)
    val autoTestBandwidthWifiOnly = settingsRepository.autoTestBandwidthWifiOnly.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val autoTestBandwidthDownloadSizeMb = settingsRepository.autoTestBandwidthDownloadSizeMb.stateIn(viewModelScope, SharingStarted.Lazily, 10)
    val autoTestBandwidthUploadSizeMb = settingsRepository.autoTestBandwidthUploadSizeMb.stateIn(viewModelScope, SharingStarted.Lazily, 10)
    val autoTestUnlockEnabled = settingsRepository.autoTestUnlockEnabled.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val autoTestByRegion = settingsRepository.autoTestByRegion.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val autoTestNodeLimit = settingsRepository.autoTestNodeLimit.stateIn(viewModelScope, SharingStarted.Lazily, 20)
    val preferTestModes = settingsRepository.preferTestModes.stateIn(viewModelScope, SharingStarted.Lazily, builtInPreferTestModes())
    val preferTestSelectedModeId = settingsRepository.preferTestSelectedModeId.stateIn(viewModelScope, SharingStarted.Lazily, BUILTIN_PREFER_MODE_CHAT)
    val startupDefaultTestMode = settingsRepository.startupDefaultTestMode.stateIn(viewModelScope, SharingStarted.Lazily, StartupDefaultTestMode.NONE)
    val nodeIpInfoTestOnVpnStart = settingsRepository.nodeIpInfoTestOnVpnStart.stateIn(viewModelScope, SharingStarted.Lazily, false)
    
    val vpnState = ServiceManager.vpnState
    
    // 流量统计
    val uploadSpeed = ServiceManager.uploadSpeed
    val downloadSpeed = ServiceManager.downloadSpeed
    val uploadTotal = ServiceManager.uploadTotal
    val downloadTotal = ServiceManager.downloadTotal
    
    val currentNode = combine(nodeDao.getAllNodes(), selectedNodeId) { nodeList, selectedId ->
        nodeList.find { it.id == selectedId }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)
    
    init {
        // 将历史明文 rawLink 迁移为加密存储（一次性后台处理）
        viewModelScope.launch(Dispatchers.IO) {
            migrateRawLinksToEncrypted()
            logRawLinkEncryptionStats("startup_migration")
        }

        // 启动时加载数据
        viewModelScope.launch {
            // 每次启动重置选择状态 (不记住上次选择)
            settingsRepository.setSelectedNodeId(null)
            
            // 标记初始化完成，此时 selectedNodeId 才开始发出真实值
            // 这样 UI 不会看到旧的选中状态闪烁
            _isInitialized.value = true
            
            // 监听用户协议状态，只有同意后才初始化网络请求
            isUserAgreementAccepted.collect { accepted ->
                if (accepted) {
                    val autoEnabled = settingsRepository.autoTestEnabled.first()
                    if (autoEnabled) {
                        val selectedModeId = settingsRepository.preferTestSelectedModeId.first()
                        val modeName = settingsRepository.preferTestModes.first()
                            .firstOrNull { it.id == selectedModeId }
                            ?.name
                            ?: "当前模式"
                        _error.value = "APP启动后已自动执行${modeName}测试"
                        startAutomatedTest()
                    } else {
                        val fetchOk = fetchNodesInternal(
                            bypassThrottle = true,
                            runUrlTest = false,
                            fetchingLabel = "请求节点中"
                        )
                        if (!fetchOk) return@collect
                        val choiceDone = settingsRepository.startupDefaultTestChoiceDone.first()
                        if (!choiceDone) {
                            _showStartupDefaultTestChoiceDialog.value = true
                        } else {
                            launchStartupDefaultTestIfNeeded(showHint = false)
                        }
                    }
                    checkNotice()
                    checkUpdate(isAuto = true, trackStartupSplash = true)
                }
            }
        }
        
        // 监听 ServiceManager 的错误消息
        viewModelScope.launch {
            ServiceManager.errorMessage.collect { message ->
                if (message != null) {
                    _error.value = message
                    ServiceManager.clearError()
                }
            }
        }
        

        // 监听自动选择 (当已连接 && 自动模式 && 有延迟数据时触发)
        viewModelScope.launch {
            // 组合观察: 节点列表, VPN状态, 当前选中ID(null=auto)
            combine(nodes, vpnState, selectedNodeId) { currentNodes, state, selectedId ->
                Triple(currentNodes, state, selectedId)
            }.collect { (currentNodes, state, selectedId) ->
                if (state == VpnState.CONNECTED && selectedId == null) {
                    // 只有在自动选择模式(selectedId==null)且已连接时才执行
                    // 寻找有延迟数据的最佳节点
                    val validNodes = currentNodes.filter { it.latency > 0 }
                    if (validNodes.isNotEmpty()) {
                        // 找到延迟最低的节点
                        val bestNode = validNodes.minByOrNull { it.latency }
                        if (bestNode != null) {
                            Log.i(tag, "Auto-selecting best node: ${bestNode.name} (${bestNode.latency}ms)")
                            
                            // 1. 切换代理
                            BoxVpnService.selectNode(bestNode.id)
                            
                            // 2. 更新选中状态 (这会停止后续的自动选择，因为 selectedId 不再是 null)
                            // 稍微延迟一下确保切换成功
                            delay(500)
                            settingsRepository.setSelectedNodeId(bestNode.id)
                            
                            // 3. 通知用户
                            _error.value = "已自动选择: ${bestNode.name}"
                        }
                    }
                }
            }
        }
    }

    
    /**
     * 获取节点列表
     */
    val backupNodeEnabled = settingsRepository.backupNodeEnabled.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        false
    )
    
    /**
     * 获取节点列表
     */
    // 备用节点 URL (本地存储)
    val backupNodeUrl = settingsRepository.backupNodeUrl.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        null
    )
    
    /**
     * 获取节点列表
     */
    /**
     * @param skipBackupMode 强制跳过备用模式逻辑 (用于回退场景，避免 DataStore 异步更新导致重复触发)
     */
    fun fetchNodes(
        skipBackupMode: Boolean = false,
        bypassThrottle: Boolean = false,
        runUrlTest: Boolean = true,
        allowWhenTestRunning: Boolean = false,
        allowWhenFetchingInProgress: Boolean = false,
        fetchingLabel: String = "请求节点中"
    ) {
        viewModelScope.launch {
            fetchNodesInternal(
                skipBackupMode = skipBackupMode,
                bypassThrottle = bypassThrottle,
                runUrlTest = runUrlTest,
                allowWhenTestRunning = allowWhenTestRunning,
                allowWhenFetchingInProgress = allowWhenFetchingInProgress,
                fetchingLabel = fetchingLabel
            )
        }
    }

    private suspend fun fetchNodesInternal(
        skipBackupMode: Boolean = false,
        bypassThrottle: Boolean = false,
        runUrlTest: Boolean = true,
        allowWhenTestRunning: Boolean = false,
        allowWhenFetchingInProgress: Boolean = false,
        fetchingLabel: String = "请求节点中"
    ): Boolean {
        if (!allowWhenTestRunning && GlobalTestExecution.mutex.isLocked) {
            _error.value = GlobalTestExecution.busyHint()
            return false
        }
        if (!allowWhenFetchingInProgress && GlobalTestExecution.isFetching()) {
            _error.value = GlobalTestExecution.fetchingHint()
            return false
        }

        // 节流检查 (内部调用可跳过)
        if (!bypassThrottle) {
            val now = System.currentTimeMillis()
            if (now - lastFetchNodesTime < THROTTLE_INTERVAL) {
                _error.value = "操作过于频繁，请稍后再试"
                return false
            }
            lastFetchNodesTime = now
        }

        GlobalTestExecution.beginFetching(fetchingLabel)
        _isLoading.value = true
        _filterUnavailable.value = false // 刷新时重置过滤
        try {
            // 1. 检查网络状态
            if (!NetworkUtils.isNetworkAvailable(getApplication())) {
                _error.value = "当前无网络连接，无法获取节点"
                return false
            }

            // 2. 检查是否开启备用节点 (skipBackupMode 时强制为 false)
            val isBackupEnabled = if (skipBackupMode) false else settingsRepository.backupNodeEnabled.first()
            var targetUrl: String? = null

            if (isBackupEnabled) {
                // 每次刷新都获取最新 Notice 配置，确保能检测到远程配置变化
                Log.d(tag, "Backup mode enabled, fetching latest notice config...")
                val notice = fetchNoticeSync()

                if (notice == null) {
                    // Notice 请求失败 (服务器错误等)，触发回退
                    Log.e(tag, "Failed to fetch notice for backup node")
                    _error.value = "备用节点当前不可用，已退回默认节点！"
                    handleBackupFallback()
                    return false
                }

                // 验证备用节点配置有效性
                val backupUrl = notice.backupNodes?.url
                val isValidUrl = backupUrl != null &&
                    backupUrl.isNotBlank() &&
                    (backupUrl.startsWith("http://") || backupUrl.startsWith("https://"))

                if (isValidUrl) {
                    targetUrl = backupUrl
                    Log.d(tag, "Using backup node URL: $targetUrl")
                } else {
                    // 配置无效 (无 backupNodes / 无 url / url 为空 / url 格式错误)
                    // 触发完整回退
                    Log.w(tag, "Backup node config invalid: backupNodes=$${notice.backupNodes}, url=$backupUrl")
                    _error.value = "备用节点当前不可用，已退回默认节点！"
                    handleBackupFallback()
                    return false
                }
            }

            // 请求节点（失败自动重试一次）
            var result = if (targetUrl != null) {
                subscriptionParser.fetchAndParse(targetUrl)
            } else {
                subscriptionParser.fetchAndParse()
            }
            if (result.isFailure) {
                Log.w(tag, "First fetch attempt failed, retrying...", result.exceptionOrNull())
                _error.value = "请求节点失败，已自动重试..."
                result = if (targetUrl != null) {
                    subscriptionParser.fetchAndParse(targetUrl)
                } else {
                    subscriptionParser.fetchAndParse()
                }
            }

            if (result.isSuccess) {
                val fetchedNodes = result.getOrThrow()
                // 检查由备用节点返回的空列表
                if (isBackupEnabled && fetchedNodes.isEmpty()) {
                    Log.w(tag, "Backup node returned empty list, treating as failure")
                    _error.value = "备用节点当前不可用，已退回默认节点！"
                    handleBackupFallback()
                    return false
                }

                // 保存到数据库
                nodeDao.replaceAllNodes(fetchedNodes)
                // 缓存最新节点，供后续自动测试使用（避免 Room Flow 延迟导致测旧节点）
                lastFetchedNodes = fetchedNodes

                if (BuildConfig.DEBUG) {
                    viewModelScope.launch(Dispatchers.IO) {
                        logRawLinkEncryptionStats("after_fetch_insert")
                    }
                }

                // 自动测试延迟 (直接传入节点列表，避免等待 Flow 更新)
                // 用户更关心连通性与真实延迟，改用 URL Test
                if (runUrlTest) {
                    urlTestAllNodes(fetchedNodes)
                }

                Log.d(tag, "Fetched ${fetchedNodes.size} nodes")
                return true
            } else {
                val e = result.exceptionOrNull()
                Log.e(tag, "Failed to fetch nodes", e)
                if (isBackupEnabled) {
                    _error.value = "备用节点当前不可用，已退回默认节点！"
                    // 执行回退操作 (关闭，清除URL，重试默认)
                    handleBackupFallback()
                } else {
                    _error.value = "获取节点失败: ${e?.message ?: "未知错误"}"
                }
                return false
            }
        } catch (e: Exception) {
            Log.e(tag, "Error fetching nodes", e)
            _error.value = "网络错误"
            return false
        } finally {
            _isLoading.value = false
            GlobalTestExecution.endFetching()
        }
    }
    
    // 处理备用节点回退逻辑
    private suspend fun handleBackupFallback() {
        // 0. 如果 VPN 正在运行，先停止（因为节点来源已失效）
        if (vpnState.value != VpnState.DISCONNECTED) {
            Log.d(tag, "Stopping VPN before backup fallback")
            ServiceManager.stopVpn(getApplication())
            delay(500) // 等待 VPN 停止
        }
        
        // 1. 关闭备用节点开关
        settingsRepository.setBackupNodeEnabled(false)
        // 2. 清除备用节点 URL
        settingsRepository.setBackupNodeUrl(null)
        // 3. 清除当前选中的节点（备用节点 ID 在默认列表中不存在）
        settingsRepository.setSelectedNodeId(null)
        
        // 4. 更新 Notice Config 以隐藏按钮
        val currentConfig = _noticeConfig.value
        if (currentConfig != null) {
            _noticeConfig.value = currentConfig.copy(backupNodes = null)
        }
        
        // 5. 重新请求，强制跳过备用模式 (避免 DataStore 异步更新导致重复触发)
        val fetchOk = fetchNodesInternal(
            skipBackupMode = true,
            bypassThrottle = true,
            runUrlTest = false,
            allowWhenFetchingInProgress = true,
            fetchingLabel = "请求节点中"
        )
        if (!fetchOk) return
        launchStartupDefaultTestIfNeeded(showHint = true)
    }
    
    // 辅助: 同步获取 Notice
    private suspend fun fetchNoticeSync(): NoticeInfo? {
        return try {
            val info = withTimeout(AppConfig.NOTICE_REQUEST_TIMEOUT_MS) {
                NetworkClient.apiService.getNoticeInfo(AppConfig.NOTICE_URL)
            }
            updateNoticeConfig(info)
            info
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun updateNoticeConfig(info: NoticeInfo) {
        _noticeConfig.value = info
        
        val backupInfo = info.backupNodes
        val isValid = backupInfo?.url?.let { 
             it.startsWith("http://") || it.startsWith("https://") 
        } == true
        
        if (isValid) {
            settingsRepository.setBackupNodeUrl(backupInfo!!.url)
        } else {
            // 无效配置，清除本地记录
            settingsRepository.setBackupNodeUrl(null)
            // 注意: 不在这里触发 handleBackupFallback，让 fetchNodes 的调用者统一处理
            // 避免重复触发 Toast
        }
    }
    
    fun setBackupNodeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            if (_autoTestProgress.value.running || _isTesting.value || GlobalTestExecution.mutex.isLocked) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }

            // 节流检查
            val now = System.currentTimeMillis()
            if (now - lastBackupSwitchTime < THROTTLE_INTERVAL) {
                _error.value = "切换过于频繁，请稍后再试"
                return@launch
            }
            lastBackupSwitchTime = now
            
            // 如果 VPN 正在运行，先停止（因为节点来源将改变）
            if (vpnState.value != VpnState.DISCONNECTED) {
                Log.d(tag, "Stopping VPN before switching node source")
                ServiceManager.stopVpn(getApplication())
                // 等待 VPN 停止
                delay(500)
            }
            
            // 清除当前选中的节点（因为它可能不在新列表中）
            settingsRepository.setSelectedNodeId(null)
            
            // 切换设置
            settingsRepository.setBackupNodeEnabled(enabled)
            delay(100)
            
            // 获取新的节点列表 (跳过节流检查，因为这是内部调用；测试行为跟随 APP 默认设置)
            val fetchOk = fetchNodesInternal(
                bypassThrottle = true,
                runUrlTest = false,
                fetchingLabel = "请求节点中"
            )
            if (!fetchOk) return@launch
            launchStartupDefaultTestIfNeeded(showHint = true)
        }
    }
    
    fun testAllNodes(targetNodes: List<Node>? = null) {
        viewModelScope.launch {
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            if (!GlobalTestExecution.tryStart("TCPing 测试")) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }
            _isTesting.value = true
            _testingLabel.value = "TCPing 测试中..."
            try {
                val currentNodes = targetNodes ?: nodes.value
                internalTestNodes(currentNodes) { completed, total ->
                    _testingLabel.value = "TCPing 测试中 ($completed/$total)"
                }
            } finally {
                _isTesting.value = false
                _testingLabel.value = null
                GlobalTestExecution.finish()
            }
        }
    }
    
    /**
     * URL Test 所有节点延迟 (通过 ClashAPI)
     * VPN 运行中 → 用现有 ClashAPI (port 9090)
     * VPN 未运行 → 启动临时无头 sing-box 实例 (port 19090)
     */
    fun urlTestAllNodes(targetNodes: List<Node>? = null) {
        urlTestAllNodesInternal(
            targetNodes = targetNodes,
            requireGlobalLock = true
        )
    }

    private fun urlTestAllNodesInternal(
        targetNodes: List<Node>? = null,
        requireGlobalLock: Boolean,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ) {
        viewModelScope.launch {
            var locked = false
            if (requireGlobalLock) {
                if (GlobalTestExecution.isFetching()) {
                    _error.value = GlobalTestExecution.fetchingHint()
                    return@launch
                }
                if (!GlobalTestExecution.tryStart("URL Test 测试")) {
                    _error.value = GlobalTestExecution.busyHint()
                    return@launch
                }
                locked = true
            }
            _isTesting.value = true
            _testingLabel.value = "URL Test 测试中..."
            
            val isVpnRunning = vpnState.value == VpnState.CONNECTED
            val clashApiPort: Int
            var startedHeadless = false
            
            try {
                val currentNodes = targetNodes ?: nodes.value
                if (currentNodes.isEmpty()) {
                    _error.value = "没有可用节点"
                    return@launch
                }
                
                if (isVpnRunning) {
                    // VPN 已连接，直接使用现有 ClashAPI
                    clashApiPort = 9090
                    Log.d(tag, "URL Test via existing VPN ClashAPI (port $clashApiPort)")
                } else {
                    // VPN 未连接，启动临时无头 sing-box 实例
                    clashApiPort = xyz.a202132.app.network.UrlTestManager.CLASH_API_PORT
                    Log.d(tag, "URL Test via headless instance (port $clashApiPort)")
                    _testingLabel.value = "启动测试引擎..."
                    
                    val started = withContext(Dispatchers.IO) {
                        xyz.a202132.app.network.UrlTestManager.start(getApplication(), currentNodes)
                    }
                    if (!started) {
                        _error.value = "启动测试引擎失败"
                        return@launch
                    }
                    startedHeadless = true
                    
                    // 等待 sing-box 初始化 + ClashAPI 就绪
                    delay(2000)
                    
                    // 健康检查：等待 ClashAPI 可用 (必须在 IO 线程)
                    val clashReady = withContext(Dispatchers.IO) {
                        var ready = false
                        for (retry in 1..6) {
                            try {
                                val checkUrl = java.net.URL("http://127.0.0.1:$clashApiPort/proxies")
                                val conn = checkUrl.openConnection() as java.net.HttpURLConnection
                                conn.connectTimeout = 2000
                                conn.readTimeout = 2000
                                if (conn.responseCode == 200) {
                                    ready = true
                                    conn.disconnect()
                                    break
                                }
                                conn.disconnect()
                            } catch (e: Exception) {
                                Log.d(tag, "ClashAPI not ready yet (attempt $retry/6): ${e.message}")
                            }
                            delay(1000)
                        }
                        ready
                    }
                    
                    if (!clashReady) {
                        _error.value = "测试引擎启动超时"
                        return@launch
                    }
                    
                    _testingLabel.value = "URL Test 测试中..."
                }
                
                // 诊断：查询 ClashAPI 注册的代理
                if (startedHeadless) {
                    withContext(Dispatchers.IO) {
                        val proxiesResponse = xyz.a202132.app.network.UrlTestManager.diagnoseProxies()
                        Log.d(tag, "ClashAPI proxies: ${proxiesResponse?.take(500)}")
                    }
                }
                
                Log.d(tag, "URL Testing ${currentNodes.size} nodes via ClashAPI (port=$clashApiPort)")
                
                val results = latencyTester.urlTestAllNodes(currentNodes, clashApiPort) { completed, total ->
                    _testingLabel.value = "URL Test 测试中 ($completed/$total)"
                    onProgress?.invoke(completed, total)
                }
                Log.d(tag, "Got ${results.size} URL test results")
                
                // 更新数据库
                results.forEach { result ->
                    val updatedRows = nodeDao.updateLatency(
                        nodeId = result.nodeId,
                        latency = result.latency,
                        isAvailable = result.isAvailable,
                        testedAt = System.currentTimeMillis()
                    )
                    if (updatedRows == 0) {
                        Log.w(tag, "URL Test result not applied, node not found in DB: ${result.nodeId}")
                    }
                }
            } finally {
                // 读取 sing-box 核心日志
                if (startedHeadless) {
                    withContext(Dispatchers.IO) {
                        val coreLog = xyz.a202132.app.network.UrlTestManager.readLogFile(getApplication())
                        if (coreLog != null) {
                            // 分段输出日志（logcat 单条消息有长度限制）
                            coreLog.lines().forEach { line ->
                                Log.d("SingBoxCoreLog", line)
                            }
                        } else {
                            Log.w(tag, "No sing-box core log available")
                        }
                    }
                }
                // 如果启动了临时实例，关闭它
                if (startedHeadless) {
                    xyz.a202132.app.network.UrlTestManager.stop()
                }
                _isTesting.value = false
                _testingLabel.value = null
                if (locked) {
                    GlobalTestExecution.finish()
                }
            }
        }
    }
    
    /**
     * 打开节点列表并开始指定类型的测试
     */
    fun showNodeListForTest(testType: String) {
        if (GlobalTestExecution.isFetching()) {
            _error.value = GlobalTestExecution.fetchingHint()
            return
        }
        if (_autoTestProgress.value.running || _isTesting.value || GlobalTestExecution.mutex.isLocked) {
            _error.value = GlobalTestExecution.busyHint()
            return
        }
        if (testType != "tcping" && testType != "urltest") {
            return
        }

        _showNodeList.value = true
        when (testType) {
            "tcping" -> testAllNodes()
            "urltest" -> urlTestAllNodes()
        }
    }

    fun setStartupDefaultTestMode(mode: StartupDefaultTestMode) {
        viewModelScope.launch {
            settingsRepository.setStartupDefaultTestMode(mode)
            settingsRepository.setStartupDefaultTestChoiceDone(true)
        }
    }

    fun clearStartupDefaultTestMode() {
        setStartupDefaultTestMode(StartupDefaultTestMode.NONE)
    }

    fun setNodeIpInfoTestOnVpnStart(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNodeIpInfoTestOnVpnStart(enabled)
        }
    }

    fun confirmStartupDefaultTestChoice(mode: StartupDefaultTestMode?) {
        viewModelScope.launch {
            if (mode == null) {
                settingsRepository.setStartupDefaultTestMode(StartupDefaultTestMode.NONE)
            } else {
                settingsRepository.setStartupDefaultTestMode(mode)
            }
            settingsRepository.setStartupDefaultTestChoiceDone(true)
            _showStartupDefaultTestChoiceDialog.value = false
            if (mode != null && mode != StartupDefaultTestMode.NONE) {
                launchStartupDefaultTestIfNeeded(showHint = false)
            }
        }
    }

    fun dismissStartupDefaultTestChoiceDialog() {
        _showStartupDefaultTestChoiceDialog.value = false
    }
    
    /**
     * 清理不可用节点 (UI 过滤，不删除数据库)
     */
    fun cleanUnavailableNodes() {
        hideUnqualifiedNodesInternal()
    }

    private fun shouldHideByQuickCleanup(node: Node): Boolean {
        return node.autoTestStatus.contains("FILTERED", ignoreCase = true) ||
            node.autoTestStatus.contains("FAILED", ignoreCase = true) ||
            node.latency == -2 ||
            !node.isAvailable
    }

    /**
     * 内部测试逻辑 (Suspend)
     */
    private suspend fun internalTestNodes(
        currentNodes: List<Node>,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ) {
        if (currentNodes.isEmpty()) return
        
        Log.d(tag, "Testing ${currentNodes.size} nodes")
        
        // 统一使用 Socket 测试 (直接连接节点服务器测试可达性)
        // 即使 VPN 运行中也可以工作，因为测试的是节点服务器本身
        val results = latencyTester.testAllNodes(currentNodes, onProgress)
        Log.d(tag, "Got ${results.size} test results")
        
        // 更新数据库
        results.forEach { result ->
            nodeDao.updateLatency(
                nodeId = result.nodeId,
                latency = result.latency,
                isAvailable = result.isAvailable,
                testedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * 开始自动选择并连接
     * (Blocking UI Flow)
     */
    fun startAutoSelectAndConnect() {
        startPreferModeAutoSelectAndConnect(BUILTIN_PREFER_MODE_CHAT)
    }

    fun startPreferModeAutoSelectAndConnect(modeId: String) {
        viewModelScope.launch {
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            if (_autoTestProgress.value.running || _isTesting.value || GlobalTestExecution.mutex.isLocked) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }
            val mode = preferTestModes.value.firstOrNull { it.id == modeId }
            if (mode == null) {
                _error.value = "未找到测试模式"
                return@launch
            }
            settingsRepository.setPreferTestSelectedModeId(modeId)
            _isAutoSelecting.value = true
            startAutomatedTest(
                preferPriority = mode.defaultPriority,
                connectBestAfterDone = true,
                configOverride = mode.toAutoTestConfig(autoRunEnabled = autoTestEnabled.value)
            )
        }
    }

    fun selectBestNodeByPriority(priority: BestNodePriority, connect: Boolean) {
        viewModelScope.launch {
            val currentMode = preferTestModes.value.firstOrNull { it.id == preferTestSelectedModeId.value }
            if (currentMode == null) {
                _error.value = "当前模式不存在，请重新选择模式"
                return@launch
            }
            if (!isPrioritySupportedByMode(priority, currentMode)) {
                _infoDialogMessage.value = "当前模式未启用${priorityDisplayName(priority)}相关测试。\n\n已保存该择优规则，请先调整模式配置或执行一次测试后再自动选择连接最优。"
                return@launch
            }

            val dbNodes = nodeDao.getAllNodes().first()
            val candidates = dbNodes
                .filter { it.isAvailable }
                .let { filterPriorityReadyCandidates(it, priority) }

            if (candidates.isEmpty()) {
                if (priority != BestNodePriority.LATENCY) {
                    val latencyFallback = dbNodes.filter { it.isAvailable && it.latency > 0 }
                    if (latencyFallback.isNotEmpty()) {
                        val bestLatency = pickBestNode(latencyFallback, BestNodePriority.LATENCY, currentMode)
                        if (bestLatency != null) {
                            settingsRepository.setSelectedNodeId(bestLatency.id)
                            if (connect) {
                                ServiceManager.startVpn(getApplication(), bestLatency, proxyMode.value)
                            }
                            _error.value = if (connect) {
                                "当前模式暂无${priorityDisplayName(priority)}数据，已按延迟优先连接：${bestLatency.getDisplayName()}"
                            } else {
                                "当前模式暂无${priorityDisplayName(priority)}数据，已按延迟优先选择：${bestLatency.getDisplayName()}"
                            }
                            return@launch
                        }
                    }
                }
                _infoDialogMessage.value = "已保存${priorityDisplayName(priority)}优先规则。\n\n当前模式还没有对应测试结果，请先点击“开始测试”，完成后再自动选择连接最优。"
                return@launch
            }

            val best = pickBestNode(candidates, priority, currentMode)
            if (best == null) {
                _error.value = "没有可用于${priorityDisplayName(priority)}择优的节点"
                return@launch
            }
            settingsRepository.setSelectedNodeId(best.id)
            if (connect) {
                ServiceManager.startVpn(getApplication(), best, proxyMode.value)
            }
            _error.value = if (connect) "已连接最优节点：${best.getDisplayName()}" else "已选择最优节点：${best.getDisplayName()}"
        }
    }

    fun selectBestNodeByPriorityFromSnapshot(priority: BestNodePriority, connect: Boolean) {
        viewModelScope.launch {
            val currentMode = preferTestModes.value.firstOrNull { it.id == preferTestSelectedModeId.value }
            if (currentMode == null) {
                _error.value = "当前模式不存在，请重新选择模式"
                return@launch
            }
            if (!isPrioritySupportedByMode(priority, currentMode)) {
                _infoDialogMessage.value = "当前模式未启用${priorityDisplayName(priority)}相关测试。\n\n请先调整模式配置或重新执行测试。"
                return@launch
            }

            val snapshotNodes = autoTestResultSnapshot.value
            if (snapshotNodes.isEmpty()) {
                _error.value = "当前没有可用的自动化测试结果快照，请先执行一次测试"
                return@launch
            }

            val candidates = snapshotNodes
                .filter { it.isAvailable }
                .let { filterPriorityReadyCandidates(it, priority) }

            if (candidates.isEmpty()) {
                _infoDialogMessage.value = "快照中暂无${priorityDisplayName(priority)}可用数据，请重新执行测试后再自动连接最优。"
                return@launch
            }

            val best = pickBestNode(candidates, priority, currentMode)
            if (best == null) {
                _error.value = "快照中没有可用于${priorityDisplayName(priority)}择优的节点"
                return@launch
            }
            settingsRepository.setSelectedNodeId(best.id)
            if (connect) {
                ServiceManager.startVpn(getApplication(), best, proxyMode.value)
            }
            _error.value = if (connect) "已连接最优节点：${best.getDisplayName()}" else "已选择最优节点：${best.getDisplayName()}"
        }
    }

    private fun isPrioritySupportedByMode(priority: BestNodePriority, mode: TestPreferMode): Boolean {
        return when (priority) {
            BestNodePriority.LATENCY -> mode.latencyEnabled
            BestNodePriority.UPLOAD -> mode.bandwidthEnabled && mode.bandwidthUploadEnabled
            BestNodePriority.DOWNLOAD -> mode.bandwidthEnabled && mode.bandwidthDownloadEnabled
            BestNodePriority.UNLOCK_COUNT -> mode.unlockEnabled
        }
    }

    private fun filterPriorityReadyCandidates(nodes: List<Node>, priority: BestNodePriority): List<Node> {
        return when (priority) {
            BestNodePriority.LATENCY -> nodes.filter { it.latency > 0 }
            BestNodePriority.UPLOAD -> nodes.filter { it.autoTestedAt > 0L && it.uploadMbps > 0f }
            BestNodePriority.DOWNLOAD -> nodes.filter { it.autoTestedAt > 0L && it.downloadMbps > 0f }
            BestNodePriority.UNLOCK_COUNT -> nodes.filter {
                it.autoTestedAt > 0L && (
                    it.unlockSummary.isNotBlank() || it.unlockPassed
                )
            }
        }
    }

    private fun priorityDisplayName(priority: BestNodePriority): String = when (priority) {
        BestNodePriority.LATENCY -> "延迟"
        BestNodePriority.UPLOAD -> "上行"
        BestNodePriority.DOWNLOAD -> "下行"
        BestNodePriority.UNLOCK_COUNT -> "解锁情况"
    }

    fun hideUnqualifiedAutoTestNodes() {
        hideUnqualifiedNodesInternal()
    }

    private fun hideUnqualifiedNodesInternal() {
        val hiddenCount = nodes.value.count { shouldHideByQuickCleanup(it) }
        if (hiddenCount > 0) {
            _filterUnavailable.value = true
            _error.value = "隐藏${hiddenCount}个超时/不可用/未达标节点"
        } else {
            _error.value = "没有可隐藏的不合格节点"
        }
    }
    
    /**
     * 自动选择最佳节点
     */
    fun autoSelectBestNode() {
        viewModelScope.launch {
            val bestNode = nodeDao.getBestNode()
            if (bestNode != null) {
                selectNode(bestNode)
            }
        }
    }
    
    /**
     * 选择节点
     */
    fun selectNode(node: Node) {
        viewModelScope.launch {
            settingsRepository.setSelectedNodeId(node.id)
            _showNodeList.value = false
            
            // If VPN is connected, restart to switch to new node
            if (vpnState.value == VpnState.CONNECTED) {
                // Notify user (Optional)
                Log.i(tag, "Restarting VPN to apply new Node: ${node.name}")
                ServiceManager.startVpn(getApplication(), node, proxyMode.value)
            }
        }
    }
    
    /**
     * 切换代理模式
     */
    fun setProxyMode(mode: ProxyMode) {
        viewModelScope.launch {
            settingsRepository.setProxyMode(mode)
            
            // If VPN is connected, restart to apply new mode config
            if (vpnState.value == VpnState.CONNECTED) {
                currentNode.value?.let { node ->
                    // Notify user (Optional, usually ConnectButton shows 'Connecting...')
                    Log.i(tag, "Restarting VPN to apply Proxy Mode: $mode")
                    ServiceManager.startVpn(getApplication(), node, mode)
                }
            }
        }
    }
    
    /**
     * 切换VPN连接
     */
    fun toggleVpn() {
        val node = currentNode.value ?: run {
            _error.value = "请先选择节点"
            return
        }
        
        when (vpnState.value) {
            VpnState.DISCONNECTED -> {
                // 4. 无网络连接节点时给用户发Toast，但不阻止
                if (!NetworkUtils.isNetworkAvailable(getApplication())) {
                     _error.value = "当前无网络连接节点！"
                }
                ServiceManager.startVpn(getApplication(), node, proxyMode.value)
            }
            VpnState.CONNECTED -> {
                ServiceManager.stopVpn(getApplication())
            }
            else -> {
                // 正在连接或断开中，忽略
            }
        }
    }
    
    /**
     * 重启 VPN 如果正在运行 (用于应用设置变更)
     */
    fun restartVpnIfNeeded() {
        if (vpnState.value == VpnState.CONNECTED) {
            currentNode.value?.let { node ->
                Log.i(tag, "Settings changed, restarting VPN to apply...")
                ServiceManager.startVpn(getApplication(), node, proxyMode.value)
            }
        }
    }
    
    /**
     * 设置绕过局域网
     */
    fun setBypassLan(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBypassLan(enabled)
            // 如果 VPN 正在运行，重启以应用新设置
            restartVpnIfNeeded()
        }
    }
    
    /**
     * 设置 IPv6 路由模式
     */
    fun setIPv6RoutingMode(mode: IPv6RoutingMode) {
        viewModelScope.launch {
            settingsRepository.setIPv6RoutingMode(mode)
            // 如果 VPN 正在运行，重启以应用新设置
            restartVpnIfNeeded()
        }
    }

    /**
     * 检查通知公告
     */
    private suspend fun checkNotice() {
        val result = requestWithAutoRetry("公告通知") {
            withTimeout(AppConfig.NOTICE_REQUEST_TIMEOUT_MS) {
                NetworkClient.apiService.getNoticeInfo(AppConfig.NOTICE_URL)
            }
        }
        result.onSuccess { noticeInfo ->
            // Always update config for features like Backup Node
            _noticeConfig.value = noticeInfo

            if (noticeInfo.hasNotice) {
                // 如果 showOnce 为 true，检查是否已显示过
                // 如果 showOnce 为 false，则每次都显示
                if (!noticeInfo.showOnce) {
                    _notice.value = noticeInfo
                    // 更新最后显示的 ID，以便后续如果服务器端改为 true，也能正确判断
                    settingsRepository.setLastNoticeId(noticeInfo.noticeId)
                } else {
                    val lastNoticeId = settingsRepository.lastNoticeId.first()
                    if (lastNoticeId != noticeInfo.noticeId) {
                        _notice.value = noticeInfo
                        settingsRepository.setLastNoticeId(noticeInfo.noticeId)
                    }
                }
            }
        }.onFailure { e ->
            Log.e(tag, "Failed to check notice", e)
        }
    }
    
    
    /**
     * 检查更新
     * @param isAuto 是否为自动检查 (不显示"已是最新"提示)
     */
    fun checkUpdate(isAuto: Boolean = false, trackStartupSplash: Boolean = false) {
        viewModelScope.launch {
            if (trackStartupSplash) {
                _startupUpdateCheckCompleted.value = false
            }
            // 节流检查 (自动检查可跳过)
            if (!isAuto) {
                val now = System.currentTimeMillis()
                if (now - lastCheckUpdateTime < THROTTLE_INTERVAL) {
                    _error.value = "操作过于频繁，请稍后再试"
                    return@launch
                }
                lastCheckUpdateTime = now
            }
            
            val result = requestWithAutoRetry("版本更新") {
                withTimeout(AppConfig.UPDATE_REQUEST_TIMEOUT_MS) {
                    NetworkClient.apiService.getUpdateInfo(AppConfig.UPDATE_URL)
                }
            }
            result.onSuccess { info ->
                val currentVersionCode = getApplication<Application>().packageManager
                    .getPackageInfo(getApplication<Application>().packageName, 0)
                    .let { 
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            it.longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            it.versionCode
                        }
                    }
                
                if (info.versionCode > currentVersionCode) {
                    _updateInfo.value = info
                } else {
                    if (!isAuto) {
                        _error.value = "已是最新版本"
                    }
                }
            }.onFailure { e ->
                Log.e(tag, "Failed to check update", e)
            }.also {
                if (trackStartupSplash) {
                    _startupUpdateCheckCompleted.value = true
                }
            }
        }
    }
    
    // 下载状态
    val downloadState = DownloadManager.downloadState
    
    /**
     * 打开下载链接 (现在改为应用内下载)
     */
    fun openDownloadUrl() {
        updateInfo.value?.let { info ->
            // 使用 DownloadManager 检查文件是否已下载且有效。
            val existingFile = DownloadManager.isApkReady(getApplication(), info.version)
            if (existingFile != null) {
                // 确定是否需要先显示权限对话框（通过 UI 中的状态进行处理）
                // 但由于我们想要触发安装，可以将状态设置为“已完成”
                // 但是，UI 会监听状态更改。
                // 如果状态已经是“已完成”，则不会触发任何更改。
                // 我们应该在此处强制执行 installApk 逻辑。
                installApk()
                return
            }
            
            viewModelScope.launch {
                DownloadManager.startDownload(info.downloadUrl, getApplication(), info.version)
            }
        }
    }
    
    fun pauseDownload() {
        DownloadManager.pauseDownload()
    }
    
    fun resumeDownload() {
        updateInfo.value?.let { info ->
            viewModelScope.launch {
                DownloadManager.startDownload(info.downloadUrl, getApplication(), info.version)
            }
        }
    }
    
    fun retryDownload() {
         updateInfo.value?.let { info ->
            viewModelScope.launch {
                DownloadManager.startDownload(info.downloadUrl, getApplication(), info.version)
            }
        }
    }
    
    fun cancelDownload() {
        DownloadManager.cancelDownload()
    }
    
    fun installApk() {
        val targetFile = downloadState.value.file ?: run {
             updateInfo.value?.let { info ->
                 DownloadManager.isApkReady(getApplication(), info.version)
             }
        }

        targetFile?.let { file ->
            val context = getApplication<Application>()
            try {
                if (file.exists()) { 
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    context.startActivity(intent)
                } else {
                     _error.value = "安装文件丢失，请重新下载"
                     // 重置状态以允许重新下载
                     DownloadManager.resetState()
                }
            } catch (e: Exception) {
                Log.e(tag, "Install failed", e)
                _error.value = "无法启动安装程序: ${e.message}"
            }
        } ?: run {
             _error.value = "找不到安装包，请重试下载"
             DownloadManager.resetState()
        }
    }
    
    // 用户界面操作
    fun showNodeList() {
        _showNodeList.value = true
    }
    
    fun hideNodeList() {
        _showNodeList.value = false
    }
    
    fun resetFilter() {
        _filterUnavailable.value = false
    }
    
    fun dismissNotice() {
        _notice.value = null
    }
    
    fun dismissUpdate() {
        _updateInfo.value = null
    }
    
    fun clearError() {
        _error.value = null
    }

    fun clearInfoDialogMessage() {
        _infoDialogMessage.value = null
    }
    
    fun acceptUserAgreement() {
        viewModelScope.launch {
            settingsRepository.setUserAgreementAccepted(true)
        }
    }

    // 自动化测试设置写入
    fun setAutoTestEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoTestEnabled(enabled) }
    }

    fun setAutoTestFilterUnavailable(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoTestFilterUnavailable(enabled) }
    }

    fun setAutoTestLatencyEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoTestLatencyEnabled(enabled) }
    }

    fun setAutoTestLatencyMode(mode: AutoTestLatencyMode) {
        viewModelScope.launch { settingsRepository.setAutoTestLatencyMode(mode) }
    }

    fun setAutoTestLatencyThresholdMs(value: Int) {
        viewModelScope.launch { settingsRepository.setAutoTestLatencyThresholdMs(value) }
    }

    fun setAutoTestBandwidthEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthEnabled(enabled) }
    }

    fun setAutoTestBandwidthDownloadEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthDownloadEnabled(enabled) }
    }

    fun setAutoTestBandwidthUploadEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthUploadEnabled(enabled) }
    }

    fun setAutoTestBandwidthDownloadThresholdMbps(value: Int) {
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthDownloadThresholdMbps(value) }
    }

    fun setAutoTestBandwidthUploadThresholdMbps(value: Int) {
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthUploadThresholdMbps(value) }
    }

    fun setAutoTestBandwidthWifiOnly(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthWifiOnly(enabled) }
    }

    fun setAutoTestBandwidthDownloadSizeMb(value: Int) {
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthDownloadSizeMb(value) }
    }

    fun setAutoTestBandwidthUploadSizeMb(value: Int) {
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthUploadSizeMb(value) }
    }

    fun setAutoTestUnlockEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoTestUnlockEnabled(enabled) }
    }

    fun setAutoTestByRegion(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoTestByRegion(enabled) }
    }

    fun setAutoTestNodeLimit(value: Int) {
        viewModelScope.launch { settingsRepository.setAutoTestNodeLimit(value) }
    }

    fun applyPreferTestMode(modeId: String) {
        viewModelScope.launch {
            val mode = preferTestModes.value.firstOrNull { it.id == modeId } ?: return@launch
            settingsRepository.setPreferTestSelectedModeId(mode.id)
            settingsRepository.setAutoTestFilterUnavailable(mode.filterUnavailable)
            settingsRepository.setAutoTestLatencyEnabled(mode.latencyEnabled)
            settingsRepository.setAutoTestLatencyMode(mode.latencyMode)
            settingsRepository.setAutoTestLatencyThresholdMs(mode.latencyThresholdMs)
            settingsRepository.setAutoTestBandwidthEnabled(mode.bandwidthEnabled)
            settingsRepository.setAutoTestBandwidthDownloadEnabled(mode.bandwidthDownloadEnabled)
            settingsRepository.setAutoTestBandwidthUploadEnabled(mode.bandwidthUploadEnabled)
            settingsRepository.setAutoTestBandwidthDownloadThresholdMbps(mode.bandwidthDownloadThresholdMbps)
            settingsRepository.setAutoTestBandwidthUploadThresholdMbps(mode.bandwidthUploadThresholdMbps)
            settingsRepository.setAutoTestBandwidthWifiOnly(mode.bandwidthWifiOnly)
            settingsRepository.setAutoTestBandwidthDownloadSizeMb(mode.bandwidthDownloadSizeMb)
            settingsRepository.setAutoTestBandwidthUploadSizeMb(mode.bandwidthUploadSizeMb)
            settingsRepository.setAutoTestUnlockEnabled(mode.unlockEnabled)
            settingsRepository.setAutoTestByRegion(mode.byRegion)
            settingsRepository.setAutoTestNodeLimit(mode.nodeLimit)
        }
    }

    fun saveCurrentPreferTestMode(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _error.value = "模式名称不能为空"
            return
        }
        viewModelScope.launch {
            val existing = preferTestModes.value
            val selectedId = preferTestSelectedModeId.value
            val selectedMode = existing.firstOrNull { it.id == selectedId }
            val modeId = if (selectedMode != null && !selectedMode.builtIn) selectedMode.id else "custom_${UUID.randomUUID()}"
            val newMode = TestPreferMode(
                id = modeId,
                name = trimmed,
                builtIn = false,
                filterUnavailable = autoTestFilterUnavailable.value,
                latencyEnabled = autoTestLatencyEnabled.value,
                latencyMode = autoTestLatencyMode.value,
                latencyThresholdMs = autoTestLatencyThresholdMs.value,
                bandwidthEnabled = autoTestBandwidthEnabled.value,
                bandwidthDownloadEnabled = autoTestBandwidthDownloadEnabled.value,
                bandwidthUploadEnabled = autoTestBandwidthUploadEnabled.value,
                bandwidthDownloadThresholdMbps = autoTestBandwidthDownloadThresholdMbps.value,
                bandwidthUploadThresholdMbps = autoTestBandwidthUploadThresholdMbps.value,
                bandwidthWifiOnly = autoTestBandwidthWifiOnly.value,
                bandwidthDownloadSizeMb = autoTestBandwidthDownloadSizeMb.value,
                bandwidthUploadSizeMb = autoTestBandwidthUploadSizeMb.value,
                unlockEnabled = autoTestUnlockEnabled.value,
                byRegion = autoTestByRegion.value,
                nodeLimit = autoTestNodeLimit.value,
                defaultPriority = selectedMode?.defaultPriority ?: BestNodePriority.LATENCY,
                unlockPriorityMode = selectedMode?.unlockPriorityMode ?: UnlockPriorityMode.COUNT,
                unlockPriorityTargetSiteIds = selectedMode?.unlockPriorityTargetSiteIds ?: emptyList()
            )
            val updated = existing.filterNot { it.id == modeId || (!it.builtIn && it.name == trimmed) } + newMode
            settingsRepository.setPreferTestModes(updated)
            settingsRepository.setPreferTestSelectedModeId(newMode.id)
            _error.value = "已保存模式：${newMode.name}"
        }
    }

    fun createPreferTestModeFromCurrent() {
        viewModelScope.launch {
            val existing = preferTestModes.value
            val currentId = preferTestSelectedModeId.value
            val currentMode = existing.firstOrNull { it.id == currentId }
            val usedNames = existing.map { it.name }.toSet()
            val baseName = "新模式"
            var index = 1
            var candidateName = baseName
            while (candidateName in usedNames) {
                index++
                candidateName = "$baseName $index"
            }

            val newMode = TestPreferMode(
                id = "custom_${UUID.randomUUID()}",
                name = candidateName,
                builtIn = false,
                filterUnavailable = autoTestFilterUnavailable.value,
                latencyEnabled = autoTestLatencyEnabled.value,
                latencyMode = autoTestLatencyMode.value,
                latencyThresholdMs = autoTestLatencyThresholdMs.value,
                bandwidthEnabled = autoTestBandwidthEnabled.value,
                bandwidthDownloadEnabled = autoTestBandwidthDownloadEnabled.value,
                bandwidthUploadEnabled = autoTestBandwidthUploadEnabled.value,
                bandwidthDownloadThresholdMbps = autoTestBandwidthDownloadThresholdMbps.value,
                bandwidthUploadThresholdMbps = autoTestBandwidthUploadThresholdMbps.value,
                bandwidthWifiOnly = autoTestBandwidthWifiOnly.value,
                bandwidthDownloadSizeMb = autoTestBandwidthDownloadSizeMb.value,
                bandwidthUploadSizeMb = autoTestBandwidthUploadSizeMb.value,
                unlockEnabled = autoTestUnlockEnabled.value,
                byRegion = autoTestByRegion.value,
                nodeLimit = autoTestNodeLimit.value,
                defaultPriority = currentMode?.defaultPriority ?: BestNodePriority.LATENCY,
                unlockPriorityMode = currentMode?.unlockPriorityMode ?: UnlockPriorityMode.COUNT,
                unlockPriorityTargetSiteIds = currentMode?.unlockPriorityTargetSiteIds ?: emptyList()
            )
            settingsRepository.setPreferTestModes(existing + newMode)
            settingsRepository.setPreferTestSelectedModeId(newMode.id)
            _error.value = "已新增模式：${newMode.name}"
        }
    }

    fun deleteCurrentPreferTestMode() {
        viewModelScope.launch {
            val currentId = preferTestSelectedModeId.value
            val currentMode = preferTestModes.value.firstOrNull { it.id == currentId }
            if (currentMode == null) return@launch
            if (currentMode.builtIn) {
                _error.value = "内置模式不可删除"
                return@launch
            }
            val updated = preferTestModes.value.filterNot { it.id == currentMode.id }
            settingsRepository.setPreferTestModes(updated)
            settingsRepository.setPreferTestSelectedModeId(BUILTIN_PREFER_MODE_CHAT)
            applyPreferTestMode(BUILTIN_PREFER_MODE_CHAT)
            _error.value = "已删除模式：${currentMode.name}"
        }
    }

    fun updateCurrentPreferModePriority(priority: BestNodePriority) {
        viewModelScope.launch {
            val currentId = preferTestSelectedModeId.value
            val existing = preferTestModes.value
            val target = existing.firstOrNull { it.id == currentId } ?: return@launch
            val updatedMode = target.copy(defaultPriority = priority)
            val updated = existing.map { if (it.id == currentId) updatedMode else it }
            settingsRepository.setPreferTestModes(updated)
        }
    }

    fun updateCurrentPreferModeUnlockPriority(
        mode: UnlockPriorityMode,
        targetSiteIds: List<String>
    ) {
        viewModelScope.launch {
            val currentId = preferTestSelectedModeId.value
            val existing = preferTestModes.value
            val target = existing.firstOrNull { it.id == currentId } ?: return@launch
            val updatedMode = target.copy(
                unlockPriorityMode = mode,
                unlockPriorityTargetSiteIds = targetSiteIds.distinct()
            )
            val updated = existing.map { if (it.id == currentId) updatedMode else it }
            settingsRepository.setPreferTestModes(updated)
        }
    }

    fun startAutomatedTest(
        preferPriority: BestNodePriority? = null,
        connectBestAfterDone: Boolean = false,
        configOverride: AutoTestConfig? = null
    ) {
        if (_autoTestProgress.value.running) return

        val config = configOverride ?: AutoTestConfig(
            enabled = autoTestEnabled.value,
            filterUnavailable = autoTestFilterUnavailable.value,
            latencyEnabled = autoTestLatencyEnabled.value,
            latencyMode = autoTestLatencyMode.value,
            latencyThresholdMs = autoTestLatencyThresholdMs.value,
            bandwidthEnabled = autoTestBandwidthEnabled.value,
            bandwidthDownloadEnabled = autoTestBandwidthDownloadEnabled.value,
            bandwidthUploadEnabled = autoTestBandwidthUploadEnabled.value,
            bandwidthDownloadThresholdMbps = autoTestBandwidthDownloadThresholdMbps.value,
            bandwidthUploadThresholdMbps = autoTestBandwidthUploadThresholdMbps.value,
            bandwidthWifiOnly = autoTestBandwidthWifiOnly.value,
            bandwidthDownloadSizeMb = autoTestBandwidthDownloadSizeMb.value,
            bandwidthUploadSizeMb = autoTestBandwidthUploadSizeMb.value,
            unlockEnabled = autoTestUnlockEnabled.value,
            byRegion = autoTestByRegion.value,
            nodeLimit = autoTestNodeLimit.value
        )

        autoTestJob = viewModelScope.launch {
            _autoTestResultSnapshot.value = emptyList()
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                _isAutoSelecting.value = false
                return@launch
            }
            if (!GlobalTestExecution.tryStart("测试择优")) {
                _error.value = GlobalTestExecution.busyHint()
                _isAutoSelecting.value = false
                return@launch
            }
            try {
                _autoTestProgress.value = AutoTestProgress(
                    running = true,
                    stage = AutoTestStage.FETCH_NODES,
                    message = "正在拉取节点..."
                )

                val fetchOk = fetchNodesInternal(
                    bypassThrottle = true,
                    runUrlTest = false,
                    allowWhenTestRunning = true,
                    fetchingLabel = "请求节点中"
                )
                if (!fetchOk) {
                    _error.value = "自动化测试失败：请求节点失败"
                    _autoTestProgress.value = AutoTestProgress(
                        running = false,
                        stage = AutoTestStage.FAILED,
                        message = "请求节点失败"
                    )
                    return@launch
                }

                // 在获取后始终读取新的数据库快照，以避免陈旧的 Flow 缓存
                // 导致在长时间运行的自动化测试中按 ID 更新失败。
                val latestSnapshot = nodeDao.getAllNodes().first()
                var workingNodes = selectNodesForAutoTest(latestSnapshot, config)
                val initialSelectedCount = workingNodes.size
                if (workingNodes.isEmpty()) {
                    _error.value = "自动化测试失败：没有可用节点"
                    _autoTestProgress.value = AutoTestProgress(running = false, stage = AutoTestStage.FAILED, message = "没有可用节点")
                    return@launch
                }

                if (config.latencyEnabled) {
                    val latencyModeLabel = if (config.latencyMode == AutoTestLatencyMode.TCPING) "TCPing" else "URL Test"
                    _autoTestProgress.value = AutoTestProgress(
                        running = true,
                        stage = AutoTestStage.LATENCY_TEST,
                        message = "正在进行 $latencyModeLabel...",
                        total = workingNodes.size
                    )

                    if (config.latencyMode == AutoTestLatencyMode.TCPING) {
                        internalTestNodes(workingNodes) { completed, total ->
                            _autoTestProgress.value = AutoTestProgress(
                                running = true,
                                stage = AutoTestStage.LATENCY_TEST,
                                message = "正在进行 TCPing...",
                                completed = completed,
                                total = total
                            )
                        }
                    } else {
                        urlTestAllNodesInternal(
                            targetNodes = workingNodes,
                            requireGlobalLock = false,
                            onProgress = { completed, total ->
                                _autoTestProgress.value = AutoTestProgress(
                                    running = true,
                                    stage = AutoTestStage.LATENCY_TEST,
                                    message = "正在进行 URL Test...",
                                    completed = completed,
                                    total = total
                                )
                            }
                        )
                        waitForUrlTestCompletion(startTimeoutMs = 12_000, finishTimeoutMs = 120_000)
                    }

                    val latestNodesById = nodeDao.getAllNodes().first().associateBy { it.id }
                    workingNodes = workingNodes.mapNotNull { latestNodesById[it.id] }

                    _autoTestProgress.value = AutoTestProgress(
                        running = true,
                        stage = AutoTestStage.FILTER_LATENCY,
                        message = "按延迟阈值筛选节点...",
                        total = workingNodes.size
                    )

                    val latencyFiltered = if (config.filterUnavailable) {
                        workingNodes.filter { it.isAvailable && it.latency > 0 && it.latency <= config.latencyThresholdMs }
                    } else {
                        workingNodes
                    }

                    val removedByLatency = workingNodes.filterNot { node -> latencyFiltered.any { it.id == node.id } }
                    removedByLatency.forEach { node ->
                        nodeDao.updateAutoTestStatus(node.id, false, "LATENCY_FILTERED", System.currentTimeMillis())
                    }
                    latencyFiltered.forEach { node ->
                        val status = if (node.latency > 0) "LATENCY_PASSED" else "LATENCY_SKIPPED"
                        nodeDao.updateAutoTestStatus(node.id, node.isAvailable, status, System.currentTimeMillis())
                    }

                    workingNodes = latencyFiltered
                } else {
                    workingNodes.forEach { node ->
                        nodeDao.updateAutoTestStatus(node.id, node.isAvailable, "LATENCY_SKIPPED", System.currentTimeMillis())
                    }
                }

                if (config.bandwidthEnabled) {
                    val wifiAllowed = !config.bandwidthWifiOnly || NetworkUtils.isWifiConnected(getApplication())
                    if (!wifiAllowed) {
                        _error.value = "当前非 Wi-Fi 网络，已跳过带宽测试"
                    } else if (!config.bandwidthDownloadEnabled && !config.bandwidthUploadEnabled) {
                        _error.value = "未选择上下行带宽测试方向，已跳过带宽测试"
                    } else {
                        val bandwidthDirectionList = mutableListOf<String>()
                        if (config.bandwidthDownloadEnabled) bandwidthDirectionList.add("下行")
                        if (config.bandwidthUploadEnabled) bandwidthDirectionList.add("上行")
                        val bandwidthDirections = bandwidthDirectionList.joinToString("+")
                        _autoTestProgress.value = AutoTestProgress(
                            running = true,
                            stage = AutoTestStage.BANDWIDTH_TEST,
                            message = "逐节点带宽测试中（$bandwidthDirections）...",
                            total = workingNodes.size
                        )

                        val downloadBandwidthMap = mutableMapOf<String, Float>()
                        val uploadBandwidthMap = mutableMapOf<String, Float>()
                        workingNodes.forEachIndexed { index, node ->
                            _autoTestProgress.value = AutoTestProgress(
                                running = true,
                                stage = AutoTestStage.BANDWIDTH_TEST,
                                message = "带宽测试($bandwidthDirections): ${node.getDisplayName()} (${index + 1}/${workingNodes.size})",
                                completed = index + 1,
                                total = workingNodes.size
                            )
                            val downloadMbps = if (config.bandwidthDownloadEnabled) {
                                testNodeDownloadBandwidthMbps(node, config.bandwidthDownloadSizeMb)
                            } else 0f
                            val uploadMbps = if (config.bandwidthUploadEnabled) {
                                testNodeUploadBandwidthMbps(node, config.bandwidthUploadSizeMb)
                            } else 0f
                            downloadBandwidthMap[node.id] = downloadMbps
                            uploadBandwidthMap[node.id] = uploadMbps
                            nodeDao.updateBandwidth(node.id, downloadMbps, uploadMbps, System.currentTimeMillis())
                        }

                        _autoTestProgress.value = AutoTestProgress(
                            running = true,
                            stage = AutoTestStage.FILTER_BANDWIDTH,
                            message = "按带宽阈值筛选节点...",
                            total = workingNodes.size
                        )

                        val filtered = workingNodes.filter { node ->
                            val downloadThreshold = config.bandwidthDownloadThresholdMbps.toFloat()
                            val uploadThreshold = config.bandwidthUploadThresholdMbps.toFloat()
                            val downloadValue = downloadBandwidthMap[node.id] ?: 0f
                            val uploadValue = uploadBandwidthMap[node.id] ?: 0f
                            val downloadMeasured = !config.bandwidthDownloadEnabled || downloadValue > 0f
                            val uploadMeasured = !config.bandwidthUploadEnabled || uploadValue > 0f
                            val downloadPassed = !config.bandwidthDownloadEnabled || (downloadMeasured && downloadValue >= downloadThreshold)
                            val uploadPassed = !config.bandwidthUploadEnabled || (uploadMeasured && uploadValue >= uploadThreshold)
                            downloadPassed && uploadPassed
                        }
                        val removed = workingNodes.filterNot { node -> filtered.any { it.id == node.id } }
                        removed.forEach { node ->
                            nodeDao.updateAutoTestStatus(node.id, false, "BANDWIDTH_FILTERED", System.currentTimeMillis())
                        }
                        filtered.forEach { node ->
                            nodeDao.updateAutoTestStatus(node.id, node.isAvailable, "BANDWIDTH_PASSED", System.currentTimeMillis())
                        }
                        workingNodes = filtered
                    }
                    if (!wifiAllowed || (!config.bandwidthDownloadEnabled && !config.bandwidthUploadEnabled)) {
                        workingNodes.forEach { node ->
                            nodeDao.updateAutoTestStatus(node.id, node.isAvailable, "BANDWIDTH_SKIPPED", System.currentTimeMillis())
                        }
                    }
                }

                if (config.unlockEnabled && workingNodes.isNotEmpty()) {
                    val unlockConcurrency = AppConfig.AUTO_TEST_UNLOCK_CONCURRENCY.coerceAtLeast(1)
                    _autoTestProgress.value = AutoTestProgress(
                        running = true,
                        stage = AutoTestStage.UNLOCK_TEST,
                        message = "流媒体解锁测试中（并发 $unlockConcurrency）...",
                        total = workingNodes.size
                    )

                    val total = workingNodes.size
                    val completed = AtomicInteger(0)
                    coroutineScope {
                        val semaphore = Semaphore(unlockConcurrency)
                        workingNodes.map { node ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    val (summary, passed) = testNodeUnlock(node)
                                    val now = System.currentTimeMillis()
                                    nodeDao.updateUnlock(node.id, summary, passed, now)
                                    nodeDao.updateAutoTestStatus(
                                        nodeId = node.id,
                                        available = node.isAvailable && passed,
                                        status = if (passed) "UNLOCK_PASSED" else "UNLOCK_FAILED",
                                        testedAt = now
                                    )

                                    val done = completed.incrementAndGet()
                                    _autoTestProgress.value = AutoTestProgress(
                                        running = true,
                                        stage = AutoTestStage.UNLOCK_TEST,
                                        message = "流媒体解锁测试中（并发 $unlockConcurrency）...",
                                        completed = done,
                                        total = total
                                    )
                                }
                            }
                        }.awaitAll()
                    }
                }

                _autoTestProgress.value = AutoTestProgress(
                    running = false,
                    stage = AutoTestStage.DONE,
                    message = "完成：保留 ${workingNodes.size} 个节点",
                    completed = workingNodes.size,
                    total = initialSelectedCount
                )
                val currentPreferMode = preferTestModes.value.firstOrNull { it.id == preferTestSelectedModeId.value }
                val snapshotPriority = preferPriority ?: currentPreferMode?.defaultPriority ?: BestNodePriority.LATENCY
                val sortedSnapshot = sortNodesForSnapshot(
                    nodes = workingNodes,
                    priority = snapshotPriority,
                    mode = currentPreferMode
                )
                _autoTestResultSnapshot.value = sortedSnapshot.map { it.copy() }

                if (preferPriority != null && workingNodes.isNotEmpty()) {
                    val latestById = nodeDao.getAllNodes().first().associateBy { it.id }
                    val finalCandidates = workingNodes.mapNotNull { latestById[it.id] }.filter { it.isAvailable }
                    val bestNode = pickBestNode(finalCandidates, preferPriority, currentPreferMode)
                    if (bestNode != null) {
                        settingsRepository.setSelectedNodeId(bestNode.id)
                        if (connectBestAfterDone) {
                            ServiceManager.startVpn(getApplication(), bestNode, proxyMode.value)
                            _error.value = "已自动连接最优节点：${bestNode.getDisplayName()}"
                        } else {
                            _error.value = "已选择最优节点：${bestNode.getDisplayName()}"
                        }
                    } else if (connectBestAfterDone) {
                        _error.value = "测试完成，但未找到符合择优规则的节点"
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Automated test failed", e)
                _autoTestProgress.value = AutoTestProgress(
                    running = false,
                    stage = AutoTestStage.FAILED,
                    message = "失败: ${e.message}"
                )
                _error.value = "自动化测试失败: ${e.message}"
            } finally {
                if (connectBestAfterDone) {
                    _isAutoSelecting.value = false
                }
                autoTestJob = null
                GlobalTestExecution.finish()
            }
        }
    }

    fun cancelAutomatedTest() {
        autoTestJob?.cancel()
        autoTestJob = null
        _isAutoSelecting.value = false
        _autoTestProgress.value = AutoTestProgress(
            running = false,
            stage = AutoTestStage.CANCELED,
            message = "自动化测试已取消"
        )
    }

    private fun selectNodesForAutoTest(allNodes: List<Node>, config: AutoTestConfig): List<Node> {
        val limit = config.nodeLimit.coerceAtLeast(1)
        if (!config.byRegion) {
            return allNodes.take(limit)
        }

        return allNodes
            .groupBy { resolveRegionBucket(it) }
            .values
            .flatMap { regionNodes -> regionNodes.take(limit) }
    }

    private fun resolveRegionBucket(node: Node): String {
        val normalized = node.country
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } }
        if (normalized != null) {
            return normalized
        }

        return extractRegionCodeFromFlag(node.name) ?: "UNKNOWN"
    }

    private fun extractRegionCodeFromFlag(text: String): String? {
        var i = 0
        while (i < text.length) {
            val first = text.codePointAt(i)
            val firstLen = Character.charCount(first)
            if (first in 0x1F1E6..0x1F1FF && i + firstLen < text.length) {
                val second = text.codePointAt(i + firstLen)
                if (second in 0x1F1E6..0x1F1FF) {
                    val c1 = (first - 0x1F1E6 + 'A'.code).toChar()
                    val c2 = (second - 0x1F1E6 + 'A'.code).toChar()
                    return "$c1$c2"
                }
            }
            i += firstLen
        }
        return null
    }

    private suspend fun waitForCondition(timeoutMs: Long, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw IllegalStateException("等待测试阶段超时")
            }
            delay(300)
        }
    }

    private suspend fun waitForUrlTestCompletion(startTimeoutMs: Long, finishTimeoutMs: Long) {
        val start = System.currentTimeMillis()
        while (!isTesting.value && System.currentTimeMillis() - start < startTimeoutMs) {
            delay(100)
        }
        if (!isTesting.value) {
            throw IllegalStateException("URL test并没有启动")
        }
        waitForCondition(timeoutMs = finishTimeoutMs) { !isTesting.value }
    }

    private fun launchStartupDefaultTestIfNeeded(showHint: Boolean = false) {
        // 取出缓存的最新节点（避免 Room Flow 延迟导致测旧节点）
        val freshNodes = lastFetchedNodes
        lastFetchedNodes = null
        viewModelScope.launch {
            val mode = settingsRepository.startupDefaultTestMode.first()
            val ready = waitForNodesReadyWithAutoRetry(timeoutMs = AppConfig.NODE_REQUEST_TIMEOUT_MS)
            if (!ready) return@launch
            runDefaultStartupTestIfNeeded(mode, showHint, freshNodes)
        }
    }

    fun refreshNodesWithDefaultTest() {
        viewModelScope.launch {
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            if (_autoTestProgress.value.running || _isTesting.value || GlobalTestExecution.mutex.isLocked) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }
            val mode = settingsRepository.startupDefaultTestMode.first()
            val fetchOk = fetchNodesInternal(
                bypassThrottle = true,
                runUrlTest = false,
                fetchingLabel = "请求节点中"
            )
            if (!fetchOk) return@launch
            // 取出缓存的最新节点（避免 Room Flow 延迟导致测旧节点）
            val freshNodes = lastFetchedNodes
            lastFetchedNodes = null
            val ready = waitForNodesReadyWithAutoRetry(timeoutMs = AppConfig.NODE_REQUEST_TIMEOUT_MS)
            if (!ready) return@launch
            runDefaultStartupTestIfNeeded(mode, showHint = false, freshNodes)
        }
    }

    private suspend fun waitForNodesReadyWithAutoRetry(timeoutMs: Long): Boolean {
        val firstAttempt = runCatching {
            waitForCondition(timeoutMs = timeoutMs) { !isLoading.value && nodes.value.isNotEmpty() }
        }
        if (firstAttempt.isSuccess) return true

        _error.value = "请求节点超时，已自动再次请求节点..."
        val retryOk = fetchNodesInternal(
            bypassThrottle = true,
            runUrlTest = false,
            allowWhenFetchingInProgress = true,
            fetchingLabel = "请求节点中"
        )
        if (!retryOk) {
            _error.value = "请求节点超时，自动重试后仍未完成，请稍后再试"
            return false
        }

        val secondAttempt = runCatching {
            waitForCondition(timeoutMs = timeoutMs) { !isLoading.value && nodes.value.isNotEmpty() }
        }
        if (secondAttempt.isSuccess) return true

        _error.value = "请求节点超时，自动重试后仍失败，请稍后再试"
        return false
    }

    private suspend fun <T> requestWithAutoRetry(
        requestName: String,
        requestBlock: suspend () -> T
    ): Result<T> {
        val first = runCatching { requestBlock() }
        if (first.isSuccess) return Result.success(first.getOrThrow())

        _error.value = "${requestName}请求失败，已自动重试..."
        val second = runCatching { requestBlock() }
        if (second.isSuccess) return Result.success(second.getOrThrow())

        _error.value = "${requestName}请求失败，自动重试后仍失败，请稍后再试"
        return Result.failure(second.exceptionOrNull() ?: first.exceptionOrNull() ?: IllegalStateException("${requestName}请求失败"))
    }

    private fun runDefaultStartupTestIfNeeded(
        mode: StartupDefaultTestMode,
        showHint: Boolean,
        freshNodes: List<Node>? = null
    ) {
        if (mode == StartupDefaultTestMode.NONE) return
        if (_isTesting.value || _autoTestProgress.value.running) return
        when (mode) {
            StartupDefaultTestMode.TCPING -> {
                if (showHint) _error.value = "已按默认设置执行 TCPing 测试"
                testAllNodes(freshNodes)
            }
            StartupDefaultTestMode.URL_TEST -> {
                if (showHint) _error.value = "已按默认设置执行 URL Test 测试"
                urlTestAllNodes(freshNodes)
            }
            StartupDefaultTestMode.NONE -> Unit
        }
    }

    suspend fun fetchNodeIpInfo(node: Node): Result<NodeIpInfo> = withContext(Dispatchers.IO) {
        val port = pickFreePort()
        val session = UnlockTestManager.createSession(getApplication(), node, port)
        val started = session.start()
        if (!started) return@withContext Result.failure(IllegalStateException("启动节点测试代理失败"))

        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val client = NetworkClient.withUserAgent(OkHttpClient.Builder())
                .proxy(proxy)
                .connectTimeout(AppConfig.NODE_IP_INFO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(AppConfig.NODE_IP_INFO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(AppConfig.NODE_IP_INFO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url(AppConfig.NODE_IP_INFO_URL)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("请求失败: HTTP ${response.code}")
                    )
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("接口返回为空"))
                }
                val parsed = gson.fromJson(body, NodeIpInfo::class.java)
                    ?: return@withContext Result.failure(IllegalStateException("解析 IP 信息失败"))
                Result.success(parsed)
            }
        } catch (e: Exception) {
            Log.e(tag, "Fetch node IP info failed for ${node.getDisplayName()}: ${e.message}")
            Result.failure(e)
        } finally {
            session.stop()
        }
    }

    private suspend fun testNodeDownloadBandwidthMbps(node: Node, sizeMb: Int): Float = withContext(Dispatchers.IO) {
        val port = pickFreePort()
        val started = UnlockTestManager.start(getApplication(), node, port)
        if (!started) return@withContext 0f

        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val builder = NetworkClient.withUserAgent(OkHttpClient.Builder())
                .proxy(proxy)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
            if (AppConfig.AUTO_TEST_BANDWIDTH_DOWNLOAD_TIMEOUT_MS > 0L) {
                builder.callTimeout(AppConfig.AUTO_TEST_BANDWIDTH_DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            val client = builder.build()

            val bytes = sizeMb.toLong() * 1_000_000L
            val url = "${AppConfig.SPEED_TEST_DOWNLOAD_URL}?bytes=$bytes&r=${System.currentTimeMillis()}"
            val request = Request.Builder().url(url).build()

            val start = System.currentTimeMillis()
            var total = 0L
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext 0f
                val source = resp.body?.source() ?: return@withContext 0f
                val buffer = ByteArray(8192)
                var read: Int
                while (source.read(buffer).also { read = it } != -1) {
                    total += read
                }
            }
            val durationMs = (System.currentTimeMillis() - start).coerceAtLeast(1)
            (total * 8f / 1_000_000f) / (durationMs / 1000f)
        } catch (e: Exception) {
            Log.e(tag, "Download bandwidth test failed for ${node.getDisplayName()}: ${e.message}")
            0f
        } finally {
            UnlockTestManager.stop()
        }
    }

    private suspend fun testNodeUploadBandwidthMbps(node: Node, sizeMb: Int): Float = withContext(Dispatchers.IO) {
        val port = pickFreePort()
        val started = UnlockTestManager.start(getApplication(), node, port)
        if (!started) return@withContext 0f

        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val builder = NetworkClient.withUserAgent(OkHttpClient.Builder())
                .proxy(proxy)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
            if (AppConfig.AUTO_TEST_BANDWIDTH_UPLOAD_TIMEOUT_MS > 0L) {
                builder.callTimeout(AppConfig.AUTO_TEST_BANDWIDTH_UPLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            val client = builder.build()

            val bytes = sizeMb.toLong() * 1_000_000L
            val randomBuffer = ByteArray(8192) { 0x5A.toByte() }
            val requestBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()

                override fun contentLength(): Long = bytes

                override fun writeTo(sink: BufferedSink) {
                    var uploaded = 0L
                    while (uploaded < bytes) {
                        val remain = bytes - uploaded
                        val writeSize = minOf(remain.toInt(), randomBuffer.size)
                        sink.write(randomBuffer, 0, writeSize)
                        uploaded += writeSize
                    }
                }
            }

            val request = Request.Builder()
                .url(AppConfig.SPEED_TEST_UPLOAD_URL)
                .post(requestBody)
                .build()

            val start = System.currentTimeMillis()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext 0f
                resp.body?.close()
            }
            val durationMs = (System.currentTimeMillis() - start).coerceAtLeast(1)
            (bytes * 8f / 1_000_000f) / (durationMs / 1000f)
        } catch (e: Exception) {
            Log.e(tag, "Upload bandwidth test failed for ${node.getDisplayName()}: ${e.message}")
            0f
        } finally {
            UnlockTestManager.stop()
        }
    }

    private suspend fun testNodeUnlock(node: Node): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        val port = pickFreePort()
        val session = UnlockTestManager.createSession(getApplication(), node, port)
        val started = session.start()
        if (!started) return@withContext "启动测试代理失败" to false

        try {
            val result = UnlockTestsRunner.run(
                context = getApplication(),
                args = listOf(
                    "-socks-proxy", "socks5://127.0.0.1:$port",
                    "-f", "0",
                    "-L", "zh",
                    "-b=false",
                    "-s=false"
                ),
                timeoutSeconds = 120
            )
            val lines = result.stdout.lineSequence()
                .map { cleanLogLine(it) }
                .filter { it.isNotEmpty() }
                .filter { it.contains("YES", true) || it.contains("NO", true) || it.contains("Available", true) || it.contains("Region", true) }
                .filterNot { it.contains(Regex("""\bYES\s*=\s*\d+""", RegexOption.IGNORE_CASE)) || it.contains(Regex("""\bNO\s*=\s*\d+""", RegexOption.IGNORE_CASE)) }
                .distinct()
                .take(200)
                .toList()

            val yes = lines.count {
                (it.contains(Regex("""\bYES\b""", RegexOption.IGNORE_CASE)) || it.contains("Available", true)) &&
                    !it.contains(Regex("""\bNO\b""", RegexOption.IGNORE_CASE))
            }
            val no = lines.count { it.contains(Regex("""\bNO\b""", RegexOption.IGNORE_CASE)) }
            val pass = yes >= 3 && yes >= no
            val fullResult = if (lines.isEmpty()) {
                if (result.stdout.isBlank()) "未识别到解锁结果" else "未识别关键结果（可在流媒体工具里查看完整输出）"
            } else {
                "YES=$yes, NO=$no\n" + lines.joinToString("\n")
            }
            fullResult to pass
        } catch (e: Exception) {
            Log.e(tag, "Unlock test failed for ${node.getDisplayName()}: ${e.message}")
            ("解锁测试失败: ${e.message}") to false
        } finally {
            session.stop()
        }
    }

    private fun pickBestNode(
        candidates: List<Node>,
        priority: BestNodePriority,
        mode: TestPreferMode? = null
    ): Node? {
        if (candidates.isEmpty()) return null
        return when (priority) {
            BestNodePriority.LATENCY -> candidates
                .filter { it.latency > 0 }
                .minWithOrNull(compareBy<Node> { it.latency }.thenByDescending { it.downloadMbps }.thenByDescending { it.uploadMbps })
            BestNodePriority.DOWNLOAD -> candidates
                .filter { it.downloadMbps > 0f }
                .maxWithOrNull(compareBy<Node> { it.downloadMbps }.thenBy { -safeLatency(it) })
            BestNodePriority.UPLOAD -> candidates
                .filter { it.uploadMbps > 0f }
                .maxWithOrNull(compareBy<Node> { it.uploadMbps }.thenBy { -safeLatency(it) })
            BestNodePriority.UNLOCK_COUNT -> candidates
                .maxWithOrNull(
                    compareBy<Node> { unlockPriorityScore(it, mode) }
                        .thenBy { extractUnlockYesCount(it.unlockSummary) }
                        .thenBy { it.unlockPassed.compareTo(false) }
                        .thenBy { if (mode?.bandwidthEnabled == true && mode.bandwidthDownloadEnabled) it.downloadMbps else 0f }
                        .thenBy { -safeLatency(it) }
                )
        }
    }

    private fun sortNodesForSnapshot(
        nodes: List<Node>,
        priority: BestNodePriority,
        mode: TestPreferMode?
    ): List<Node> {
        return when (priority) {
            BestNodePriority.LATENCY -> nodes.sortedWith(
                compareBy<Node> { if (it.latency > 0) it.latency else Int.MAX_VALUE }
                    .thenByDescending { it.downloadMbps }
                    .thenByDescending { it.uploadMbps }
            )

            BestNodePriority.DOWNLOAD -> nodes.sortedWith(
                compareByDescending<Node> { it.downloadMbps }
                    .thenBy { safeLatency(it) }
            )

            BestNodePriority.UPLOAD -> nodes.sortedWith(
                compareByDescending<Node> { it.uploadMbps }
                    .thenBy { safeLatency(it) }
            )

            BestNodePriority.UNLOCK_COUNT -> nodes.sortedWith(
                compareByDescending<Node> { unlockPriorityScore(it, mode) }
                    .thenByDescending { extractUnlockYesCount(it.unlockSummary) }
                    .thenByDescending { it.unlockPassed }
                    .thenByDescending { if (mode?.bandwidthEnabled == true && mode.bandwidthDownloadEnabled) it.downloadMbps else 0f }
                    .thenBy { safeLatency(it) }
            )
        }
    }

    private fun unlockPriorityScore(node: Node, mode: TestPreferMode?): Int {
        if (mode == null) return extractUnlockYesCount(node.unlockSummary)
        return when (mode.unlockPriorityMode) {
            UnlockPriorityMode.COUNT -> extractUnlockYesCount(node.unlockSummary)
            UnlockPriorityMode.TARGET_SITES -> {
                if (mode.unlockPriorityTargetSiteIds.isEmpty()) {
                    extractUnlockYesCount(node.unlockSummary)
                } else {
                    countMatchedUnlockTargetSites(node.unlockSummary, mode.unlockPriorityTargetSiteIds)
                }
            }
        }
    }

    private fun countMatchedUnlockTargetSites(summary: String, targetSiteIds: List<String>): Int {
        val positiveLines = summary.lineSequence()
            .map { cleanLogLine(it) }
            .filter { it.isNotBlank() }
            .filter { line ->
                (line.contains("YES", true) || line.contains("Available", true)) &&
                    !line.contains(Regex("""\bNO\b""", RegexOption.IGNORE_CASE))
            }
            .toList()
        if (positiveLines.isEmpty()) return 0
        val presets = AppConfig.UNLOCK_PRIORITY_PRESET_SITES.associateBy { it.id }
        return targetSiteIds.distinct().count { siteId ->
            val site = presets[siteId] ?: return@count false
            positiveLines.any { line -> unlockSiteMatches(line, site) }
        }
    }

    private fun unlockSiteMatches(line: String, site: UnlockPrioritySite): Boolean {
        return site.keywords.any { keyword -> line.contains(keyword, ignoreCase = true) }
    }

    private fun safeLatency(node: Node): Int = if (node.latency > 0) node.latency else Int.MAX_VALUE

    private fun extractUnlockYesCount(summary: String): Int {
        val match = Regex("""\bYES\s*=\s*(\d+)""", RegexOption.IGNORE_CASE).find(summary)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: if (summary.contains("YES", ignoreCase = true) || summary.contains("Available", ignoreCase = true)) 1 else 0
    }

    private fun cleanLogLine(input: String): String {
        return input
            .replace(Regex("\\u001B\\[[;\\d]*[ -/]*[@-~]"), "")
            .replace(Regex("\\[[0-9;]*m"), "")
            .replace("[0m", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun pickFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private suspend fun migrateRawLinksToEncrypted() {
        runCatching {
            val current = nodeDao.getAllNodes().first()
            var migrated = 0
            current.forEach { node ->
                if (!node.isRawLinkEncrypted()) {
                    val encrypted = CryptoUtils.encryptForStorage(node.rawLink)
                    if (encrypted != node.rawLink) {
                        nodeDao.updateNode(node.copy(rawLink = encrypted))
                        migrated++
                    }
                }
            }
            if (migrated > 0) {
                Log.i(tag, "Migrated $migrated node rawLink entries to encrypted storage")
            }
        }.onFailure {
            Log.w(tag, "RawLink migration skipped: ${it.message}")
        }
    }

    private suspend fun logRawLinkEncryptionStats(scene: String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val current = nodeDao.getAllNodes().first()
            val total = current.size
            val encrypted = current.count { it.isRawLinkEncrypted() }
            val plain = total - encrypted
            Log.d("RawLinkCryptoStats", "scene=$scene total=$total encrypted=$encrypted plain=$plain")
        }.onFailure {
            Log.w("RawLinkCryptoStats", "scene=$scene stats_failed=${it.message}")
        }
    }
}
