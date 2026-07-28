package com.code2hack.pokerdealer.domain

data class ModelProviderOption(
    val id: String,
    val label: String,
)

data class ModelOption(
    val model: String,
    val displayName: String,
    val reasoningEfforts: List<String> = emptyList(),
)

data class PermissionRequirements(
    val allowedSandboxModes: Set<String>? = null,
    val allowedApprovalPolicies: Set<String>? = null,
)

enum class PermissionPreset(
    val label: String,
    val sandbox: String? = null,
    val approvalPolicy: String? = null,
    val approvalsReviewer: String? = null,
) {
    HOST_DEFAULT("Host default"),
    ASK_ON_PHONE("Ask on phone", "workspace-write", "on-request", "user"),
    AUTO_REVIEW("Auto review", "workspace-write", "on-request", "auto_review"),
    READ_ONLY("Read-only", "read-only", "never"),
    YOLO("YOLO", "danger-full-access", "never"),
    ;

    fun unavailableReason(requirements: PermissionRequirements): String? = when {
        sandbox != null &&
            requirements.allowedSandboxModes?.contains(sandbox) == false ->
            "$sandbox is disallowed by host requirements"
        approvalPolicy != null &&
            requirements.allowedApprovalPolicies?.contains(approvalPolicy) == false ->
            "$approvalPolicy is disallowed by host requirements"
        else -> null
    }
}

data class ThreadStartCatalog(
    val workingDirectory: String,
    val defaultProviderId: String? = null,
    val defaultModel: String? = null,
    val defaultReasoningEffort: String? = null,
    val defaultSandbox: String? = null,
    val defaultApprovalPolicy: String? = null,
    val defaultApprovalsReviewer: String? = null,
    val providers: List<ModelProviderOption> = emptyList(),
    val models: List<ModelOption> = emptyList(),
    val requirements: PermissionRequirements = PermissionRequirements(),
)

data class ThreadStartSelection(
    val workingDirectory: String,
    val providerOverride: String? = null,
    val modelOverride: String? = null,
    val reasoningEffort: String? = null,
    val permissionPreset: PermissionPreset = PermissionPreset.HOST_DEFAULT,
) {
    fun hasControlOverrides(): Boolean =
        providerOverride?.isNotBlank() == true ||
            modelOverride?.isNotBlank() == true ||
            permissionPreset != PermissionPreset.HOST_DEFAULT

    fun validated(catalog: ThreadStartCatalog): ThreadStartSelection {
        require(workingDirectory.startsWith('/') && '\u0000' !in workingDirectory) {
            "Working directory must be an absolute host path"
        }
        require(workingDirectory == catalog.workingDirectory) {
            "Review settings again after changing the working directory"
        }
        permissionPreset.unavailableReason(catalog.requirements)?.let {
            throw IllegalArgumentException(it)
        }
        val selectedModel = modelOverride?.trim()?.takeIf(String::isNotEmpty) ?: catalog.defaultModel
        if (reasoningEffort != null) {
            val advertised = catalog.models.singleOrNull { it.model == selectedModel }?.reasoningEfforts.orEmpty()
            require(reasoningEffort in advertised) {
                "Reasoning effort is not advertised for the selected model"
            }
        }
        return copy(
            providerOverride = providerOverride?.trim()?.takeIf(String::isNotEmpty),
            modelOverride = modelOverride?.trim()?.takeIf(String::isNotEmpty),
        )
    }
}
