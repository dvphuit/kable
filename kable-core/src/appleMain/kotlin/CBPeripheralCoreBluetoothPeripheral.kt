package com.juul.kable

import com.benasher44.uuid.Uuid
import com.juul.kable.CentralManagerDelegate.ConnectionEvent
import com.juul.kable.CentralManagerDelegate.ConnectionEvent.DidConnect
import com.juul.kable.CentralManagerDelegate.ConnectionEvent.DidDisconnect
import com.juul.kable.CentralManagerDelegate.ConnectionEvent.DidFailToConnect
import com.juul.kable.Endianness.LittleEndian
import com.juul.kable.PeripheralDelegate.Response.DidDiscoverCharacteristicsForService
import com.juul.kable.PeripheralDelegate.Response.DidDiscoverDescriptorsForCharacteristic
import com.juul.kable.PeripheralDelegate.Response.DidDiscoverServices
import com.juul.kable.PeripheralDelegate.Response.DidReadRssi
import com.juul.kable.PeripheralDelegate.Response.DidUpdateNotificationStateForCharacteristic
import com.juul.kable.PeripheralDelegate.Response.DidUpdateValueForDescriptor
import com.juul.kable.PeripheralDelegate.Response.DidWriteValueForCharacteristic
import com.juul.kable.State.Disconnected.Status.Cancelled
import com.juul.kable.State.Disconnected.Status.ConnectionLimitReached
import com.juul.kable.State.Disconnected.Status.EncryptionTimedOut
import com.juul.kable.State.Disconnected.Status.Failed
import com.juul.kable.State.Disconnected.Status.PeripheralDisconnected
import com.juul.kable.State.Disconnected.Status.Timeout
import com.juul.kable.State.Disconnected.Status.Unknown
import com.juul.kable.State.Disconnected.Status.UnknownDevice
import com.juul.kable.WriteType.WithResponse
import com.juul.kable.WriteType.WithoutResponse
import com.juul.kable.logs.Logger
import com.juul.kable.logs.Logging
import com.juul.kable.logs.Logging.DataProcessor.Operation.Write
import com.juul.kable.logs.detail
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBDescriptor
import platform.CoreBluetooth.CBErrorConnectionFailed
import platform.CoreBluetooth.CBErrorConnectionLimitReached
import platform.CoreBluetooth.CBErrorConnectionTimeout
import platform.CoreBluetooth.CBErrorEncryptionTimedOut
import platform.CoreBluetooth.CBErrorOperationCancelled
import platform.CoreBluetooth.CBErrorPeripheralDisconnected
import platform.CoreBluetooth.CBErrorUnknownDevice
import platform.CoreBluetooth.CBManagerState
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.CoreBluetooth.CBUUIDCharacteristicExtendedPropertiesString
import platform.CoreBluetooth.CBUUIDClientCharacteristicConfigurationString
import platform.CoreBluetooth.CBUUIDL2CAPPSMCharacteristicString
import platform.CoreBluetooth.CBUUIDServerCharacteristicConfigurationString
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.dataUsingEncoding
import platform.darwin.UInt16
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

