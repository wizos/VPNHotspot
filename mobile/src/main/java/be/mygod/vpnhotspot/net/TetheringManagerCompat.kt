package be.mygod.vpnhotspot.net

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.TetheringManager
import android.os.Build
import android.os.DeadObjectException
import android.os.Handler
import android.os.Parcelable
import androidx.annotation.RequiresApi
import androidx.core.os.ExecutorCompat
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.root.RootManager
import be.mygod.vpnhotspot.root.StartTethering
import be.mygod.vpnhotspot.root.StopTethering
import be.mygod.vpnhotspot.root.StopTetheringLegacy
import be.mygod.vpnhotspot.util.ConstantLookup
import be.mygod.vpnhotspot.util.InPlaceExecutor
import be.mygod.vpnhotspot.util.Services
import be.mygod.vpnhotspot.util.UnblockCentral
import be.mygod.vpnhotspot.util.broadcastReceiver
import be.mygod.vpnhotspot.util.callSuper
import be.mygod.vpnhotspot.util.ensureReceiverUnregistered
import be.mygod.vpnhotspot.util.getRootCause
import be.mygod.vpnhotspot.util.matches
import be.mygod.vpnhotspot.util.matches1
import com.android.dx.stock.ProxyBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor

/**
 * Heavily based on:
 * https://github.com/aegis1980/WifiHotSpot
 * https://android.googlesource.com/platform/frameworks/base.git/+/android-7.0.0_r1/core/java/android/net/ConnectivityManager.java
 */
object TetheringManagerCompat {
    interface TetheringCallback {
        /**
         * ADDED: Called when a local Exception occurred.
         */
        fun onException(e: Exception) {
            when (e.getRootCause()) {
                is SecurityException, is CancellationException -> { }
                else -> Timber.w(e)
            }
        }
    }
    /**
     * Callback for use with [startTethering] to find out whether tethering succeeded.
     */
    interface StartTetheringCallback : TetheringCallback {
        /**
         * Called when tethering has been successfully started.
         */
        fun onTetheringStarted() { }

        /**
         * Called when starting tethering failed.
         *
         * @param error The error that caused the failure.
         */
        fun onTetheringFailed(error: Int? = null) { }

    }
    interface StopTetheringCallback : TetheringCallback {
        /**
         * Called when tethering has been successfully stopped.
         */
        fun onStopTetheringSucceeded() {}

        /**
         * Called when starting tethering failed.
         *
         * @param error The error that caused the failure.
         */
        @RequiresApi(30)
        fun onStopTetheringFailed(error: Int) {}
    }

    @RequiresApi(30)
    private const val TETHERING_CONNECTOR_CLASS = "android.net.ITetheringConnector"
    @RequiresApi(30)
    private const val IN_PROCESS_SUFFIX = ".InProcess"

    /**
     * This is a sticky broadcast since almost forever.
     *
     * https://android.googlesource.com/platform/frameworks/base.git/+/2a091d7aa0c174986387e5d56bf97a87fe075bdb%5E%21/services/java/com/android/server/connectivity/Tethering.java
     */
    const val ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
    private const val EXTRA_ACTIVE_LOCAL_ONLY_LEGACY = "localOnlyArray"
    /**
     * gives a String[] listing all the interfaces currently in local-only
     * mode (ie, has DHCPv4+IPv6-ULA support and no packet forwarding)
     */
    @RequiresApi(30)
    private const val EXTRA_ACTIVE_LOCAL_ONLY = "android.net.extra.ACTIVE_LOCAL_ONLY"
    /**
     * gives a String[] listing all the interfaces currently tethered
     * (ie, has DHCPv4 support and packets potentially forwarded/NATed)
     */
    private const val EXTRA_ACTIVE_TETHER = "tetherArray"
    /**
     * gives a String[] listing all the interfaces we tried to tether and
     * failed.  Use [getLastTetherError] to find the error code
     * for any interfaces listed here.
     */
    const val EXTRA_ERRORED_TETHER = "erroredArray"

