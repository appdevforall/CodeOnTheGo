package com.itsaky.androidide.tooling.impl.sync

import com.itsaky.androidide.project.GradleModels

/**
 * Abstract implementation of [IProjectModelBuilder].
 *
 * @author Akash Yadav
 */
abstract class AbstractProjectModelBuilder<P> :
	AbstractModelBuilder<P, GradleModels.GradleProject>(),
	IProjectModelBuilder<P>