internal class CBPeripheralCoreBluetoothPeripheral(
    parentCoroutineContext: CoroutineContext,
    internal val cbPeripheral: CBPeripheral,
    observationExceptionHandler: ObservationExceptionHandler,
    private val onServicesDiscovered: ServicesDiscoveredAction,
    private val onMtuChanged: OnMtuChanged,
    private val logging: Logging,
) : CoreBluetoothPeripheral {

    private val logger = Logger(logging, identifier = cbPeripheral.identifier.UUIDString)

    private val centralManager: CentralManager = CentralManager.Default

    private val _state = MutableStateFlow<State>(State.Disconnected())
    override val state: StateFlow<State> = _state.asStateFlow()

    override val identifier: Uuid = cbPeripheral.identifier.toUuid()

    private val observers = Observers<NSData>(this, logging, exceptionHandler = observationExceptionHandler)


    /**
     * It's important that we instantiate this scope as late as possible, since [dispose] will be
     * called immediately if the parent job is already complete. Doing so late in <init> is fine,
     * but early in <init> it could reference non-nullable variables that are not yet set and crash.
     */
    private val scope = CoroutineScope(
        parentCoroutineContext +
            SupervisorJob(parentCoroutineContext.job).apply { invokeOnCompletion(::dispose) } +
            CoroutineName("Kable/Peripheral/${cbPeripheral.identifier.UUIDString}"),
    )

    init {
        centralManager.delegate
            .connectionState
            .filter { event -> event.identifier == cbPeripheral.identifier }
            .onEach { event ->
                logger.debug {
                    message = "CentralManagerDelegate state change"
                    detail("state", event.toString())
                }
            }
            .map { event -> event.toState() }
            .onEach { _state.value = it }
            .launchIn(scope)
    }

    internal val canSendWriteWithoutResponse = MutableStateFlow(cbPeripheral.canSendWriteWithoutResponse)

    private val _discoveredServices = atomic<List<DiscoveredService>?>(null)
    private val discoveredServices: List<DiscoveredService>
        get() = _discoveredServices.value
            ?: throw IllegalStateException("Services have not been discovered for $this")

    override val services: List<DiscoveredService>?
        get() = _discoveredServices.value?.toList()

    private val _connection = atomic<Connection?>(null)
    private val connection: Connection
        inline get() = _connection.value ?: throw NotReadyException(toString())

    override val name: String? get() = cbPeripheral.name

    private val connectAction = scope.sharedRepeatableAction(::establishConnection)

    override suspend fun connect() {
        connectAction.await()
    }

    private suspend fun establishConnection(scope: CoroutineScope) {
        // Check CBCentral State since connecting can result in an API misuse message.
        centralManager.checkBluetoothState(CBManagerStatePoweredOn)
        centralManager.delegate.state.watchForDisablingIn(scope)

        logger.info { message = "Connecting" }
        _state.value = State.Connecting.Bluetooth

        val failureWatcher = centralManager.delegate
            .connectionState
            .watchForConnectionFailureIn(scope)

        try {
            _connection.value = centralManager.connectPeripheral(
                scope,
                this@CBPeripheralCoreBluetoothPeripheral,
                observers.characteristicChanges,
                logging,
            )

            suspendUntilOrThrow<State.Connecting.Services>()
            discoverServices()
            onServicesDiscovered(ServicesDiscoveredPeripheral(this@CBPeripheralCoreBluetoothPeripheral))

            _state.value = State.Connecting.Observes
            logger.verbose { message = "Configuring characteristic observations" }
            observers.onConnected()
        } catch (e: Exception) {
            closeConnection()
            val failure = e.unwrapCancellationCause()
            logger.error(failure) { message = "Failed to connect" }
            throw failure
        } finally {
            failureWatcher.cancel()
        }

        logger.info { message = "Connected" }
        _state.value = State.Connected

        centralManager.delegate.onDisconnected.watchForConnectionLossIn(scope)
    }

    private fun Flow<CBManagerState>.watchForDisablingIn(scope: CoroutineScope) =
        scope.launch(start = UNDISPATCHED) {
            filter { state -> state != CBManagerStatePoweredOn }
                .collect { state ->
                    logger.info {
                        message = "Bluetooth unavailable"
                        detail("state", state)
                    }
                    closeConnection()
                    throw ConnectionLostException("$this $state")
                }
        }

    private fun Flow<NSUUID>.watchForConnectionLossIn(scope: CoroutineScope) =
        filter { identifier -> identifier == cbPeripheral.identifier }
            .onEach {
                logger.info { message = "Disconnected" }
                throw ConnectionLostException("$this disconnected")
            }
            .launchIn(scope)

    private fun Flow<ConnectionEvent>.watchForConnectionFailureIn(scope: CoroutineScope) =
        filter { identifier -> identifier == cbPeripheral.identifier }
            .filterNot { event -> event is DidConnect }
            .onEach { event ->
                val error = when (event) {
                    is DidFailToConnect -> event.error
                    is DidDisconnect -> event.error
                    else -> null
                }
                val failure = error
                    ?.let { ": ${it.toStatus()} (${it.localizedDescription})" }
                    .orEmpty()
                logger.info { message = "Disconnected$failure" }
                throw ConnectionLostException("$this disconnected$failure")
            }
            .launchIn(scope)

    override suspend fun disconnect() {
        closeConnection()
        suspendUntil<State.Disconnected>()
        logger.info { message = "Disconnected" }
    }

    private suspend fun closeConnection() {
        withContext(NonCancellable) {
            centralManager.cancelPeripheralConnection(cbPeripheral)
        }
    }

    private fun dispose(cause: Throwable?) {
        GlobalScope.launch(start = UNDISPATCHED) {
            closeConnection()
            setDisconnected()
            logger.info(cause) { message = "$this disposed" }
        }
    }

    private fun setDisconnected() {
        // Avoid trampling existing `Disconnected` state (and its properties) by only updating if not already `Disconnected`.
        _state.update { previous -> previous as? State.Disconnected ?: State.Disconnected() }
    }

    @Throws(CancellationException::class, IOException::class, NotReadyException::class)
    override suspend fun rssi(): Int = connection.execute<DidReadRssi> {
        centralManager.readRssi(cbPeripheral)
    }.rssi.intValue

    private suspend fun discoverServices(): Unit = discoverServices(services = null)

    /** @param services to discover (list of service UUIDs), or `null` for all. */
    private suspend fun discoverServices(
        services: List<Uuid>?,
    ) {
        logger.verbose { message = "discoverServices" }
        val servicesToDiscover = services?.map { CBUUID.UUIDWithNSUUID(it.toNSUUID()) }

        connection.execute<DidDiscoverServices> {
            centralManager.discoverServices(cbPeripheral, servicesToDiscover)
        }.also {
            onMtuChanged.invoke(it.mtu)
        }

        // Cast should be safe since `CBPeripheral.services` type is `[CBService]?`, according to:
        // https://developer.apple.com/documentation/corebluetooth/cbperipheral/services
        @Suppress("UNCHECKED_CAST")
        val discoveredServices = cbPeripheral.services as List<CBService>?
        discoveredServices?.forEach { cbService ->
            connection.execute<DidDiscoverCharacteristicsForService> {
                centralManager.discoverCharacteristics(cbPeripheral, cbService)
            }
            // Cast should be safe since `CBService.characteristics` type is `[CBCharacteristic]?`,
            // according to: https://developer.apple.com/documentation/corebluetooth/cbservice/characteristics
            @Suppress("UNCHECKED_CAST")
            val discoveredCharacteristics = cbService.characteristics as List<CBCharacteristic>?
            discoveredCharacteristics?.forEach { cbCharacteristic ->
                connection.execute<DidDiscoverDescriptorsForCharacteristic> {
                    centralManager.discoverDescriptors(cbPeripheral, cbCharacteristic)
                }
            }
        }

        _discoveredServices.value = cbPeripheral.services
            .orEmpty()
            .map { it as PlatformService }
            .map(::DiscoveredService)
    }

    @Throws(CancellationException::class, IOException::class, NotReadyException::class)
    override suspend fun write(
        characteristic: Characteristic,
        data: ByteArray,
        writeType: WriteType,
    ): Unit = write(characteristic, data.toNSData(), writeType)

    @Throws(CancellationException::class, IOException::class, NotReadyException::class)
    override suspend fun write(
        characteristic: Characteristic,
        data: NSData,
        writeType: WriteType,
    ) {
        logger.debug {
            message = "write"
            detail(characteristic)
            detail(writeType)
            detail(data, Write)
        }

        val platformCharacteristic = discoveredServices.obtain(characteristic, writeType.properties)
        when (writeType) {
            WithResponse -> connection.execute<DidWriteValueForCharacteristic> {
                centralManager.write(cbPeripheral, data, platformCharacteristic, CBCharacteristicWriteWithResponse)
            }
            WithoutResponse -> connection.guard.withLock {
                if (!canSendWriteWithoutResponse.updateAndGet { cbPeripheral.canSendWriteWithoutResponse }) {
                    canSendWriteWithoutResponse.first { it }
                }
                centralManager.write(cbPeripheral, data, platformCharacteristic, CBCharacteristicWriteWithoutResponse)
            }
        }
    }

    @Throws(CancellationException::class, IOException::class, NotReadyException::class)
    override suspend fun read(
        characteristic: Characteristic,
    ): ByteArray = readAsNSData(characteristic).toByteArray()

    @Throws(CancellationException::class, IOException::class, NotReadyException::class)
    override suspend fun readAsNSData(
        characteristic: Characteristic,
    ): NSData {
        logger.debug {
            message = "read"
            detail(characteristic)
        }

        val platformCharacteristic = discoveredServices.obtain(characteristic, Read)

        val event = connection.guard.withLock {
            observers
                .characteristicChanges
                .onSubscription { centralManager.read(cbPeripheral, platformCharacteristic) }
                .first { event -> event.isAssociatedWith(characteristic) }
        }

        return when (event) {
            is ObservationEvent.CharacteristicChange -> event.data
            is ObservationEvent.Error -> throw IOException(cause = event.cause)
            ObservationEvent.Disconnected -> throw ConnectionLostException()
        }
    }

    @Throws(CancellationException::class, IOException::class, NotReadyException::class)
    override suspend fun write(
        descriptor: Descriptor,
        data: ByteArray,
    ): Unit = write(descriptor, data.toNSData())

    @Throws(CancellationException::class, IOException::class)
    override suspend fun write(
        descriptor: Descriptor,
        data: NSData,
    ) {
        logger.debug {
            message = "write"
            detail(descriptor)
            detail(data, Write)
        }

        val platformDescriptor = discoveredServices.obtain(descriptor)
        connection.execute<DidUpdateValueForDescriptor> {
            centralManager.write(cbPeripheral, data, platformDescriptor)
        }
    }

    @Throws(CancellationException::class, IOException::class, NotReadyException::class)
    override suspend fun read(
        descriptor: Descriptor,
    ): ByteArray = readAsNSData(descriptor).toByteArray()

    @Throws(CancellationException::class, IOException::class, NotReadyException::class)
    override suspend fun readAsNSData(
        descriptor: Descriptor,
    ): NSData {
        logger.debug {
            message = "read"
            detail(descriptor)
        }

        val platformDescriptor = discoveredServices.obtain(descriptor)
        val updatedDescriptor = connection.execute<DidUpdateValueForDescriptor> {
            centralManager.read(cbPeripheral, platformDescriptor)
        }.descriptor

        return when (val value = updatedDescriptor.value) {
            is NSData -> value

            is NSString -> value.dataUsingEncoding(NSUTF8StringEncoding)
                ?: byteArrayOf().toNSData().also {
                    logger.warn {
                        message = "Failed to decode descriptor"
                        detail(descriptor)
                        detail("type", "NSString")
                    }
                }

            is NSNumber -> when (updatedDescriptor.isUnsignedShortValue) {
                true -> value.unsignedShortValue.toByteArray(LittleEndian)
                false -> value.unsignedLongValue.toByteArray(LittleEndian)
            }.toNSData()

            // This case handles if CBUUIDL2CAPPSMCharacteristicString is `UInt16`, as it is unclear
            // in the Core Bluetooth documentation. See https://github.com/JuulLabs/kable/pull/706#discussion_r1680615969
            // for related discussion.
            is UInt16 -> value.toByteArray(LittleEndian).toNSData()

            else -> byteArrayOf().toNSData().also {
                logger.warn {
                    message = "Unknown descriptor type"
                    detail(descriptor)
                    value.type?.let { detail("type", it) }
                }
            }
        }
    }

    override fun observe(
        characteristic: Characteristic,
        onSubscription: OnSubscriptionAction,
    ): Flow<ByteArray> = observeAsNSData(characteristic, onSubscription).map(NSData::toByteArray)

    override fun observeAsNSData(
        characteristic: Characteristic,
        onSubscription: OnSubscriptionAction,
    ): Flow<NSData> = observers.acquire(characteristic, onSubscription)


    internal suspend fun startNotifications(characteristic: Characteristic) {
        logger.debug {
            message = "CentralManager.notify"
            detail(characteristic)
        }

        val platformCharacteristic = discoveredServices.obtain(characteristic, Notify or Indicate)
        connection.execute<DidUpdateNotificationStateForCharacteristic> {
            centralManager.notify(cbPeripheral, platformCharacteristic)
        }
    }

    internal suspend fun stopNotifications(characteristic: Characteristic) {
        logger.debug {
            message = "CentralManager.cancelNotify"
            detail(characteristic)
        }

        val platformCharacteristic = discoveredServices.obtain(characteristic, Notify or Indicate)
        connection.execute<DidUpdateNotificationStateForCharacteristic> {
            centralManager.cancelNotify(cbPeripheral, platformCharacteristic)
        }
    }

    override fun toString(): String = "Peripheral(cbPeripheral=$cbPeripheral)"
}