    /** Tethering offload status is stopped.  */
    @RequiresApi(30)
    const val TETHER_HARDWARE_OFFLOAD_STOPPED = 0
    /** Tethering offload status is started.  */
    @RequiresApi(30)
    const val TETHER_HARDWARE_OFFLOAD_STARTED = 1
    /** Fail to start tethering offload.  */
    @RequiresApi(30)
    const val TETHER_HARDWARE_OFFLOAD_FAILED = 2

    // tethering types supported by enableTetheringInternal: https://android.googlesource.com/platform/frameworks/base/+/5d36f01/packages/Tethering/src/com/android/networkstack/tethering/Tethering.java#549
    /**
     * USB tethering type.
     *
     * Requires MANAGE_USB permission, unfortunately.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/7ca5d3a/services/usb/java/com/android/server/usb/UsbService.java#389
     * @see startTethering
     */
    const val TETHERING_USB = 1
    /**
     * Bluetooth tethering type.
     *
     * Requires BLUETOOTH permission.
     * @see startTethering
     */
    const val TETHERING_BLUETOOTH = 2
    /**
     * Ethernet tethering type.
     *
     * Requires MANAGE_USB permission, also.
     * @see startTethering
     */
    @RequiresApi(30)
    const val TETHERING_ETHERNET = 5

    @get:RequiresApi(30)
    val resolvedService get() = sequence {
        for (action in arrayOf(TETHERING_CONNECTOR_CLASS + IN_PROCESS_SUFFIX, TETHERING_CONNECTOR_CLASS)) {
            val result = app.packageManager.queryIntentServices(Intent(action), PackageManager.MATCH_SYSTEM_ONLY)
            check(result.size <= 1) { "Multiple system services handle $action: ${result.joinToString()}" }
            result.firstOrNull()?.let { yield(it) }
        }
    }.first()

    private val classOnStartTetheringCallback by lazy {
        Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
    }
    private val startTethering by lazy {
        ConnectivityManager::class.java.getDeclaredMethod("startTethering",
                Int::class.java, Boolean::class.java, classOnStartTetheringCallback, Handler::class.java)
    }
    private val stopTethering by lazy {
        ConnectivityManager::class.java.getDeclaredMethod("stopTethering", Int::class.java)
    }
    private val getLastTetherError by lazy @SuppressLint("SoonBlockedPrivateApi") {
        ConnectivityManager::class.java.getDeclaredMethod("getLastTetherError", String::class.java)
    }

//    @get:RequiresApi(30)
//    private val setStaticIpv4Addresses by lazy {
//        TetheringManager.TetheringRequest.Builder::class.java.getDeclaredMethod("setStaticIpv4Addresses",
//                LinkAddress::class.java, LinkAddress::class.java)
//    }
    @get:RequiresApi(30)
    private val setExemptFromEntitlementCheck by lazy @TargetApi(30) {
        TetheringManager.TetheringRequest.Builder::class.java.getDeclaredMethod("setExemptFromEntitlementCheck",
            Boolean::class.java)
    }
    @get:RequiresApi(30)
    private val setShouldShowEntitlementUi by lazy @TargetApi(30) {
        TetheringManager.TetheringRequest.Builder::class.java.getDeclaredMethod("setShouldShowEntitlementUi",
            Boolean::class.java)
    }

