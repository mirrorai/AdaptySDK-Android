package com.adapty

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.adapty.api.*
import com.adapty.api.entity.DataState
import com.adapty.api.entity.paywalls.*
import com.adapty.api.entity.profile.update.ProfileParameterBuilder
import com.adapty.api.entity.purchaserInfo.*
import com.adapty.api.entity.purchaserInfo.model.PurchaserInfoModel
import com.adapty.api.entity.validate.GoogleValidationResult
import com.adapty.api.responses.*
import com.adapty.purchase.InAppPurchases
import com.adapty.purchase.InAppPurchasesInfo
import com.adapty.utils.*
import com.android.billingclient.api.Purchase
import com.google.gson.Gson
import java.lang.ref.WeakReference
import kotlin.collections.ArrayList

class Adapty {

    companion object {
        lateinit var context: Context
        private lateinit var preferenceManager: PreferenceManager
        private var onPurchaserInfoUpdatedListener: OnPurchaserInfoUpdatedListener? = null
        private var onPromoReceivedListener: OnPromoReceivedListener? = null
        private var requestQueue: ArrayList<() -> Unit> = arrayListOf()
        private var isActivated = false
        private val kinesisManager by lazy {
            KinesisManager(preferenceManager)
        }
        private val liveTracker by lazy {
            AdaptyLiveTracker(kinesisManager)
        }
        private val gson = Gson()
        private val apiClientRepository by lazy {
            ApiClientRepository(preferenceManager, gson)
        }

        @JvmStatic
        fun activate(
            context: Context,
            appKey: String
        ) =
            activate(context, appKey, null, null)

        @JvmStatic
        fun activate(
            context: Context,
            appKey: String,
            customerUserId: String?
        ) {
            activate(context, appKey, customerUserId, null)
        }

        private fun activate(
            context: Context,
            appKey: String,
            customerUserId: String?,
            adaptyCallback: ((AdaptyError?) -> Unit)?
        ) {
            LogHelper.logVerbose("activate($appKey, ${customerUserId ?: ""})")
            if (isActivated)
                return

            require(!appKey.isBlank()) { "Public SDK key must not be empty." }
            require(context.applicationContext is Application) { "Application context must be provided." }
            require(context.checkCallingOrSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED) { "INTERNET permission must be granted." }

            isActivated = true

            this.context = context.applicationContext
            this.preferenceManager = PreferenceManager(this.context)
            this.preferenceManager.appKey = appKey
            (this.context as Application).registerActivityLifecycleCallbacks(liveTracker)

            addToQueue {
                activateInQueue(customerUserId, adaptyCallback)
            }
        }

        private fun activateInQueue(
            customerUserId: String?,
            adaptyCallback: ((AdaptyError?) -> Unit)?
        ) {
            if (preferenceManager.profileID.isEmpty()) {
                apiClientRepository.createProfile(customerUserId, object : AdaptySystemCallback {
                    override fun success(response: Any?, reqID: Int) {
                        if (response is CreateProfileResponse) {
                            response.data?.attributes?.apply {
                                profileId?.let {
                                    preferenceManager.profileID = it
                                }
                                this.customerUserId?.let {
                                    preferenceManager.customerUserID = it
                                }

                                checkChangesPurchaserInfo(this)
                            }
                        }

                        adaptyCallback?.invoke(null)

                        nextQueue()

                        getStartedPaywalls()

                        getPromoOnStart()

                        sendSyncMetaInstallRequest()

                        syncPurchasesBody(false, null)

                    }

                    override fun fail(error: AdaptyError, reqID: Int) {
                        adaptyCallback?.invoke(error)
                        nextQueue()
                    }
                })
            } else {
                makeStartRequests(adaptyCallback)
            }
        }

        private fun nextQueue() {
            if (requestQueue.isNotEmpty())
                requestQueue.removeAt(0)

            if (requestQueue.isNotEmpty())
                requestQueue.first().invoke()
        }

        private fun addToQueue(action: () -> Unit) {
            requestQueue.add(action)

            if (requestQueue.size == 1)
                requestQueue[0].invoke()
        }

        private fun makeStartRequests(adaptyCallback: ((error: AdaptyError?) -> Unit)?) {
            sendSyncMetaInstallRequest()

            getStartedPaywalls()

            getPromoOnStart()

            syncPurchasesBody(false) { _ ->
                var isCallbackSent = false
                getPurchaserInfo(false) { info, state, error ->
                    if (!isCallbackSent) {
                        isCallbackSent = true
                        adaptyCallback?.invoke(error)
                        nextQueue()
                        return@getPurchaserInfo
                    }

                    info?.takeIf(::isPurchaserInfoChanged)?.let {
                        onPurchaserInfoUpdatedListener?.onPurchaserInfoReceived(it)
                    }
                }
            }
        }

        private fun checkChangesPurchaserInfo(res: AttributePurchaserInfoRes)
                = checkChangesPurchaserInfo(generatePurchaserInfoModel(res))

        private fun checkChangesPurchaserInfo(purchaserInfo: PurchaserInfoModel) {
            if (isPurchaserInfoChanged(purchaserInfo)) {
                onPurchaserInfoUpdatedListener?.onPurchaserInfoReceived(purchaserInfo)
            }
            preferenceManager.purchaserInfo = purchaserInfo
        }

        private fun isPurchaserInfoChanged(info: PurchaserInfoModel)
                = preferenceManager.purchaserInfo != info

        private fun sendSyncMetaInstallRequest() {
            LogHelper.logVerbose("sendSyncMetaInstallRequest()")
            apiClientRepository.syncMetaInstall(object : AdaptySystemCallback {
                override fun success(response: Any?, reqID: Int) {
                    if (response is SyncMetaInstallResponse) {
                        response.data?.attributes?.let { attrs ->
                            attrs.iamAccessKeyId?.let {
                                preferenceManager.iamAccessKeyId = it
                            }
                            attrs.iamSecretKey?.let {
                                preferenceManager.iamSecretKey = it
                            }
                            attrs.iamSessionToken?.let {
                                preferenceManager.iamSessionToken = it
                            }
                            attrs.profileId?.let {
                                if (it != preferenceManager.profileID) {
                                    preferenceManager.profileID = it
                                }
                            }
                        }

                        liveTracker.start()
                    }
                }

                override fun fail(error: AdaptyError, reqID: Int) {
                }

            })
        }

        @JvmStatic
        fun identify(customerUserId: String, adaptyCallback: (error: AdaptyError?) -> Unit) {
            LogHelper.logVerbose("identify()")
            addToQueue { identifyInQueue(customerUserId, adaptyCallback) }
        }

        private fun identifyInQueue(
            customerUserId: String,
            adaptyCallback: (error: AdaptyError?) -> Unit
        ) {
            if (customerUserId.isBlank()) {
                LogHelper.logError("customerUserId should not be empty")
                adaptyCallback.invoke(AdaptyError(message = "customerUserId should not be empty", adaptyErrorCode = AdaptyErrorCode.EMPTY_PARAMETER))
                nextQueue()
                return
            } else if (customerUserId == preferenceManager.customerUserID) {
                adaptyCallback.invoke(null)
                nextQueue()
                return
            }

            apiClientRepository.createProfile(customerUserId, object : AdaptySystemCallback {
                override fun success(response: Any?, reqID: Int) {
                    var profileIdChanged = false
                    if (response is CreateProfileResponse) {
                        response.data?.attributes?.apply {
                            profileId?.let {
                                profileIdChanged = it != preferenceManager.profileID
                                preferenceManager.profileID = it
                            }
                            this.customerUserId?.let {
                                preferenceManager.customerUserID = it
                            }

                            checkChangesPurchaserInfo(this)
                        }
                    }

                    adaptyCallback.invoke(null)

                    nextQueue()

                    if (!profileIdChanged) return

                    preferenceManager.products = arrayListOf()
                    preferenceManager.containers = null

                    getStartedPaywalls()

                    sendSyncMetaInstallRequest()

                    syncPurchasesBody(false, null)
                }

                override fun fail(error: AdaptyError, reqID: Int) {
                    adaptyCallback.invoke(error)

                    nextQueue()
                }

            })
        }

        @JvmStatic
        fun updateProfile(params: ProfileParameterBuilder, adaptyCallback: (error: AdaptyError?) -> Unit) {
            addToQueue {
                apiClientRepository.updateProfile(
                    params.email,
                    params.phoneNumber,
                    params.facebookUserId,
                    params.mixpanelUserId,
                    params.amplitudeUserId,
                    params.amplitudeDeviceId,
                    params.appmetricaProfileId,
                    params.appmetricaDeviceId,
                    params.firstName,
                    params.lastName,
                    params.gender,
                    params.birthday,
                    params.customAttributes,
                    object : AdaptyProfileCallback {
                        override fun onResult(response: UpdateProfileResponse?, error: AdaptyError?) {
                            response?.data?.attributes?.profileId?.let {
                                if (it != preferenceManager.profileID) {
                                    preferenceManager.profileID = it
                                }
                            }
                            adaptyCallback.invoke(error)
                            nextQueue()
                        }

                    }
                )
            }
        }

        private fun getPurchaserInfo(
            needQueue: Boolean,
            adaptyCallback: (purchaserInfo: PurchaserInfoModel?, state: DataState, error: AdaptyError?) -> Unit
        ) {
            val info = preferenceManager.purchaserInfo
            info?.let {
                adaptyCallback.invoke(it, DataState.CACHED, null)
            }

            apiClientRepository.getProfile(
                object : AdaptyPurchaserInfoCallback {
                    override fun onResult(response: AttributePurchaserInfoRes?, error: AdaptyError?) {
                        response?.let {
                            val purchaserInfo = generatePurchaserInfoModel(it)
                            adaptyCallback.invoke(purchaserInfo, DataState.SYNCED, error)
                            preferenceManager.purchaserInfo = purchaserInfo
                        } ?: kotlin.run {
                            adaptyCallback.invoke(null, DataState.SYNCED, error)
                        }

                        if (needQueue) {
                            nextQueue()
                        }
                    }
                }
            )
        }

        @JvmStatic
        fun getPurchaserInfo(
            adaptyCallback: (purchaserInfo: PurchaserInfoModel?, state: DataState, error: AdaptyError?) -> Unit
        ) {
            addToQueue { getPurchaserInfo(true, adaptyCallback) }
        }

        private fun getStartedPaywalls() {
            getPaywallsInQueue(
                false
            ) { containers, products, state, error -> }
        }

        private fun getPromoOnStart() {
            getPromoInQueue(false) { promo, error ->
                if (error != null || promo == null)
                    return@getPromoInQueue
                onPromoReceivedListener?.onPromoReceived(promo)
            }
        }

        @JvmStatic
        fun getPaywalls(
            adaptyCallback: (paywalls: List<PaywallModel>, products: ArrayList<ProductModel>, state: DataState, error: AdaptyError?) -> Unit
        ) {
            LogHelper.logVerbose("getPaywalls()")
            addToQueue {
                getPaywallsInQueue(true, adaptyCallback)
            }
        }

        private fun getPaywallsInQueue(
            needQueue: Boolean,
            adaptyCallback: (paywalls: List<PaywallModel>, products: ArrayList<ProductModel>, state: DataState, error: AdaptyError?) -> Unit
        ) {
            val cntrs = preferenceManager.containers
            cntrs?.toPaywalls()?.let {
                adaptyCallback.invoke(it, preferenceManager.products, DataState.CACHED, null)
            }

            apiClientRepository.getPaywalls(
                object : AdaptyPaywallsCallback {
                    override fun onResult(
                        containers: ArrayList<DataContainer>,
                        products: ArrayList<ProductModel>,
                        error: AdaptyError?
                    ) {

                        if (error != null) {
                            adaptyCallback.invoke(arrayListOf(), arrayListOf(), DataState.SYNCED, error)
                            if (needQueue)
                                nextQueue()
                            return
                        }

                        val data: ArrayList<Any> =
                            containers.filterTo(arrayListOf()) { !it.attributes?.products.isNullOrEmpty() }

                        if (data.isEmpty() && products.isEmpty()) {
                            preferenceManager.apply {
                                this.containers = containers
                                this.products = products
                            }
                            adaptyCallback.invoke(containers.toPaywalls(), products, DataState.SYNCED, error)
                            if (needQueue)
                                nextQueue()
                            return
                        }

                        if (products.isNotEmpty())
                            data.add(products)

                        InAppPurchasesInfo(
                            context,
                            data,
                            object : AdaptyPaywallsInfoCallback {
                                override fun onResult(data: ArrayList<Any>, error: AdaptyError?) {
                                    if (error != null) {
                                        adaptyCallback.invoke(containers.toPaywalls(), products, DataState.SYNCED, error)
                                        if (needQueue)
                                            nextQueue()
                                        return
                                    }

                                    val cArray = ArrayList<DataContainer>()
                                    val pArray = ArrayList<ProductModel>()

                                    for (d in data) {
                                        if (d is DataContainer)
                                            cArray.add(d)
                                        else if (d is ArrayList<*>)
                                            pArray.addAll(d as ArrayList<ProductModel>)
                                    }

                                    val ar =
                                        containers.filterTo(arrayListOf()) { c -> cArray.all { it.id != c.id } }

                                    ar.addAll(0, cArray)

                                    preferenceManager.apply {
                                        this.containers = ar
                                        this.products = pArray
                                    }

                                    adaptyCallback.invoke(ar.toPaywalls(), pArray, DataState.SYNCED, null)
                                    if (needQueue)
                                        nextQueue()
                                }
                            })
                    }

                }
            )
        }

        @JvmStatic
        fun getPromo(
            adaptyCallback: (promo: PromoModel?, error: AdaptyError?) -> Unit
        ) {
            LogHelper.logVerbose("getPromos()")
            addToQueue {
                getPromoInQueue(true, adaptyCallback)
            }
        }

        private var currentPromo: PromoModel? = null

        private fun getPromoInQueue(
            needQueue: Boolean,
            adaptyCallback: (promo: PromoModel?, error: AdaptyError?) -> Unit
        ) {
            apiClientRepository.getPromo(
                object : AdaptyPromosCallback {
                    override fun onResult(
                        promo: PromoModel?,
                        error: AdaptyError?
                    ) {

                        if (error != null || promo == null) {
                            adaptyCallback.invoke(null, error)
                            if (needQueue)
                                nextQueue()
                            return
                        }

                        fun finishSettingPaywallToPromo(it: PaywallModel) {
                            promo.paywall = it
                            adaptyCallback.invoke(promo, error)
                            if (currentPromo != promo) {
                                currentPromo = promo
                                onPromoReceivedListener?.onPromoReceived(promo)
                            }
                            if (needQueue)
                                nextQueue()
                        }

                        preferenceManager.containers
                            ?.toPaywalls()
                            ?.firstOrNull { it.variationId == promo.variationId }
                            ?.let {
                                finishSettingPaywallToPromo(it)
                            } ?: kotlin.run {
                            getPaywallsInQueue(needQueue) { paywalls, products, state, error ->
                                if (state == DataState.SYNCED) {
                                    if (error == null) {
                                        paywalls
                                            .firstOrNull { it.variationId == promo.variationId }
                                            ?.let {
                                                finishSettingPaywallToPromo(it)
                                            } ?: adaptyCallback.invoke(null, AdaptyError(message = "Paywall not found", adaptyErrorCode = AdaptyErrorCode.PAYWALL_NOT_FOUND))
                                    } else {
                                        adaptyCallback.invoke(null, error)
                                    }
                                }
                            }
                        }
                    }

                }
            )
        }

        @JvmStatic
        fun makePurchase(
            activity: Activity,
            product: ProductModel,
            adaptyCallback: (purchaserInfo: PurchaserInfoModel?, purchaseToken: String?, googleValidationResult: GoogleValidationResult?, product: ProductModel, error: AdaptyError?) -> Unit
        ) {
            LogHelper.logVerbose("makePurchase()")
            addToQueue {
                InAppPurchases(
                    context,
                    WeakReference(activity),
                    false,
                    preferenceManager,
                    product,
                    null,
                    apiClientRepository,
                    object : AdaptyPurchaseCallback {
                        override fun onResult(
                            purchase: Purchase?,
                            response: ValidateReceiptResponse?,
                            error: AdaptyError?
                        ) {
                            val purchaserInfo = response?.data?.attributes
                                ?.let(::generatePurchaserInfoModel)
                                ?.also(::checkChangesPurchaserInfo)
                            val validationResult = response?.data?.attributes?.googleValidationResult

                            adaptyCallback.invoke(purchaserInfo, purchase?.purchaseToken, validationResult, product, error)
                            nextQueue()
                        }
                    })
            }
        }

        @Deprecated(message = "Will be removed in newer versions", level = DeprecationLevel.WARNING)
        @JvmStatic
        @JvmOverloads
        fun syncPurchases(adaptyCallback: ((error: AdaptyError?) -> Unit)? = null) {
            addToQueue {
                syncPurchasesBody(true, adaptyCallback)
            }
        }

        private fun syncPurchasesBody(
            needQueue: Boolean,
            adaptyCallback: ((error: AdaptyError?) -> Unit)?
        ) {
            if (!::preferenceManager.isInitialized)
                preferenceManager = PreferenceManager(context)

            InAppPurchases(context,
                null,
                true,
                preferenceManager,
                ProductModel(),
                null,
                apiClientRepository,
                object : AdaptyRestoreCallback {
                    override fun onResult(response: RestoreReceiptResponse?, error: AdaptyError?) {
                        if (adaptyCallback != null) {
                            if (error == null)
                                adaptyCallback.invoke(null)
                            else
                                adaptyCallback.invoke(error)
                            if (needQueue) {
                                nextQueue()
                            }
                        }
                    }
                })
        }

        @JvmStatic
        fun restorePurchases(
            adaptyCallback: (purchaserInfo: PurchaserInfoModel?, googleValidationResultList: List<GoogleValidationResult>?, error: AdaptyError?) -> Unit
        ) {
            addToQueue {
                if (!::preferenceManager.isInitialized)
                    preferenceManager = PreferenceManager(context)

                InAppPurchases(
                    context,
                    null,
                    true,
                    preferenceManager,
                    ProductModel(),
                    null,
                    apiClientRepository,
                    object : AdaptyRestoreCallback {
                        override fun onResult(response: RestoreReceiptResponse?, error: AdaptyError?) {
                            val purchaserInfo = response?.data?.attributes
                                ?.let(::generatePurchaserInfoModel)
                                ?.also(::checkChangesPurchaserInfo)
                            val validationResultList = response?.data?.attributes?.googleValidationResult

                            adaptyCallback.invoke(purchaserInfo, validationResultList, error)

                            nextQueue()
                        }
                    })
            }
        }

        @JvmStatic
        @JvmOverloads
        fun updateAttribution(
            attribution: Any,
            source: AttributionType,
            networkUserId: String? = null,
            adaptyCallback: (error: AdaptyError?) -> Unit
        ) {
            LogHelper.logVerbose("updateAttribution()")
            addToQueue {
                apiClientRepository.updateAttribution(
                    attribution,
                    source,
                    networkUserId,
                    object : AdaptySystemCallback {
                        override fun success(response: Any?, reqID: Int) {
                            adaptyCallback.invoke(null)
                            nextQueue()
                        }

                        override fun fail(error: AdaptyError, reqID: Int) {
                            adaptyCallback.invoke(error)
                            nextQueue()
                        }
                    })
            }
        }

        @JvmStatic
        fun logout(adaptyCallback: (error: AdaptyError?) -> Unit) {
            addToQueue { logoutInQueue(adaptyCallback) }
        }

        private fun logoutInQueue(adaptyCallback: (error: AdaptyError?) -> Unit) {
            if (!::context.isInitialized) {
                adaptyCallback.invoke(AdaptyError(message = "Adapty was not initialized", adaptyErrorCode = AdaptyErrorCode.ADAPTY_NOT_INITIALIZED))
                nextQueue()
                return
            }

            if (!::preferenceManager.isInitialized) {
                preferenceManager = PreferenceManager(context)
            }

            preferenceManager.clearOnLogout()

            activateInQueue(null, adaptyCallback)
        }

        @JvmStatic
        fun refreshPushToken(newToken: String) {
            apiClientRepository.pushToken = newToken
            if (isActivated && preferenceManager.profileID.isNotEmpty()) {
                sendSyncMetaInstallRequest()
            }
        }

        @JvmStatic
        fun handlePromoIntent(
            intent: Intent?,
            adaptyCallback: (promo: PromoModel?, error: AdaptyError?) -> Unit
        ): Boolean {
            if (intent?.getStringExtra("source") != "adapty") {
                return false
            }
            kinesisManager.trackEvent(
                "promo_push_opened",
                mapOf("promo_delivery_id" to intent.getStringExtra("promo_delivery_id"))
            )
            getPromoInQueue(false, adaptyCallback)
            return true
        }

        @JvmStatic
        fun setOnPurchaserInfoUpdatedListener(onPurchaserInfoUpdatedListener: OnPurchaserInfoUpdatedListener?) {
            this.onPurchaserInfoUpdatedListener = onPurchaserInfoUpdatedListener
        }

        @JvmStatic
        fun setOnPromoReceivedListener(onPromoReceivedListener: OnPromoReceivedListener?) {
            this.onPromoReceivedListener = onPromoReceivedListener
        }

        @JvmStatic
        fun setLogLevel(logLevel: AdaptyLogLevel) {
            LogHelper.setLogLevel(logLevel)
        }
    }
}