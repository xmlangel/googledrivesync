package uk.xmlangel.googledrivesync.util

import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit Rule that extracts metadata from @TestMetadata annotation
 * and prints it to stdout in a format that can be parsed by Gradle task.
 */
class TestMetadataRule : TestWatcher() {
    override fun starting(description: Description) {
        val testName = description.methodName
        val metadata = description.getAnnotation(TestMetadata::class.java)
        if (metadata != null) {
            println("[METADATA:$testName:description=${metadata.description}]")
            println("[METADATA:$testName:step=${metadata.step}]")
            println("[METADATA:$testName:expected_result=${metadata.expected}]")
        }
    }

    override fun succeeded(description: Description) {
        val testName = description.methodName
        println("[METADATA:$testName:actual_result=PASS]")
    }

    override fun failed(e: Throwable, description: Description) {
        val testName = description.methodName
        println("[METADATA:$testName:actual_result=FAIL: ${e.message}]")
    }
}
