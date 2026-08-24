package com.code2hack.pokerdealer.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ThreadStartSettingsTest {
    private val catalog = ThreadStartCatalog(
        workingDirectory = "/work/repo",
        defaultModel = "host-model",
        models = listOf(ModelOption("wire-model", "Model", listOf("low", "high"))),
    )

    @Test
    fun `custom provider IDs and exact model wire values remain editable`() {
        val selected = ThreadStartSelection(
            workingDirectory = "/work/repo",
            providerOverride = "  custom-provider  ",
            modelOverride = "wire-model",
            reasoningEffort = "high",
        ).validated(catalog)

        assertEquals("custom-provider", selected.providerOverride)
        assertEquals("wire-model", selected.modelOverride)
        assertEquals("high", selected.reasoningEffort)
    }

    @Test
    fun `reasoning and permission choices fail closed against advertised values`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThreadStartSelection("/work/repo", modelOverride = "custom", reasoningEffort = "high")
                .validated(catalog)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThreadStartSelection(
                "/work/repo",
                permissionPreset = PermissionPreset.YOLO,
            ).validated(
                catalog.copy(
                    requirements = PermissionRequirements(
                        allowedSandboxModes = setOf("read-only"),
                        allowedApprovalPolicies = setOf("never"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `all and only accepted permission presets are exposed`() {
        assertEquals(
            listOf("Host default", "Ask on phone", "Auto review", "Read-only", "YOLO"),
            PermissionPreset.entries.map(PermissionPreset::label),
        )
    }

    @Test
    fun `only provider model and permission changes are control-bearing resume overrides`() {
        assertEquals(false, ThreadStartSelection("/work/repo").hasControlOverrides())
        assertEquals(
            false,
            ThreadStartSelection("/work/repo", reasoningEffort = "high").hasControlOverrides(),
        )
        assertEquals(
            true,
            ThreadStartSelection("/work/repo", providerOverride = "custom").hasControlOverrides(),
        )
        assertEquals(
            true,
            ThreadStartSelection("/work/repo", modelOverride = "model").hasControlOverrides(),
        )
        assertEquals(
            true,
            ThreadStartSelection(
                "/work/repo",
                permissionPreset = PermissionPreset.READ_ONLY,
            ).hasControlOverrides(),
        )
    }
}
