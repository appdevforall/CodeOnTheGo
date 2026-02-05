package org.appdevforall.codeonthego.computervision.ui

import android.net.Uri

sealed class ComputerVisionEvent {
    data class ImageSelected(val uri: Uri) : ComputerVisionEvent()
    data class ImageCaptured(val uri: Uri, val success: Boolean) : ComputerVisionEvent()
    object RunDetection : ComputerVisionEvent()
    object UpdateLayoutFile : ComputerVisionEvent()
    object ConfirmUpdate : ComputerVisionEvent()
    object SaveToDownloads : ComputerVisionEvent()
    object OpenImagePicker : ComputerVisionEvent()
    object RequestCameraPermission : ComputerVisionEvent()
    data class UpdateGuides(val leftPct: Float, val rightPct: Float) : ComputerVisionEvent()
}
