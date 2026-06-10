package com.holderzone.hardware.camera

/**
 * Snapshot of a selectable camera or video device exposed by the active backend.
 *
 * The [id] is backend-scoped:
 * - CameraX / Camera2 use the underlying camera id.
 * - Camera1 uses the legacy camera index as a string.
 * - UVC uses a synthetic USB device key.
 */
data class AvailableCamera(
    val index: Int,
    val id: String,
    val displayName: String,
    val backend: CameraBackend,
    val lensFacing: LensFacing? = null,
    val isActive: Boolean = false,
)
