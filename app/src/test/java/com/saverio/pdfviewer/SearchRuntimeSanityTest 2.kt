package com.saverio.pdfviewer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Guards against Kotlin compiler / kotlin-stdlib version mismatches where the
 * compiler emits calls to `kotlin.coroutines.jvm.internal.SpillingKt` that are
 * missing at runtime (causing NoClassDefFoundError during PDF search).
 */
class SearchRuntimeSanityTest {

    @Test
    fun kotlinStdlibMatchesCompilerCoroutineSupport() {
        val spillingClass = Class.forName("kotlin.coroutines.jvm.internal.SpillingKt")
        assertNotNull(spillingClass)
    }

    @Test
    fun suspendingLoopWithSpilledLocalsCompletesWithoutError() = runBlocking {
        val results = mutableListOf<Int>()
        for (page in 0 until 3) {
            val status = withContext(Dispatchers.Default) { "page-$page" }
            val pageHits = listOf(status, "hit-$page")
            val snap = results.toList() + pageHits.size
            withContext(Dispatchers.Default) {
                results.add(snap.last())
            }
        }
        assertEquals(listOf(2, 2, 2), results)
    }
}
