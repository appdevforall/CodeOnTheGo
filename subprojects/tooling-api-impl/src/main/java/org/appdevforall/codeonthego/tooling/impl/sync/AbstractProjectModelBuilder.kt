package org.appdevforall.codeonthego.tooling.impl.sync

import org.appdevforall.codeonthego.project.GradleModels

/**
 * Abstract implementation of [IProjectModelBuilder].
 *
 * @author Akash Yadav
 */
abstract class AbstractProjectModelBuilder<P> :
	AbstractModelBuilder<P, GradleModels.GradleProject>(),
	IProjectModelBuilder<P>
