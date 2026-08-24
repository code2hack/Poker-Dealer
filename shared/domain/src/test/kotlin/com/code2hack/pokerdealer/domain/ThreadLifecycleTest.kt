package com.code2hack.pokerdealer.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreadLifecycleTest {
    private val selected = thread("root")

    @Test
    fun `ready non ephemeral cascade is eligible`() {
        val preflight = ThreadCascadePreflight(selected, listOf(thread("child"), thread("archived", archived = true)))

        assertTrue(preflight.eligible)
        assertEquals(2, preflight.descendants.size)
    }

    @Test
    fun `active ephemeral and unknown descendants fail closed`() {
        assertFalse(
            ThreadCascadePreflight(selected, listOf(thread("busy", workState = ThreadWorkState.BUSY))).eligible,
        )
        assertFalse(
            ThreadCascadePreflight(selected, listOf(thread("ephemeral", ephemeral = true))).eligible,
        )
        assertFalse(
            ThreadCascadePreflight(selected, listOf(thread("unknown", ephemeral = null))).eligible,
        )
        assertFalse(
            ThreadCascadePreflight(selected, listOf(thread("unknown-state", workState = null))).eligible,
        )
    }

    @Test
    fun `confirmation becomes stale when scope or safety metadata changes`() {
        val reviewed = ThreadCascadePreflight(selected, listOf(thread("child")))

        assertTrue(reviewed.safetyMatches(ThreadCascadePreflight(selected, listOf(thread("child")))))
        assertFalse(reviewed.safetyMatches(ThreadCascadePreflight(selected, listOf(thread("other")))))
        assertFalse(
            reviewed.safetyMatches(
                ThreadCascadePreflight(selected, listOf(thread("child", workState = ThreadWorkState.BUSY))),
            ),
        )
    }

    private fun thread(
        id: String,
        archived: Boolean = false,
        ephemeral: Boolean? = false,
        workState: ThreadWorkState? = ThreadWorkState.READY,
    ) = DiscoveredThread(
        locator = CodexThreadLocator("spark", id),
        archived = archived,
        ephemeral = ephemeral,
        workState = workState,
    )
}
