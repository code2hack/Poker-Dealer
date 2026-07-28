package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.ModelOption
import com.code2hack.pokerdealer.domain.ModelProviderOption
import com.code2hack.pokerdealer.domain.PermissionRequirements
import com.code2hack.pokerdealer.domain.ThreadStartCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class HostThreadStartSettings(
    private val appServer: CodexAppServerSession,
) {
    suspend fun read(workingDirectory: String): ThreadStartCatalog {
        val response = appServer.configRead(workingDirectory)
        val config = response["config"] as? JsonObject ?: JsonObject(emptyMap())
        return ThreadStartCatalog(
            workingDirectory = workingDirectory,
            defaultProviderId = config.text("model_provider"),
            defaultModel = config.text("model"),
            providers = config.providers(),
            models = models(),
            requirements = requirements(),
        )
    }

    private suspend fun models(): List<ModelOption> = try {
        buildList {
            var cursor: String? = null
            do {
                val page = appServer.modelList(cursor)
                (page["data"] as? JsonArray).orEmpty().forEach { value ->
                    val model = value as? JsonObject ?: return@forEach
                    val wireValue = model.text("model") ?: return@forEach
                    add(
                        ModelOption(
                            model = wireValue,
                            displayName = model.text("displayName") ?: wireValue,
                            reasoningEfforts = (model["supportedReasoningEfforts"] as? JsonArray)
                                .orEmpty()
                                .mapNotNull {
                                    (it as? JsonObject)?.text("reasoningEffort")
                                },
                        ),
                    )
                }
                cursor = page.text("nextCursor")
            } while (cursor != null)
        }.distinctBy(ModelOption::model)
    } catch (failure: JsonRpcRemoteException) {
        if (failure.code == -32601) emptyList() else throw failure
    }

    private suspend fun requirements(): PermissionRequirements = try {
        val requirements = appServer.configRequirementsRead()["requirements"] as? JsonObject
        PermissionRequirements(
            allowedSandboxModes = requirements.stringSet("allowedSandboxModes"),
            allowedApprovalPolicies = requirements.stringSet("allowedApprovalPolicies"),
        )
    } catch (failure: JsonRpcRemoteException) {
        if (failure.code == -32601) PermissionRequirements() else throw failure
    }
}

private fun JsonObject.providers(): List<ModelProviderOption> =
    (this["model_providers"] as? JsonObject).orEmpty().map { (id, value) ->
        ModelProviderOption(
            id = id,
            label = (value as? JsonObject)?.text("name") ?: id,
        )
    }.sortedBy(ModelProviderOption::label)

private fun JsonObject?.stringSet(name: String): Set<String>? =
    (this?.get(name) as? JsonArray)?.mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull
    }?.toSet()

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull
