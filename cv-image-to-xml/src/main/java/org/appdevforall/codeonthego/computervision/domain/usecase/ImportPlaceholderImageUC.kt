package org.appdevforall.codeonthego.computervision.domain.usecase

import android.net.Uri
import org.appdevforall.codeonthego.computervision.data.repository.DrawableImportHelper
import org.appdevforall.codeonthego.computervision.data.repository.ImportedDrawable

/**
 * Use case that handles copying a user-selected image from the gallery
 * into the project's local drawable resources folder.
 */
class ImportPlaceholderImageUC(private val drawableImportHelper: DrawableImportHelper) {
    suspend operator fun invoke(
        uri: Uri,
        layoutFilePath: String?,
        placeholderId: String
    ): Result<ImportedDrawable> {
        return drawableImportHelper.importDrawable(
            sourceUri = uri,
            layoutFilePath = layoutFilePath,
            fallbackName = "imported_image_$placeholderId"
        )
    }
}
