package com.itsaky.androidide.plugins

@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This declaration is internal to the IDE and is not part of the plugin API contract. " +
        "It may change or be removed at any time without a major version bump."
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
)
annotation class InternalPluginApi
