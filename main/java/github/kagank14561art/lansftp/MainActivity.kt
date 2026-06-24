package github.kagank14561art.lansftp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import github.kagank14561art.lansftp.model.*
import github.kagank14561art.lansftp.ui.theme.LANSFTPTheme
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        checkAndRequestPermissions()

        setContent {
            LANSFTPTheme {
                var selectedDevice by remember { mutableStateOf<Device?>(null) }
                var showSettings by remember { mutableStateOf(false) }
                var pickedFileUri by remember { mutableStateOf<Uri?>(null) }
                
                if (showSettings) {
                    SettingsScreen(viewModel, onBack = { showSettings = false })
                } else if (selectedDevice == null) {
                    MainScreen(
                        viewModel = viewModel, 
                        onDeviceClick = { selectedDevice = it },
                        onOpenSettings = { showSettings = true },
                        onFilePicked = { uri -> pickedFileUri = uri }
                    )
                } else {
                    FileExplorerScreen(
                        device = selectedDevice!!,
                        isPickMode = pickedFileUri != null,
                        pickedFileUri = pickedFileUri,
                        onFileSent = { pickedFileUri = null; selectedDevice = null },
                        onBack = { 
                            selectedDevice = null
                        }
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(neededPermissions.toTypedArray())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel, 
    onDeviceClick: (Device) -> Unit, 
    onOpenSettings: () -> Unit,
    onFilePicked: (Uri) -> Unit
) {
    val isRunning by viewModel.isServerRunning.collectAsState()
    val devices by viewModel.discoveredDevices.collectAsState()
    val pendingRequest by viewModel.pendingRequest.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let { onFilePicked(it) }
        }
    )

    if (pendingRequest != null) {
        PermissionDialog(
            request = pendingRequest!!,
            onApprove = { type, duration, path -> viewModel.approveConnection(type, duration, path) },
            onReject = { viewModel.rejectConnection() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LANSFTP") },
                actions = {
                    TextButton(onClick = onOpenSettings) {
                        Text("Engellenenler")
                    }
                }
            )
        },
        floatingActionButton = {
            var showMenu by remember { mutableStateOf(false) }
            Box {
                FloatingActionButton(onClick = { showMenu = true }) {
                    Text("+")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Dosya Seç") },
                        onClick = { 
                            filePickerLauncher.launch(arrayOf("*/*"))
                            showMenu = false 
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    if (isRunning) viewModel.stopServer() else viewModel.startServer()
                }
            ) {
                Text(if (isRunning) "Sunucuyu Durdur" else "Sunucuyu Başlat")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Bulunan Cihazlar:", style = MaterialTheme.typography.titleMedium)

            LazyColumn {
                items(devices.toList()) { device ->
                    DeviceItem(device, onClick = { onDeviceClick(device) })
                }
            }
        }
    }
}

@Composable
fun PermissionDialog(
    request: ConnectionRequest,
    onApprove: (PermissionType, Long?, String?) -> Unit,
    onReject: () -> Unit
) {
    var selectedType by remember { mutableStateOf(PermissionType.ALL) }
    var selectedDurationMs by remember { mutableStateOf<Long?>(null) } // null = Kalıcı
    var pathConstraint by remember { mutableStateOf("") }

    val durations = listOf(
        "Tek Seferlik" to 0L,
        "5 dk" to 5 * 60 * 1000L,
        "10 dk" to 10 * 60 * 1000L,
        "30 dk" to 30 * 60 * 1000L,
        "1 sa" to 60 * 60 * 1000L,
        "2 sa" to 2 * 60 * 60 * 1000L,
        "4 sa" to 4 * 60 * 60 * 1000L,
        "Kalıcı" to null
    )

    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("Bağlantı İsteği: ${request.deviceName}") },
        text = {
            Column {
                Text("İzin Türü:")
                PermissionType.values().forEach { type ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedType == type, onClick = { selectedType = type })
                        Text(when(type) {
                            PermissionType.ALL -> "Tam Erişim"
                            PermissionType.SPECIFIC_FOLDER -> "Sadece şu klasöre eriş"
                            PermissionType.MEDIA_ONLY -> "Sadece medya dosyaları"
                            PermissionType.DOCUMENTS_ONLY -> "Sadece belgeler"
                            PermissionType.EXCLUDE_FOLDER -> "Şu klasör hariç"
                        })
                    }
                }
                
                if (selectedType == PermissionType.SPECIFIC_FOLDER || selectedType == PermissionType.EXCLUDE_FOLDER) {
                    TextField(value = pathConstraint, onValueChange = { pathConstraint = it }, label = { Text("Klasör Yolu") })
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Süre:")
                durations.forEach { (label, ms) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedDurationMs == ms, onClick = { selectedDurationMs = ms })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApprove(selectedType, selectedDurationMs, pathConstraint) }) {
                Text("Onayla")
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text("Reddet (Engelle)")
            }
        }
    )
}

