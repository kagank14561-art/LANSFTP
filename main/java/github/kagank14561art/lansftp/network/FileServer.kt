package github.kagank14561art.lansftp.network


import github.kagank14561art.lansftp.model.ConnectionRequest
import github.kagank14561art.lansftp.model.ConnectionResponse
import github.kagank14561art.lansftp.model.FileItem
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.content.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FileServer(
    private val port: Int = 8080,
    private val appVersion: String,
    private val onConnectionRequested: suspend (ConnectionRequest) -> Boolean,
    private val checkPermission: (deviceId: String, path: String, isWrite: Boolean) -> Boolean
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start() {
        server = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) {
                json()
            }
            routing {
                get("/") {
                    call.respondText("LANSFTP Server is running")
                }
                get("/files") {
                    val deviceId = call.request.header("X-Device-Id") ?: return@get call.respond(io.ktor.http.HttpStatusCode.Unauthorized)
                    val path = call.parameters["path"] ?: "HOME"
                    
                    if (!checkPermission(deviceId, path, false)) {
                        return@get call.respond(io.ktor.http.HttpStatusCode.Forbidden, "Erişim Reddedildi")
                    }
                    
                    val files = listFiles(path, deviceId)
                    call.respond(files)
                }
                get("/download") {
                    val deviceId = call.request.header("X-Device-Id") ?: return@get call.respond(io.ktor.http.HttpStatusCode.Unauthorized)
                    val path = call.parameters["path"]
                    if (path != null) {
                        if (!checkPermission(deviceId, path, false)) {
                            return@get call.respond(io.ktor.http.HttpStatusCode.Forbidden, "Erişim Reddedildi")
                        }
                        val file = File(path)
                        if (file.exists() && !file.isDirectory) {
                            call.respondFile(file)
                        } else {
                            call.respond(io.ktor.http.HttpStatusCode.NotFound, "File not found")
                        }
                    } else {
                        call.respond(io.ktor.http.HttpStatusCode.BadRequest, "Missing path")
                    }
                }
                post("/upload") {
                    val deviceId = call.request.header("X-Device-Id") ?: return@post call.respond(io.ktor.http.HttpStatusCode.Unauthorized)
                    val multipart = call.receiveMultipart()
                    val pathParam = call.parameters["path"]
                    
                    val savePath = if (pathParam != null) {
                        pathParam
                    } else {
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.getDefault()).format(Date())
                        val defaultDir = File(android.os.Environment.getExternalStorageDirectory(), "LANSFTP/get/$timestamp")
                        if (!defaultDir.exists()) defaultDir.mkdirs()
                        defaultDir.absolutePath
                    }
                    
                    if (!checkPermission(deviceId, savePath, true)) {
                        return@post call.respond(io.ktor.http.HttpStatusCode.Forbidden, "Yazma İzni Yok")
                    }
                    
                    multipart.forEachPart { part ->
                        if (part is PartData.FileItem) {
                            val fileName = part.originalFileName ?: "uploaded_file"
                            val file = File(savePath, fileName)
                            part.streamProvider().use { input ->
                                file.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                        part.dispose()
                    }
                    call.respond(io.ktor.http.HttpStatusCode.OK, "Uploaded")
                }
                post("/connect") {
                    val request = call.receive<ConnectionRequest>()
                    if (request.version != appVersion) {
                        call.respond(ConnectionResponse(approved = false, message = "Version mismatch. Server: $appVersion, Client: ${request.version}"))
                        return@post
                    }
                    val approved = onConnectionRequested(request)
                    call.respond(ConnectionResponse(approved = approved, message = if (approved) "Connected" else "Rejected"))
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
    }

    private fun listFiles(path: String, deviceId: String): List<FileItem> {
        val homePath = android.os.Environment.getExternalStorageDirectory().absolutePath
        val directory = if (path == "HOME" || path.isEmpty()) {
            File(homePath)
        } else {
            File(path)
        }

        if (!directory.exists() || !directory.isDirectory) return emptyList()

        val items = mutableListOf<FileItem>()
        
        directory.parentFile?.let { parent ->
            if (checkPermission(deviceId, parent.absolutePath, false)) {
                items.add(FileItem(
                    name = "..",
                    path = parent.absolutePath,
                    isDirectory = true,
                    size = 0,
                    lastModified = 0
                ))
            }
        }

        directory.listFiles()?.forEach {
            if (checkPermission(deviceId, it.absolutePath, false)) {
                items.add(FileItem(
                    name = it.name,
                    path = it.absolutePath,
                    isDirectory = it.isDirectory,
                    size = if (it.isDirectory) 0 else it.length(),
                    lastModified = it.lastModified()
                ))
            }
        }
        
        return items
    }
}
