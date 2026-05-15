package org.appdevforall.codeonthego.computervision.domain.usecase

import org.appdevforall.codeonthego.computervision.data.repository.DrawableImportHelper

/**
 * Use case that handles deleting a previously imported placeholder image
 * from the project's local drawable resources folder.
 */
class RemovePlaceholderImageUC(private val drawableImportHelper: DrawableImportHelper) {
    suspend operator fun invoke(
        layoutFilePath: String?,
        resourceName: String
    ): Result<Boolean> {
        return drawableImportHelper.deleteDrawable(
            layoutFilePath = layoutFilePath,
            resourceName = resourceName
        )
    }
}