@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val blockedDevices by viewModel.blockedDevices.collectAsState()

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Engellenen Cihazlar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("<")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding)) {
            items(blockedDevices.toList()) { deviceId ->
                ListItem(
                    headlineContent = { Text(deviceId) },
                    trailingContent = {
                        Button(onClick = { viewModel.unblockDevice(deviceId) }) {
                            Text("Engeli Kaldır")
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun DeviceItem(device: Device, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = device.name, style = MaterialTheme.typography.titleLarge)
            Text(text = "${device.ip}:${device.port}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    device: Device, 
    onBack: () -> Unit,
    isPickMode: Boolean = false,
    pickedFileUri: Uri? = null,
    onFileSent: () -> Unit = {}
) {
    var currentPath by remember { mutableStateOf("HOME") }
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isWaitingForPermission by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val client = remember {
        HttpClient(CIO) {
            install(ContentNegotiation) { json() }
        }
    }

    LaunchedEffect(currentPath) {
        try {
            val deviceId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            
            isWaitingForPermission = true
            val connectResponse: ConnectionResponse = client.post("http://${device.ip}:${device.port}/connect") {
                contentType(ContentType.Application.Json)
                setBody(ConnectionRequest(
                    deviceId = deviceId,
                    deviceName = Build.MODEL,
                    version = "1.0.0",
                    publicKey = ""
                ))
            }.body()

            if (connectResponse.approved) {
                isWaitingForPermission = false
                val response: List<FileItem> = client.get("http://${device.ip}:${device.port}/files") {
                    header("X-Device-Id", deviceId)
                    parameter("path", currentPath)
                }.body()
                files = response
                
                if (currentPath == "HOME" && response.isNotEmpty()) {
                    val firstItem = response.firstOrNull { it.name != ".." }
                    if (firstItem != null) {
                        currentPath = firstItem.path.substringBeforeLast("/", "/")
                    }
                }
            } else {
                onBack()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isWaitingForPermission) "İzin bekleniyor..." else currentPath, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = {
                        val parent = if (currentPath == "HOME") null else File(currentPath).parent
                        if (parent == null || currentPath == "/") {
                            onBack()
                        } else {
                            currentPath = parent
                        }
                    }) {
                        Text("<")
                    }
                },
                actions = {
                    if (isPickMode && pickedFileUri != null && !isWaitingForPermission) {
                        Button(onClick = {
                            scope.launch {
                                try {
                                    val contentResolver = context.contentResolver
                                    val fileName = contentResolver.query(pickedFileUri, null, null, null, null)?.use { cursor ->
                                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                        cursor.moveToFirst()
                                        cursor.getString(nameIndex)
                                    } ?: "dosya_${System.currentTimeMillis()}"

                                    val inputStream = contentResolver.openInputStream(pickedFileUri)
                                    if (inputStream != null) {
                                        val bytes = withContext(Dispatchers.IO) {
                                            inputStream.readBytes()
                                        }
                                        
                                        client.submitFormWithBinaryData(
                                            url = "http://${device.ip}:${device.port}/upload",
                                            formData = formData {
                                                append("file", bytes, Headers.build {
                                                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                                                })
                                            }
                                        ) {
                                            header("X-Device-Id", android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID))
                                            parameter("path", currentPath)
                                        }
                                        onFileSent()
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }) {
                            Text("Buraya Kaydet")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (isWaitingForPermission) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Karşı cihazdan izin bekleniyor...")
                }
            }
        } else {
            LazyColumn(modifier = Modifier.padding(innerPadding)) {
                items(files) { file ->
                    ListItem(
                        headlineContent = { Text(file.name) },
                        supportingContent = { Text(if (file.isDirectory) "Klasör" else "${file.size} bayt") },
                        trailingContent = {
                            if (!file.isDirectory && !isPickMode) {
                                IconButton(onClick = {
                                    scope.launch {
                                        try {
                                            val response = client.get("http://${device.ip}:${device.port}/download") {
                                                header("X-Device-Id", android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID))
                                                parameter("path", file.path)
                                            }
                                            val bytes = response.readRawBytes()
                                            
                                            val targetDir = File(android.os.Environment.getExternalStorageDirectory(), "Download/lansftp/download")
                                            if (!targetDir.exists()) {
                                                targetDir.mkdirs()
                                            }
                                            
                                            val localFile = File(targetDir, file.name)
                                            withContext(Dispatchers.IO) {
                                                localFile.writeBytes(bytes)
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                }) {
                                    Text("↓")
                                }
                            }
                        },
                        modifier = Modifier.clickable {
                            if (file.isDirectory) {
                                currentPath = file.path
                            }
                        }
                    )
                }
            }
        }
    }
}
