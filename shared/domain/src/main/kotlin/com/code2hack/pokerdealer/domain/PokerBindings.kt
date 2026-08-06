package com.code2hack.pokerdealer.domain

import kotlinx.serialization.Serializable

@Serializable
enum class PokerBindingDeviceKind {
    GLASSES,
    BLUETOOTH_HID,
}

/** Android's exact InputDevice descriptor; no friendly-name or scan-code identity is retained. */
@Serializable
data class PokerBindingDevice(
    val descriptor: String,
    val kind: PokerBindingDeviceKind,
) {
    init {
        require(descriptor.isNotBlank()) { "Binding device descriptor must not be blank" }
        if (kind == PokerBindingDeviceKind.GLASSES) {
            require(descriptor == GLASSES_DESCRIPTOR) { "Glasses use the canonical descriptor" }
        }
    }

    companion object {
        const val GLASSES_DESCRIPTOR = "glasses"
        val GLASSES = PokerBindingDevice(GLASSES_DESCRIPTOR, PokerBindingDeviceKind.GLASSES)

        fun remote(descriptor: String): PokerBindingDevice = PokerBindingDevice(
            descriptor = descriptor,
            kind = PokerBindingDeviceKind.BLUETOOTH_HID,
        )
    }
}

/** One atomic control. Remotes use descriptor + keyCode; glasses use one built-in gesture. */
@Serializable
data class PokerBindingControl(
    val device: PokerBindingDevice,
    val keyCode: Int? = null,
    val gesture: PokerGlassesGesture? = null,
) {
    init {
        when (device.kind) {
            PokerBindingDeviceKind.GLASSES -> {
                require(keyCode == null) { "Glasses controls must not contain a key code" }
                require(gesture != null) { "Glasses controls require a gesture" }
            }

            PokerBindingDeviceKind.BLUETOOTH_HID -> {
                require(keyCode != null && keyCode > 0) { "Remote controls require a positive key code" }
                require(gesture == null) { "Remote controls must not contain a glasses gesture" }
            }
        }
    }

    companion object {
        fun glasses(gesture: PokerGlassesGesture): PokerBindingControl = PokerBindingControl(
            device = PokerBindingDevice.GLASSES,
            gesture = gesture,
        )

        fun remote(descriptor: String, keyCode: Int): PokerBindingControl = PokerBindingControl(
            device = PokerBindingDevice.remote(descriptor),
            keyCode = keyCode,
        )
    }
}

@Serializable
data class PokerBindingEntry(
    val operation: PokerOperation,
    val controls: List<PokerBindingControl>,
) {
    init {
        require(controls.isNotEmpty()) { "A binding entry must contain a control" }
        require(controls.distinct().size == controls.size) { "Duplicate controls are not allowed" }
    }
}