    @Deprecated("Legacy API")
    fun startTetheringLegacy(type: Int, showProvisioningUi: Boolean, callback: StartTetheringCallback,
                             handler: Handler? = null, cacheDir: File = app.deviceStorage.codeCacheDir) {
        val reference = WeakReference(callback)
        val proxy = ProxyBuilder.forClass(classOnStartTetheringCallback).apply {
            dexCache(cacheDir)
            handler { proxy, method, args ->
                @Suppress("NAME_SHADOWING") val callback = reference.get()
                if (args.isEmpty()) when (method.name) {
                    "onTetheringStarted" -> return@handler callback?.onTetheringStarted()
                    "onTetheringFailed" -> return@handler callback?.onTetheringFailed()
                }
                ProxyBuilder.callSuper(proxy, method, *args)
            }
        }.build()
        startTethering(Services.connectivity, type, showProvisioningUi, proxy, handler)
    }
    @RequiresApi(30)
    fun startTethering(type: Int, exemptFromEntitlementCheck: Boolean, showProvisioningUi: Boolean, executor: Executor,
                       callback: TetheringManager.StartTetheringCallback) {
        Services.tethering.startTethering(TetheringManager.TetheringRequest.Builder(type).also { builder ->
            // setting exemption requires TETHER_PRIVILEGED permission
            if (exemptFromEntitlementCheck) setExemptFromEntitlementCheck(builder, true)
            setShouldShowEntitlementUi(builder, showProvisioningUi)
        }.build(), executor, callback)
    }
    @RequiresApi(30)
    private fun proxy(callback: StartTetheringCallback): TetheringManager.StartTetheringCallback {
        val reference = WeakReference(callback)
        return object : TetheringManager.StartTetheringCallback {
            override fun onTetheringStarted() {
                reference.get()?.onTetheringStarted()
            }
            override fun onTetheringFailed(error: Int) {
                reference.get()?.onTetheringFailed(error)
            }
        }
    }
    /**
     * Runs tether provisioning for the given type if needed and then starts tethering if
     * the check succeeds. If no carrier provisioning is required for tethering, tethering is
     * enabled immediately. If provisioning fails, tethering will not be enabled. It also
     * schedules tether provisioning re-checks if appropriate.
     *
     * CHANGED BEHAVIOR: This method will not throw Exceptions, instead, callback.onException will be called.
     *
     * @param type The type of tethering to start. Must be one of
     *         {@link ConnectivityManager.TETHERING_WIFI},
     *         {@link ConnectivityManager.TETHERING_USB}, or
     *         {@link ConnectivityManager.TETHERING_BLUETOOTH}.
     * @param showProvisioningUi a boolean indicating to show the provisioning app UI if there
     *         is one. This should be true the first time this function is called and also any time
     *         the user can see this UI. It gives users information from their carrier about the
     *         check failing and how they can sign up for tethering if possible.
     * @param callback an {@link OnStartTetheringCallback} which will be called to notify the caller
     *         of the result of trying to tether.
     * @param handler {@link Handler} to specify the thread upon which the callback will be invoked
     * *@param localIPv4Address for API 30+ (not implemented for now due to blacklist). If present, it
     *         configures tethering with the preferred local IPv4 link address to use.
     * *@see setStaticIpv4Addresses
     */
    fun startTethering(type: Int, showProvisioningUi: Boolean, callback: StartTetheringCallback,
                       handler: Handler? = null, cacheDir: File = app.deviceStorage.codeCacheDir) {
        if (Build.VERSION.SDK_INT >= 30) try {
            val executor = if (handler == null) InPlaceExecutor else ExecutorCompat.create(handler)
            startTethering(type, true, showProvisioningUi,
                    executor, proxy(object : StartTetheringCallback {
                override fun onTetheringStarted() = callback.onTetheringStarted()
                override fun onTetheringFailed(error: Int?) {
                    if (error != TetheringManager.TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION) {
                        callback.onTetheringFailed(error)
                    } else GlobalScope.launch(Dispatchers.Unconfined) {
                        val result = try {
                            RootManager.use { it.execute(StartTethering(type, showProvisioningUi)) }
                        } catch (eRoot: Exception) {
                            try {   // last resort: start tethering without trying to bypass entitlement check
                                startTethering(type, false, showProvisioningUi, executor, proxy(callback))
                                if (eRoot !is CancellationException) Timber.w(eRoot)
                            } catch (e: Exception) {
                                e.addSuppressed(eRoot)
                                callback.onException(e)
                            }
                            return@launch
                        }
                        when {
                            result == null -> callback.onTetheringStarted()
                            result.value == TetheringManager.TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION -> try {
                                startTethering(type, false, showProvisioningUi, executor, proxy(callback))
                            } catch (e: Exception) {
                                callback.onException(e)
                            }
                            else -> callback.onTetheringFailed(result.value)
                        }
                    }
                }
                override fun onException(e: Exception) = callback.onException(e)
            }))
        } catch (e: Exception) {
            callback.onException(e)
        } else @Suppress("DEPRECATION") try {
            startTetheringLegacy(type, showProvisioningUi, callback, handler, cacheDir)
        } catch (e: InvocationTargetException) {
            if (e.targetException is SecurityException) GlobalScope.launch(Dispatchers.Unconfined) {
                val result = try {
                    val rootCache = File(cacheDir, "root")
                    rootCache.mkdirs()
                    check(rootCache.exists()) { "Creating root cache dir failed" }
                    RootManager.use {
                        it.execute(be.mygod.vpnhotspot.root.StartTetheringLegacy(
                                rootCache, type, showProvisioningUi))
                    }.value
                } catch (eRoot: Exception) {
                    eRoot.addSuppressed(e)
                    if (eRoot !is CancellationException) Timber.w(eRoot)
                    callback.onException(eRoot)
                    return@launch
                }
                if (result) callback.onTetheringStarted() else callback.onTetheringFailed()
            } else callback.onException(e)
        } catch (e: Exception) {
            callback.onException(e)
        }
    }

