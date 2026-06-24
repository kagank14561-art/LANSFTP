package github.kagank14561art.lansftp.model

import kotlinx.serialization.Serializable

@Serializable
data class Device(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int,
    val version: String
)

@Serializable
data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val isRoot: Boolean = false
)

@Serializable
data class ConnectionRequest(
    val deviceId: String,
    val deviceName: String,
    val version: String,
    val publicKey: String // For future encryption
)

@Serializable
data class ConnectionResponse(
    val approved: Boolean,
    val message: String? = null
)

@Serializable
enum class PermissionType {
    ALL, SPECIFIC_FOLDER, MEDIA_ONLY, DOCUMENTS_ONLY, EXCLUDE_FOLDER
}

data class PermissionSession(
    val deviceId: String,
    val type: PermissionType,
    val pathConstraint: String? = null,
    val expiryTime: Long? = null // ms timestamp, null for permanent, 0 for one-time
)