/** One complete, revisioned map. Missing operations are valid for an incomplete remote. */
@Serializable
data class PokerBindingMap(
    val revision: Long,
    val entries: List<PokerBindingEntry>,
) {
    init {
        require(revision > 0) { "Binding revision must be positive" }
        require(entries.map(PokerBindingEntry::operation).distinct().size == entries.size) {
            "Each operation must have one binding entry"
        }
        val controls = entries.flatMap(PokerBindingEntry::controls)
        require(controls.distinct().size == controls.size) {
            "A physical control may map to only one operation"
        }
        require(
            controls
                .map(PokerBindingControl::device)
                .filter { it.kind == PokerBindingDeviceKind.BLUETOOTH_HID }
                .map(PokerBindingDevice::descriptor)
                .distinct()
                .size <= 1,
        ) { "Only one bonded Bluetooth HID remote is supported" }
    }

    val devices: Set<PokerBindingDevice>
        get() = entries.flatMap(PokerBindingEntry::controls).map(PokerBindingControl::device).toSet()

    fun controls(device: PokerBindingDevice, operation: PokerOperation): List<PokerBindingControl> =
        entries.firstOrNull { it.operation == operation }
            ?.controls
            ?.filter { it.device == device }
            .orEmpty()

    fun operationFor(control: PokerBindingControl): PokerOperation? =
        entries.firstOrNull { control in it.controls }?.operation

    fun isManagedRemote(descriptor: String): Boolean = entries.any { entry ->
        entry.controls.any {
            it.device.kind == PokerBindingDeviceKind.BLUETOOTH_HID &&
                it.device.descriptor == descriptor
        }
    }

    fun bind(
        device: PokerBindingDevice,
        operation: PokerOperation,
        control: PokerBindingControl,
    ): PokerBindingMap {
        require(control.device == device) { "Binding control belongs to another device" }
        val updated = entries.mapNotNull { entry ->
            val kept = entry.controls.filterNot {
                it == control || (entry.operation == operation && it.device == device)
            }
            kept.takeIf { it.isNotEmpty() }?.let { entry.copy(controls = it) }
        }.toMutableList()
        val target = updated.indexOfFirst { it.operation == operation }
        if (target >= 0) {
            updated[target] = updated[target].copy(controls = updated[target].controls + control)
        } else {
            updated += PokerBindingEntry(operation, listOf(control))
        }
        return changed(updated)
    }

    fun remove(device: PokerBindingDevice, operation: PokerOperation): PokerBindingMap {
        val updated = entries.mapNotNull { entry ->
            if (entry.operation != operation) {
                entry
            } else {
                entry.controls.filterNot { it.device == device }
                    .takeIf(List<PokerBindingControl>::isNotEmpty)
                    ?.let { entry.copy(controls = it) }
            }
        }
        return if (updated == entries) this else changed(updated)
    }

    fun clearRemote(descriptor: String): PokerBindingMap = clear { control ->
        control.device.kind == PokerBindingDeviceKind.BLUETOOTH_HID &&
            control.device.descriptor == descriptor
    }

    fun resetGlassesDefaults(): PokerBindingMap {
        val remote = entries.mapNotNull { entry ->
            entry.controls.filter { it.device.kind != PokerBindingDeviceKind.GLASSES }
                .takeIf(List<PokerBindingControl>::isNotEmpty)
                ?.let { entry.copy(controls = it) }
        }
        val defaults = defaultGlasses(revision + 1).entries
        val merged = mergeEntries(remote + defaults)
        return if (merged == entries) this else changed(merged)
    }

    private fun clear(predicate: (PokerBindingControl) -> Boolean): PokerBindingMap {
        val updated = entries.mapNotNull { entry ->
            entry.controls.filterNot(predicate)
                .takeIf(List<PokerBindingControl>::isNotEmpty)
                ?.let { entry.copy(controls = it) }
        }
        return if (updated == entries) this else changed(updated)
    }

    private fun changed(updated: List<PokerBindingEntry>): PokerBindingMap = PokerBindingMap(
        revision = revision + 1,
        entries = mergeEntries(updated),
    )

    companion object {
        fun defaultGlasses(revision: Long = 1): PokerBindingMap = PokerBindingMap(
            revision = revision,
            entries = PokerOperation.entries.map { operation ->
                PokerBindingEntry(operation, listOf(PokerBindingControl.glasses(defaultGesture(operation))))
            },
        )

        private fun defaultGesture(operation: PokerOperation): PokerGlassesGesture = when (operation) {
            PokerOperation.DOWN -> PokerGlassesGesture.SINGLE_FINGER_SWIPE_FORWARD
            PokerOperation.UP -> PokerGlassesGesture.SINGLE_FINGER_SWIPE_BACKWARD
            PokerOperation.RIGHT -> PokerGlassesGesture.DOUBLE_FINGER_SWIPE_FORWARD
            PokerOperation.LEFT -> PokerGlassesGesture.DOUBLE_FINGER_SWIPE_BACKWARD
            PokerOperation.FN -> PokerGlassesGesture.FUNCTION_BUTTON
            PokerOperation.TAP -> PokerGlassesGesture.SINGLE_FINGER_TAP
            PokerOperation.TAPTAP -> PokerGlassesGesture.DUAL_FINGER_TAP
        }

        private fun mergeEntries(entries: List<PokerBindingEntry>): List<PokerBindingEntry> =
            PokerOperation.entries.mapNotNull { operation ->
                entries.filter { it.operation == operation }
                    .flatMap(PokerBindingEntry::controls)
                    .distinct()
                    .takeIf(List<PokerBindingControl>::isNotEmpty)
                    ?.let { PokerBindingEntry(operation, it) }
            }
    }
}