    private val stubIIntResultListener by lazy { Class.forName("android.net.IIntResultListener\$Stub") }
    @RequiresApi(30)
    fun stopTethering(type: Int, callback: StopTetheringCallback, context: Context,
                      cacheDir: File = app.deviceStorage.codeCacheDir) {
        val reference = WeakReference(callback)
        val contextRef = WeakReference(context)
        UnblockCentral.TetheringManager_getConnector(Services.tethering, Proxy.newProxyInstance(
            UnblockCentral.TetheringManager_ConnectorConsumer.classLoader,
            arrayOf(UnblockCentral.TetheringManager_ConnectorConsumer), object : InvocationHandler {
            override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?) = when {
                method.matches("onConnectorAvailable", UnblockCentral.ITetheringConnector) -> {
                    contextRef.get()?.let { context ->
                        val resultListener = ProxyBuilder.forClass(stubIIntResultListener).apply {
                            dexCache(cacheDir)
                            handler { proxy, method, args ->
                                @Suppress("NAME_SHADOWING") val callback = reference.get()
                                if (method.name == "onResult") (args[0] as Int).let { resultCode ->
                                    return@handler if (resultCode == TetheringManager.TETHER_ERROR_NO_ERROR) {
                                        callback?.onStopTetheringSucceeded()
                                    } else callback?.onStopTetheringFailed(resultCode)
                                }
                                ProxyBuilder.callSuper(proxy, method, *args)
                            }
                        }.build()
                        val (method, isNew) = UnblockCentral.ITetheringConnector_stopTethering
                        if (isNew) {
                            method(args!![0], type, context.opPackageName, context.attributionTag,
                                resultListener)
                        } else method(args!![0], type, context.opPackageName, resultListener)
                    }
                }
                else -> callSuper(UnblockCentral.TetheringManager_ConnectorConsumer::class.java, proxy, method, args)
            }
        }))
    }
    private fun stopTetheringRoot(type: Int, callback: StopTetheringCallback, cacheDir: File,
                                  suppressed: Exception? = null) = GlobalScope.launch(Dispatchers.Unconfined) {
        val result = try {
            RootManager.use { it.execute(StopTethering(cacheDir, type)) }
        } catch (eRoot: Exception) {
            stopTetheringLegacy(type, callback, if (eRoot is CancellationException) suppressed else eRoot.apply {
                if (suppressed != null) addSuppressed(suppressed)
            })
            return@launch
        }
        if (suppressed != null) Timber.w(suppressed)
        if (result == null) callback.onStopTetheringSucceeded() else {
            Timber.w(Exception("Unexpected stopTetheringRoot error ${result.value}, falling back"))
            stopTetheringLegacy(type, callback, suppressed)
        }
    }
    fun stopTethering(type: Int, callback: StopTetheringCallback, cacheDir: File = app.deviceStorage.codeCacheDir) {
        if (Build.VERSION.SDK_INT >= 30) try {
            stopTethering(type, object : StopTetheringCallback {
                override fun onStopTetheringSucceeded() = callback.onStopTetheringSucceeded()
                override fun onStopTetheringFailed(error: Int) {
                    if (error != TetheringManager.TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION) {
                        callback.onStopTetheringFailed(error)
                    } else stopTetheringRoot(type, callback, cacheDir)
                }
            }, app, cacheDir)
        } catch (e: Exception) {
            stopTetheringRoot(type, callback, cacheDir, e)
        } else stopTetheringLegacy(type, callback)
    }
    fun stopTetheringLegacy(type: Int) = stopTethering(Services.connectivity, type)
    fun stopTetheringLegacy(type: Int, callback: StopTetheringCallback, suppressed: Exception? = null) {
        try {
            stopTetheringLegacy(type)
            callback.onStopTetheringSucceeded()
            if (suppressed != null) Timber.w(suppressed)
        } catch (e: InvocationTargetException) {
            if (suppressed != null) e.addSuppressed(suppressed)
            if (e.targetException is SecurityException) GlobalScope.launch(Dispatchers.Unconfined) {
                try {
                    RootManager.use { it.execute(StopTetheringLegacy(type)) }
                    callback.onStopTetheringSucceeded()
                } catch (eRoot: Exception) {
                    eRoot.addSuppressed(e)
                    callback.onException(eRoot)
                }
            } else callback.onException(e)
        }
    }

    /**
     * Callback for use with [registerTetheringEventCallback] to find out tethering
     * upstream status.
     */
    interface TetheringEventCallback {
        /**
         * Called when tethering supported status changed.
         *
         * This will be called immediately after the callback is registered, and may be called
         * multiple times later upon changes.
         *
         * Tethering may be disabled via system properties, device configuration, or device
         * policy restrictions.
         *
         * @param supported The new supported status
         */
        fun onTetheringSupported(supported: Boolean) {}

        /**
         * Called when tethering supported status changed.
         *
         * This will be called immediately after the callback is registered, and may be called
         * multiple times later upon changes.
         *
         * Tethering may be disabled via system properties, device configuration, or device
         * policy restrictions.
         *
         * @param supportedTypes a set of @TetheringType which is supported.
         */
        @TargetApi(31)
        fun onSupportedTetheringTypes(supportedTypes: Set<Int?>) {
            val filtered = supportedTypes.filter { it !in 0..5 }
            if (filtered.isNotEmpty()) Timber.w(Exception(
                "Unexpected supported tethering types: ${filtered.joinToString()}"))
        }

        /**
         * Called when tethering upstream changed.
         *
         * This will be called immediately after the callback is registered, and may be called
         * multiple times later upon changes.
         *
         * @param network the [Network] of tethering upstream. Null means tethering doesn't
         * have any upstream.
         */
        fun onUpstreamChanged(network: Network?) {}

        /**
         * Called when there was a change in tethering interface regular expressions.
         *
         * This will be called immediately after the callback is registered, and may be called
         * multiple times later upon changes.
         *
         * CHANGED: This method will NOT be immediately called after registration.
         *
         * *@param reg The new regular expressions.
         * @hide
         */
        fun onTetherableInterfaceRegexpsChanged(reg: Any?) {}

        /**
         * Called when there was a change in the list of tetherable interfaces. Tetherable
         * interface means this interface is available and can be used for tethering.
         *
         * This will be called immediately after the callback is registered, and may be called
         * multiple times later upon changes.
         * @param interfaces The list of tetherable interface names.
         */
        fun onTetherableInterfacesChanged(interfaces: List<String?>) {}

        /**
         * Called when there was a change in the list of tethered interfaces.
         *
         * This will be called immediately after the callback is registered, and may be called
         * multiple times later upon changes.
         * @param interfaces The list of 0 or more String of currently tethered interface names.
         */
        fun onTetheredInterfacesChanged(interfaces: List<String?>) {}

        /**
         * Called when an error occurred configuring tethering.
         *
         * This will be called immediately after the callback is registered if the latest status
         * on the interface is an error, and may be called multiple times later upon changes.
         * @param ifName Name of the interface.
         * @param error One of `TetheringManager#TETHER_ERROR_*`.
         */
        fun onError(ifName: String, error: Int) {}

        /**
         * Called when the list of tethered clients changes.
         *
         * This callback provides best-effort information on connected clients based on state
         * known to the system, however the list cannot be completely accurate (and should not be
         * used for security purposes). For example, clients behind a bridge and using static IP
         * assignments are not visible to the tethering device; or even when using DHCP, such
         * clients may still be reported by this callback after disconnection as the system cannot
         * determine if they are still connected.
         *
         * Only called if having permission one of NETWORK_SETTINGS, MAINLINE_NETWORK_STACK, NETWORK_STACK.
         * @param clients The new set of tethered clients; the collection is not ordered.
         */
        fun onClientsChanged(clients: Collection<Parcelable>) {
            if (clients.isNotEmpty()) Timber.i("onClientsChanged: ${clients.joinToString()}")
        }

        /**
         * Called when tethering offload status changes.
         *
         * This will be called immediately after the callback is registered.
         * @param status The offload status.
         */
        fun onOffloadStatusChanged(status: Int) {}
    }

    private val callbackMap = mutableMapOf<TetheringEventCallback, TetheringManager.TetheringEventCallback>()
    /**
     * Start listening to tethering change events. Any new added callback will receive the last
     * tethering status right away. If callback is registered,
     * [TetheringEventCallback.onUpstreamChanged] will immediately be called. If tethering
     * has no upstream or disabled, the argument of callback will be null. The same callback object
     * cannot be registered twice.
     *
     * Requires TETHER_PRIVILEGED or ACCESS_NETWORK_STATE.
     *
     * @param executor the executor on which callback will be invoked.
     * @param callback the callback to be called when tethering has change events.
     */
    @RequiresApi(30)
    fun registerTetheringEventCallback(callback: TetheringEventCallback, executor: Executor? = null) {
        val reference = WeakReference(callback)
        val proxy = synchronized(callbackMap) {
            var computed = false
            callbackMap.computeIfAbsent(callback) {
                computed = true
                Proxy.newProxyInstance(TetheringManager.TetheringEventCallback::class.java.classLoader,
                        arrayOf(TetheringManager.TetheringEventCallback::class.java), object : InvocationHandler {
                    private var regexpsSent = false
                    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
                        @Suppress("NAME_SHADOWING")
                        val callback = reference.get()
                        return when {
                            method.matches("onTetheringSupported", Boolean::class.java) -> {
                                callback?.onTetheringSupported(args!![0] as Boolean)
                            }
                            method.matches1<java.util.Set<*>>("onSupportedTetheringTypes") -> {
                                @Suppress("UNCHECKED_CAST")
                                callback?.onSupportedTetheringTypes(args!![0] as Set<Int?>)
                            }
                            method.matches1<Network>("onUpstreamChanged") -> {
                                callback?.onUpstreamChanged(args!![0] as Network?)
                            }
                            method.name == "onTetherableInterfaceRegexpsChanged" &&
                                    method.parameters.singleOrNull()?.type?.name ==
                                    "android.net.TetheringManager\$TetheringInterfaceRegexps" -> {
                                if (regexpsSent) callback?.onTetherableInterfaceRegexpsChanged(args!!.single())
                                regexpsSent = true
                            }
                            method.matches1<java.util.List<*>>("onTetherableInterfacesChanged") -> {
                                @Suppress("UNCHECKED_CAST")
                                callback?.onTetherableInterfacesChanged(args!![0] as List<String?>)
                            }
                            method.matches1<java.util.List<*>>("onTetheredInterfacesChanged") -> {
                                @Suppress("UNCHECKED_CAST")
                                callback?.onTetheredInterfacesChanged(args!![0] as List<String?>)
                            }
                            method.matches("onError", String::class.java, Integer.TYPE) -> {
                                callback?.onError(args!![0] as String, args[1] as Int)
                            }
                            method.matches1<java.util.Collection<*>>("onClientsChanged") -> {
                                @Suppress("UNCHECKED_CAST")
                                callback?.onClientsChanged(args!![0] as Collection<Parcelable>)
                            }
                            method.matches("onOffloadStatusChanged", Integer.TYPE) -> {
                                callback?.onOffloadStatusChanged(args!![0] as Int)
                            }
                            else -> callSuper(TetheringManager.TetheringEventCallback::class.java, proxy, method, args)
                        }
                    }
                }) as TetheringManager.TetheringEventCallback
            }.also { if (!computed) return }
        }
        Services.tethering.registerTetheringEventCallback(executor ?: InPlaceExecutor, proxy)
    }
    /**
     * Remove tethering event callback previously registered with
     * [registerTetheringEventCallback].
     *
     * Requires TETHER_PRIVILEGED or ACCESS_NETWORK_STATE.
     *
     * @param callback previously registered callback.
     */
    @RequiresApi(30)
    fun unregisterTetheringEventCallback(callback: TetheringEventCallback) {
        val proxy = synchronized(callbackMap) { callbackMap.remove(callback) } ?: return
        try {
            Services.tethering.unregisterTetheringEventCallback(proxy)
        } catch (e: IllegalStateException) {
            if (e.cause !is DeadObjectException) throw e
        }
    }

    val callbackLegacyMap = mutableMapOf<TetheringEventCallback, BroadcastReceiver>()
    /**
     * [registerTetheringEventCallback] in a backwards compatible way.
     *
     * Only [TetheringEventCallback.onTetheredInterfacesChanged] is supported on API 29-.
     */
    fun registerTetheringEventCallbackCompat(context: Context, callback: TetheringEventCallback) {
        if (Build.VERSION.SDK_INT < 30) synchronized(callbackLegacyMap) {
            callbackLegacyMap.computeIfAbsent(callback) {
                broadcastReceiver { _, intent ->
                    callback.onTetheredInterfacesChanged(intent.tetheredIfaces ?: return@broadcastReceiver)
                }.also { context.registerReceiver(it, IntentFilter(ACTION_TETHER_STATE_CHANGED)) }
            }
        } else registerTetheringEventCallback(callback)
    }
    fun unregisterTetheringEventCallbackCompat(context: Context, callback: TetheringEventCallback) {
        if (Build.VERSION.SDK_INT < 30) {
            val receiver = synchronized(callbackLegacyMap) { callbackLegacyMap.remove(callback) } ?: return
            context.ensureReceiverUnregistered(receiver)
        } else unregisterTetheringEventCallback(callback)
    }

    /**
     * Get a more detailed error code after a Tethering or Untethering
     * request asynchronously failed.
     *
     * @param iface The name of the interface of interest
     * @return error The error code of the last error tethering or untethering the named
     *               interface
     */
    @Deprecated("Use {@link TetheringEventCallback#onError(String, int)} instead.")
    fun getLastTetherError(iface: String): Int = getLastTetherError(Services.connectivity, iface) as Int

    val tetherErrorLookup = ConstantLookup("TETHER_ERROR_",
            "TETHER_ERROR_NO_ERROR", "TETHER_ERROR_UNKNOWN_IFACE", "TETHER_ERROR_SERVICE_UNAVAIL",
            "TETHER_ERROR_UNSUPPORTED", "TETHER_ERROR_UNAVAIL_IFACE", "TETHER_ERROR_MASTER_ERROR",
            "TETHER_ERROR_TETHER_IFACE_ERROR", "TETHER_ERROR_UNTETHER_IFACE_ERROR", "TETHER_ERROR_ENABLE_NAT_ERROR",
            "TETHER_ERROR_DISABLE_NAT_ERROR", "TETHER_ERROR_IFACE_CFG_ERROR", "TETHER_ERROR_PROVISION_FAILED",
            "TETHER_ERROR_DHCPSERVER_ERROR", "TETHER_ERROR_ENTITLEMENT_UNKNOWN") @TargetApi(30) {
        TetheringManager::class.java
    }

    val Intent.tetheredIfaces get() = getStringArrayListExtra(EXTRA_ACTIVE_TETHER)
    val Intent.localOnlyTetheredIfaces get() = getStringArrayListExtra(
        if (Build.VERSION.SDK_INT >= 30) EXTRA_ACTIVE_LOCAL_ONLY else EXTRA_ACTIVE_LOCAL_ONLY_LEGACY)
}
