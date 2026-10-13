package org.appdevforall.codeonthego.tooling.impl.sync

import org.appdevforall.codeonthego.project.GradleModels

/**
 * A [model builder][IModelBuilder] used specifically building project models.
 *
 * @author Akash Yadav
 */
interface IProjectModelBuilder<P> : IModelBuilder<P, GradleModels.GradleProject>