enum class PokerBindingSyncStatus {
    UNSYNCHRONIZED,
    PENDING,
    SYNCHRONIZED,
}

data class PokerBindingLearningTarget(
    val device: PokerBindingDevice,
    val operation: PokerOperation,
)

data class PokerBindingState(
    val map: PokerBindingMap = PokerBindingMap.defaultGlasses(),
    val knownRemoteDescriptors: List<String> = emptyList(),
    val selectedDevice: PokerBindingDevice = PokerBindingDevice.GLASSES,
    val syncStatus: PokerBindingSyncStatus = PokerBindingSyncStatus.UNSYNCHRONIZED,
    val lastAcknowledgedRevision: Long? = null,
    val learning: PokerBindingLearningTarget? = null,
    val error: String? = null,
) {
    init {
        require(knownRemoteDescriptors.distinct().size <= 1) {
            "Only one bonded Bluetooth HID remote is supported"
        }
    }

    val devices: List<PokerBindingDevice>
        get() = listOf(PokerBindingDevice.GLASSES) + knownRemoteDescriptors.map(PokerBindingDevice::remote)
}

enum class PokerBindingCaptureResult {
    IGNORED,
    UNSUPPORTED,
    APPLIED,
}

@Serializable
enum class PokerBindingInstallResult {
    INSTALLED,
    DUPLICATE,
    STALE,
    CONFLICT,
    REJECTED,
}

