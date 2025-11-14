package com.itsaky.androidide.tooling.impl.sync

import com.itsaky.androidide.project.GradleModels

/**
 * A [model builder][IModelBuilder] used specifically building project models.
 *
 * @author Akash Yadav
 */
interface IProjectModelBuilder<P> : IModelBuilder<P, GradleModels.GradleProject>
