package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.CommandApprovalState
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.FileApprovalState
import com.code2hack.pokerdealer.domain.UserInputRequestState
import com.code2hack.pokerdealer.domain.PokerBindingMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
internal data class DealerProjectionSnapshot(
    val threads: List<DiscoveredThread> = emptyList(),
)

@Serializable
internal data class DealerPendingRequestSnapshot(
    val commandApprovals: CommandApprovalState = CommandApprovalState(),
    val fileApprovals: FileApprovalState = FileApprovalState(),
    val userInputRequests: UserInputRequestState = UserInputRequestState(),
)

@Serializable
internal data class DealerPokerBindingSnapshot(
    val map: PokerBindingMap = PokerBindingMap.defaultGlasses(),
    val knownRemoteDescriptors: List<String> = emptyList(),
)

internal data class RestoredDealerState(
    val projection: DealerProjectionSnapshot,
    val pendingRequests: DealerPendingRequestSnapshot,
    val pokerBindings: DealerPokerBindingSnapshot,
    val pokerBindingsWritable: Boolean,
    val pendingRequestsWritable: Boolean,
    val errors: List<String>,
)

internal class DealerStateRecoveryStore(
    private val root: File,
) {
    private val json = Json {
        allowStructuredMapKeys = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun read(): RestoredDealerState = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val projection = runCatching {
            read(projectionFile, DealerProjectionSnapshot.serializer())
        }.getOrElse { failure ->
            if (projectionFile.exists() && !projectionFile.delete()) {
                errors += "Unable to discard corrupt cached thread projection"
            }
            errors += "Discarded corrupt cached thread projection: ${failure.message}"
            DealerProjectionSnapshot()
        }
        var pendingRequestsWritable = true
        val pendingRequests = runCatching {
            read(pendingRequestsFile, DealerPendingRequestSnapshot.serializer())
        }.getOrElse { failure ->
            pendingRequestsWritable = false
            errors += "Unable to restore pending request uncertainty; stored data was preserved: ${failure.message}"
            DealerPendingRequestSnapshot()
        }
        var pokerBindingsWritable = true
        val pokerBindings = runCatching {
            read(pokerBindingsFile, DealerPokerBindingSnapshot.serializer())
        }.getOrElse { failure ->
            pokerBindingsWritable = false
            errors += "Unable to restore Poker bindings; stored data was preserved: ${failure.message}"
            DealerPokerBindingSnapshot()
        }
        RestoredDealerState(
            projection = projection,
            pendingRequests = pendingRequests,
            pokerBindings = pokerBindings,
            pokerBindingsWritable = pokerBindingsWritable,
            pendingRequestsWritable = pendingRequestsWritable,
            errors = errors,
        )
    }

    suspend fun writeProjection(snapshot: DealerProjectionSnapshot) = withContext(Dispatchers.IO) {
        write(projectionFile, DealerProjectionSnapshot.serializer(), snapshot)
    }

    suspend fun writePendingRequests(snapshot: DealerPendingRequestSnapshot) =
        withContext(Dispatchers.IO) {
            write(pendingRequestsFile, DealerPendingRequestSnapshot.serializer(), snapshot)
        }

    suspend fun writePokerBindings(snapshot: DealerPokerBindingSnapshot) =
        withContext(Dispatchers.IO) {
            write(pokerBindingsFile, DealerPokerBindingSnapshot.serializer(), snapshot)
        }

    private fun <T> read(file: File, serializer: KSerializer<T>): T {
        if (!file.exists()) return json.decodeFromString(serializer, "{}")
        return json.decodeFromString(serializer, file.readText())
    }

    private fun <T> write(file: File, serializer: KSerializer<T>, value: T) {
        require(root.isDirectory || root.mkdirs()) { "Unable to create Dealer recovery storage" }
        val temporary = File.createTempFile("${file.name}.", ".tmp", root)
        try {
            temporary.writeText(json.encodeToString(serializer, value))
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private val projectionFile: File
        get() = root.resolve("thread-projection-v1.json")

    private val pendingRequestsFile: File
        get() = root.resolve("pending-requests-v1.json")

    private val pokerBindingsFile: File
        get() = root.resolve("poker-bindings-v1.json")
}