/** Dealer-side editor and Poker-side learning state; the map swap is always whole-map. */
class PokerBindingController(
    initialMap: PokerBindingMap = PokerBindingMap.defaultGlasses(),
) {
    private var current = PokerBindingState(
        map = initialMap,
        knownRemoteDescriptors = initialMap.devices
            .filter { it.kind == PokerBindingDeviceKind.BLUETOOTH_HID }
            .map(PokerBindingDevice::descriptor)
            .sorted(),
    )

    @get:Synchronized
    val state: PokerBindingState get() = current

    @get:Synchronized
    val map: PokerBindingMap get() = current.map

    @get:Synchronized
    val learningTarget: PokerBindingLearningTarget? get() = current.learning

    /** Returns true only when a descriptor was first observed; no friendly name is inferred. */
    @Synchronized
    fun observeRemote(descriptor: String): Boolean {
        require(descriptor.isNotBlank()) { "Remote descriptor must not be blank" }
        if (descriptor in current.knownRemoteDescriptors) return false
        if (current.knownRemoteDescriptors.isNotEmpty()) {
            current = current.copy(error = "Only one Bluetooth HID remote is supported")
            return false
        }
        current = current.copy(
            knownRemoteDescriptors = listOf(descriptor),
            error = null,
        )
        return true
    }

    @Synchronized
    fun selectDevice(device: PokerBindingDevice) {
        require(device.kind == PokerBindingDeviceKind.GLASSES ||
            device.descriptor in current.knownRemoteDescriptors
        ) { "Unknown binding device" }
        current = current.copy(selectedDevice = device, error = null)
    }

    @Synchronized
    fun beginLearning(operation: PokerOperation): Boolean = beginLearning(current.selectedDevice, operation)

    @Synchronized
    fun beginLearning(device: PokerBindingDevice, operation: PokerOperation): Boolean {
        if (current.learning != null) {
            current = current.copy(error = "A binding is already being learned")
            return false
        }
        if (device.kind != PokerBindingDeviceKind.BLUETOOTH_HID) {
            current = current.copy(error = "Only Bluetooth HID controls can be learned")
            return false
        }
        if (device.descriptor !in current.knownRemoteDescriptors &&
            !observeRemote(device.descriptor)
        ) {
            return false
        }
        current = current.copy(
            selectedDevice = device,
            learning = PokerBindingLearningTarget(device, operation),
            error = null,
        )
        return true
    }

    @Synchronized
    fun capture(control: PokerBindingControl): PokerBindingCaptureResult {
        val target = current.learning ?: return PokerBindingCaptureResult.IGNORED
        if (target.device != control.device) return PokerBindingCaptureResult.IGNORED
        if (control.device.kind != PokerBindingDeviceKind.BLUETOOTH_HID || control.keyCode == null) {
            current = current.copy(error = "Cannot bind")
            return PokerBindingCaptureResult.UNSUPPORTED
        }
        current = current.copy(
            map = current.map.bind(target.device, target.operation, control),
            learning = null,
            syncStatus = PokerBindingSyncStatus.PENDING,
            error = null,
        )
        return PokerBindingCaptureResult.APPLIED
    }

    @Synchronized
    fun cancelLearning() {
        if (current.learning != null) current = current.copy(learning = null, error = null)
    }

    @Synchronized
    fun deviceDisconnected(descriptor: String) {
        if (current.learning?.device?.descriptor == descriptor) cancelLearning()
    }

    @Synchronized
    fun connectionLost() {
        if (current.learning != null || current.syncStatus != PokerBindingSyncStatus.UNSYNCHRONIZED) {
            current = current.copy(
                syncStatus = PokerBindingSyncStatus.UNSYNCHRONIZED,
                learning = null,
                error = null,
            )
        }
    }

    @Synchronized
    fun forgetRemote(descriptor: String) {
        val device = PokerBindingDevice.remote(descriptor)
        val cleared = current.map.clearRemote(descriptor)
        current = current.copy(
            map = cleared,
            knownRemoteDescriptors = current.knownRemoteDescriptors - descriptor,
            selectedDevice = current.selectedDevice.takeUnless { it == device } ?: PokerBindingDevice.GLASSES,
            learning = current.learning?.takeUnless { it.device == device },
            syncStatus = if (cleared != current.map) {
                PokerBindingSyncStatus.PENDING
            } else {
                current.syncStatus
            },
            error = null,
        )
    }

    @Synchronized
    fun restore(map: PokerBindingMap, knownRemoteDescriptors: List<String>) {
        val known = (
            knownRemoteDescriptors + map.devices
                .filter { it.kind == PokerBindingDeviceKind.BLUETOOTH_HID }
                .map(PokerBindingDevice::descriptor)
            ).filter(String::isNotBlank).distinct().sorted()
        require(known.size <= 1) { "Only one bonded Bluetooth HID remote is supported" }
        current = PokerBindingState(
            map = map,
            knownRemoteDescriptors = known,
            selectedDevice = current.selectedDevice.takeIf {
                it.kind == PokerBindingDeviceKind.GLASSES || it.descriptor in known
            } ?: PokerBindingDevice.GLASSES,
        )
    }

    @Synchronized
    fun remove(operation: PokerOperation) = updateMap(current.map.remove(current.selectedDevice, operation))

    @Synchronized
    fun clearSelectedRemote() {
        if (current.selectedDevice.kind != PokerBindingDeviceKind.BLUETOOTH_HID) return
        updateMap(current.map.clearRemote(current.selectedDevice.descriptor))
    }

    @Synchronized
    fun resetGlassesDefaults() = updateMap(current.map.resetGlassesDefaults())

    @Synchronized
    fun acknowledge(revision: Long): Boolean {
        if (revision != current.map.revision) return false
        current = current.copy(
            syncStatus = PokerBindingSyncStatus.SYNCHRONIZED,
            lastAcknowledgedRevision = revision,
            error = null,
        )
        return true
    }

    @Synchronized
    fun install(candidate: PokerBindingMap): PokerBindingInstallResult {
        val candidateRemoteDescriptors = candidate.devices
            .filter { it.kind == PokerBindingDeviceKind.BLUETOOTH_HID }
            .map(PokerBindingDevice::descriptor)
            .toSet()
        if (candidateRemoteDescriptors.size > 1 ||
            (current.knownRemoteDescriptors.isNotEmpty() &&
                candidateRemoteDescriptors.any { it !in current.knownRemoteDescriptors })
        ) {
            current = current.copy(
                syncStatus = PokerBindingSyncStatus.UNSYNCHRONIZED,
                error = "Binding snapshot used another Bluetooth HID remote",
            )
            return PokerBindingInstallResult.REJECTED
        }
        val result = when {
            candidate.revision < current.map.revision -> PokerBindingInstallResult.STALE
            candidate.revision == current.map.revision && candidate == current.map ->
                PokerBindingInstallResult.DUPLICATE
            candidate.revision == current.map.revision -> PokerBindingInstallResult.CONFLICT
            else -> PokerBindingInstallResult.INSTALLED
        }
        if (result == PokerBindingInstallResult.INSTALLED) {
            current = current.copy(
                map = candidate,
                knownRemoteDescriptors = (
                    current.knownRemoteDescriptors + candidateRemoteDescriptors
                ).distinct(),
                syncStatus = PokerBindingSyncStatus.SYNCHRONIZED,
                lastAcknowledgedRevision = candidate.revision,
                learning = null,
                error = null,
            )
        } else if (result == PokerBindingInstallResult.STALE || result == PokerBindingInstallResult.CONFLICT) {
            current = current.copy(
                syncStatus = PokerBindingSyncStatus.UNSYNCHRONIZED,
                error = "Binding snapshot was not installed",
            )
        }
        return result
    }

    /** Dealer is authoritative; a current-epoch snapshot may replace a local unacknowledged edit. */
    @Synchronized
    fun installAuthoritative(candidate: PokerBindingMap): PokerBindingInstallResult {
        val candidateRemoteDescriptors = candidate.devices
            .filter { it.kind == PokerBindingDeviceKind.BLUETOOTH_HID }
            .map(PokerBindingDevice::descriptor)
            .toSet()
        if (candidateRemoteDescriptors.size > 1 ||
            (current.knownRemoteDescriptors.isNotEmpty() &&
                candidateRemoteDescriptors.any { it !in current.knownRemoteDescriptors })
        ) {
            current = current.copy(
                syncStatus = PokerBindingSyncStatus.UNSYNCHRONIZED,
                error = "Binding snapshot used another Bluetooth HID remote",
            )
            return PokerBindingInstallResult.REJECTED
        }
        if (candidate == current.map) {
            current = current.copy(
                syncStatus = PokerBindingSyncStatus.SYNCHRONIZED,
                lastAcknowledgedRevision = candidate.revision,
                learning = null,
                error = null,
            )
            return PokerBindingInstallResult.DUPLICATE
        }
        current = current.copy(
            map = candidate,
            knownRemoteDescriptors = (current.knownRemoteDescriptors + candidateRemoteDescriptors).distinct(),
            syncStatus = PokerBindingSyncStatus.SYNCHRONIZED,
            lastAcknowledgedRevision = candidate.revision,
            learning = null,
            error = null,
        )
        return PokerBindingInstallResult.INSTALLED
    }

    private fun updateMap(updated: PokerBindingMap) {
        if (updated == current.map) return
        current = current.copy(
            map = updated,
            syncStatus = PokerBindingSyncStatus.PENDING,
            error = null,
        )
    }
}
