package de.b21print

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build

// What each camera offers for a portrait mode: depth output, the vendor's bokeh extension, and the
// physical cameras behind a logical one. Text for the debug console log.
object CameraInfo {
    private const val DEPTH_JPEG = 0x69656963   // ImageFormat.DEPTH_JPEG (API 29)

    private val extensions = mapOf(
        CameraExtensionCharacteristics.EXTENSION_AUTOMATIC to "automatic",
        CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH to "face retouch",
        CameraExtensionCharacteristics.EXTENSION_BOKEH to "bokeh",
        CameraExtensionCharacteristics.EXTENSION_HDR to "hdr",
        CameraExtensionCharacteristics.EXTENSION_NIGHT to "night",
    )

    fun describe(c: Context): List<String> {
        val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val out = mutableListOf("Cameras: ${cm.cameraIdList.size}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            out += line(id, ch)
            if (Build.VERSION.SDK_INT >= 31) runCatching {
                val ext = cm.getCameraExtensionCharacteristics(id).supportedExtensions
                out += "    extensions: " + if (ext.isEmpty()) "none" else ext.joinToString { extensions[it] ?: "#$it" }
            }
            if (Build.VERSION.SDK_INT >= 28) for (pid in ch.physicalCameraIds) {
                out += line("$id/$pid", cm.getCameraCharacteristics(pid), "    ")
            }
        }
        out += "A depth output or a bokeh extension on the front camera would allow a portrait mode in the booth; without both, a software mask is needed."
        return out
    }

    private fun line(id: String, ch: CameraCharacteristics, indent: String = ""): String {
        val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "?"
        }
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        val depthCap = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT in caps
        val logical = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
        val formats = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.outputFormats?.toList().orEmpty()
        val depth = buildList {
            if (ImageFormat.DEPTH16 in formats) add("DEPTH16")
            if (ImageFormat.DEPTH_POINT_CLOUD in formats) add("point cloud")
            if (DEPTH_JPEG in formats) add("depth JPEG")
        }
        return "${indent}camera $id: $facing" + (if (logical) ", logical multi-camera" else "") +
            ", depth output: " + (if (depthCap || depth.isNotEmpty()) "yes (${depth.joinToString().ifEmpty { "capability only" }})" else "no")
    }
}