private fun ConnectionEvent.toState(): State = when (this) {
    is DidConnect -> State.Connecting.Services
    is DidFailToConnect -> State.Disconnected(error?.toStatus())
    is DidDisconnect -> State.Disconnected(error?.toStatus())
    else -> State.Disconnecting
}

private fun NSError.toStatus(): State.Disconnected.Status = when (code) {
    CBErrorPeripheralDisconnected -> PeripheralDisconnected
    CBErrorConnectionFailed -> Failed
    CBErrorConnectionTimeout -> Timeout
    CBErrorUnknownDevice -> UnknownDevice
    CBErrorOperationCancelled -> Cancelled
    CBErrorConnectionLimitReached -> ConnectionLimitReached
    CBErrorEncryptionTimedOut -> EncryptionTimedOut
    else -> Unknown(code.toInt())
}

private fun CentralManager.checkBluetoothState(expected: CBManagerState) {
    val actual = delegate.state.value
    if (expected != actual) {
        fun nameFor(value: Number) = when (value) {
            CBManagerStatePoweredOff -> "PoweredOff"
            CBManagerStatePoweredOn -> "PoweredOn"
            CBManagerStateResetting -> "Resetting"
            CBManagerStateUnauthorized -> "Unauthorized"
            CBManagerStateUnknown -> "Unknown"
            CBManagerStateUnsupported -> "Unsupported"
            else -> "Unknown"
        }
        val actualName = nameFor(actual)
        val expectedName = nameFor(expected)
        throw BluetoothDisabledException("Bluetooth state is $actualName ($actual), but $expectedName ($expected) was required.")
    }
}

private val CBDescriptor.isUnsignedShortValue: Boolean
    get() = UUID.UUIDString.let {
        it == CBUUIDCharacteristicExtendedPropertiesString ||
            it == CBUUIDClientCharacteristicConfigurationString ||
            it == CBUUIDServerCharacteristicConfigurationString ||
            it == CBUUIDL2CAPPSMCharacteristicString
    }

private val Any?.type: String?
    get() = this?.let { it::class.simpleName }
