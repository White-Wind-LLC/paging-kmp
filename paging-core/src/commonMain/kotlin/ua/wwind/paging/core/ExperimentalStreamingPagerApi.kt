package ua.wwind.paging.core

@RequiresOptIn(
    message = "StreamingPager API is experimental and may change without notice.",
    level = RequiresOptIn.Level.WARNING
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.CONSTRUCTOR
)
public annotation class ExperimentalStreamingPagerApi
