package github.kagank14561art.lansftp

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import github.kagank14561art.lansftp.model.*
import github.kagank14561art.lansftp.network.DiscoveryManager
import github.kagank14561art.lansftp.network.FileServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val discoveryManager = DiscoveryManager(application)
    private val fileServer = FileServer(
        port = 8080,
        appVersion = try {
            application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) { "1.0.0" },
        onConnectionRequested = { request -> handleConnectionRequest(request) },
        checkPermission = { deviceId, path, isWrite -> checkPermission(deviceId, path, isWrite) }
    )

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning

    private val _pendingRequest = MutableStateFlow<ConnectionRequest?>(null)
    val pendingRequest: StateFlow<ConnectionRequest?> = _pendingRequest

    private val _localDeviceName = MutableStateFlow<String?>(null)

    val discoveredDevices: StateFlow<Set<Device>> = discoveryManager.discoveredDevices
        .combine(_localDeviceName) { devices, localName ->
            devices.filter { it.name != localName }.toSet()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _blockedDevices = MutableStateFlow<Set<String>>(emptySet())
    val blockedDevices: StateFlow<Set<String>> = _blockedDevices

    private val _activeSessions = MutableStateFlow<Map<String, PermissionSession>>(emptyMap())
    
    private var responseDeferred: CompletableDeferred<PermissionSession?>? = null

    private fun checkPermission(deviceId: String, path: String, isWrite: Boolean): Boolean {
        val session = _activeSessions.value[deviceId] ?: return false
        
        // Expiry check
        session.expiryTime?.let {
            if (it > 0 && System.currentTimeMillis() > it) {
                _activeSessions.value -= deviceId
                return false
            }
        }

        return when (session.type) {
            PermissionType.ALL -> true
            PermissionType.SPECIFIC_FOLDER -> path.startsWith(session.pathConstraint ?: "")
            PermissionType.EXCLUDE_FOLDER -> !path.startsWith(session.pathConstraint ?: "")
            PermissionType.MEDIA_ONLY -> {
                val file = java.io.File(path)
                if (file.isDirectory) true
                else listOf("jpg", "png", "mp4", "mp3", "wav").any { path.lowercase().endsWith(it) }
            }
            PermissionType.DOCUMENTS_ONLY -> {
                val file = java.io.File(path)
                if (file.isDirectory) true
                else listOf("pdf", "doc", "docx", "txt", "pdf").any { path.lowercase().endsWith(it) }
            }
        }
    }

    private suspend fun handleConnectionRequest(request: ConnectionRequest): Boolean {
        if (_blockedDevices.value.contains(request.deviceId)) return false
        
        _pendingRequest.value = request
        responseDeferred = CompletableDeferred()
        val session = responseDeferred?.await()
        
        if (session != null) {
            _activeSessions.value += (request.deviceId to session)
            return true
        } else {
            _blockedDevices.value += request.deviceId
            return false
        }
    }

    fun approveConnection(type: PermissionType, durationMs: Long?, pathConstraint: String? = null) {
        val request = _pendingRequest.value ?: return
        val expiryTime = if (durationMs != null && durationMs > 0) System.currentTimeMillis() + durationMs else durationMs
        
        val session = PermissionSession(
            deviceId = request.deviceId,
            type = type,
            pathConstraint = pathConstraint,
            expiryTime = expiryTime
        )
        responseDeferred?.complete(session)
        _pendingRequest.value = null
    }

    fun rejectConnection() {
        responseDeferred?.complete(null)
        _pendingRequest.value = null
    }

    fun unblockDevice(deviceId: String) {
        _blockedDevices.value -= deviceId
    }

    fun startServer() {
        viewModelScope.launch {
            try {
                fileServer.start()
                val shortId = UUID.randomUUID().toString().take(4)
                val sanitizedName = "${Build.MODEL}-$shortId".filter { it.isLetterOrDigit() || it == '-' }.take(32)
                _localDeviceName.value = sanitizedName
                discoveryManager.registerService(8080, sanitizedName)
                discoveryManager.startDiscovery()
                _isServerRunning.value = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stopServer() {
        fileServer.stop()
        discoveryManager.stop()
        _isServerRunning.value = false
        _localDeviceName.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopServer()
    }
}
