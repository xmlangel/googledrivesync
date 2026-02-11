package uk.xmlangel.googledrivesync.util

/**
 * Annotation to store metadata for JUnit tests.
 * This information is used for reporting and generating detailed JUnit XML.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class TestMetadata(
    val description: String = "",
    val step: String = "",
    val expected: String = ""
)
