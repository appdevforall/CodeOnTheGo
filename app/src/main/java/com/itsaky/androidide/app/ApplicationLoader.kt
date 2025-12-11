package com.itsaky.androidide.app

/**
 * An application loader is responsible for loading an application
 * instance in different contexts.
 *
 * @author Akash Yadav
 */
internal interface ApplicationLoader {

	/**
	 * Called to perform application initialization with this loader.
	 *
	 * @param app The application instance to initialize.
	 */
	suspend fun load(app: IDEApplication)
}