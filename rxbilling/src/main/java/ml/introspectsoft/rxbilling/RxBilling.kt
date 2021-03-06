/*
 * Copyright (c) 2018 Vanniktech - Niklas Baudy
 * Modifications Copyright (c) 2020. Jason Burgess
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ml.introspectsoft.rxbilling

import android.app.Activity
import com.android.billingclient.api.*
import io.reactivex.rxjava3.annotations.CheckReturnValue
import io.reactivex.rxjava3.annotations.NonNull
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.PublishSubject
import ml.introspectsoft.rxbilling.extensions.toInventory
import ml.introspectsoft.rxbilling.extensions.toSha256

/**
 * Billing interface for Google's In-app Billing
 *
 * Currently supports 2.2.1
 */
class RxBilling(
        private val activity: Activity,
        private val logger: Logger = LogcatLogger(),
        private val scheduler: Scheduler = Schedulers.io()
) {
    private var billingClient: BillingClient? = null

    val purchasesUpdated: @NonNull PublishSubject<PurchasesUpdate> =
            PublishSubject.create<PurchasesUpdate>()

    /**
     * Destroys the current session and releases all of the references.
     * Call this when you're done or your Activity is about to be destroyed.
     */
    fun destroy() {
        billingClient?.endConnection()
        billingClient = null
    }

    /**
     * Queries InApp purchases by the given sku ids and emits those one by one and then completes.
     *
     * @param skuIds the sku ids to query. It should contain at least one id.
     * @return Observable emitting the available queried InApp purchases.
     */
    @CheckReturnValue
    fun queryInAppPurchases(vararg skuIds: String): Observable<Inventory?> = query(
            BillingClient.SkuType.INAPP, skuIds.toList(), SkuDetails::toInventory
    )

    /**
     * Queries subscriptions by the given sku ids and emits those one by one and then completes.
     * Make sure that the billing is supported first by using [isBillingForSubscriptionsSupported].
     *
     * @param skuIds the sku ids to query. It should contain at least one id.
     * @return Observable emitting the available queried subscriptions.
     */
    @CheckReturnValue
    fun querySubscriptions(vararg skuIds: String): Observable<Inventory?> = query(
            BillingClient.SkuType.SUBS, skuIds.toList(), SkuDetails::toInventory
    )

    @CheckReturnValue
    private fun <T> query(
            skuType: String, skuList: List<String>, converter: (SkuDetails) -> T
    ): Observable<T> {
        if (skuList.isEmpty()) {
            throw IllegalArgumentException("No ids were passed")
        }

        return connect().flatMapObservable { client ->
            Observable.create<T> { emitter ->
                val skuDetailsParams =
                        SkuDetailsParams.newBuilder().setSkusList(skuList).setType(skuType).build()

                client.querySkuDetailsAsync(skuDetailsParams) { result, skuDetailsList: List<SkuDetails>? ->
                    if ((result?.responseCode ?: -1) == BillingResponse.OK) {
                        if (skuDetailsList != null) {
                            for (skuDetail in skuDetailsList) {
                                emitter.onNext(converter.invoke(skuDetail))
                            }
                        }

                        emitter.onComplete()
                    } else {
                        emitter.onError(RuntimeException("Querying failed with responseCode: ${result.responseCode}"))
                    }
                }
            }
        }.subscribeOn(scheduler)
    }

    /**
     * Checks whether billing for subscriptions is supported or not.
     * In case it is the Completable will just complete.
     * Otherwise a [NoBillingSupportedException] will be thrown.
     *
     * @return Completable which will complete in case it is supported. Otherwise an error will be emitted.
     */
    @get:CheckReturnValue val isBillingForSubscriptionsSupported: @NonNull Completable
        // TODO: Implement the actual isFeatureSupported call
        get() = Completable.complete()
                .subscribeOn(scheduler) // https://issuetracker.google.com/issues/123447114

    /**
     * Acknowledge the given InApp purchase which has been bought.
     * Purchases not acknowledged or consumed after 3 days are refunded.
     *
     * @param[purchased] the purchased in app purchase to consume
     * @return Single containing the BillingResponse
     */
    @CheckReturnValue
    fun acknowledgePurchase(purchased: Purchase): Single<Int> {
        logger.d("Trying to acknowledge purchase ${purchased.sku} (${purchased.purchaseToken})")

        return connect().flatMap { client ->
            Single.create<Int> { emitter ->
                val params =
                        AcknowledgePurchaseParams.newBuilder()
                                .setPurchaseToken(purchased.purchaseToken)
                                .build()
                client.acknowledgePurchase(params) { result ->
                    emitter.onSuccess(
                            result?.responseCode ?: BillingClient.BillingResponseCode.ERROR
                    )
                }
            }
        }.subscribeOn(scheduler)

    }

    /**
     * Consumes the given InApp purchase which has been bought.
     * Purchases not acknowledged or consumed after 3 days are refunded.
     *
     * @param[purchased] the purchased in app purchase to consume
     * @return Single containing the BillingResponse
     */
    @CheckReturnValue
    fun consumePurchase(purchased: Purchase): Single<Int> {
        logger.d("Trying to consume purchase ${purchased.sku} (${purchased.purchaseToken})")

        return connect().flatMap { client ->
            Single.create<Int> { emitter ->
                val params =
                        ConsumeParams.newBuilder().setPurchaseToken(purchased.purchaseToken).build()
                client.consumeAsync(params) { result, _ ->
                    emitter.onSuccess(result.responseCode)
                }
            }
        }.subscribeOn(scheduler)
    }

    /**
     * Get purchases information from play store and triggers callback like it's coming from
     * onPurchasesUpdated().
     *
     * Should be triggered when an activity loads to handle new purchases.
     *
     * @param[skuType] sku type to check for (INAPP or SUBS)
     */
    @CheckReturnValue
    fun checkPurchases(skuType: String) {
        connect().flatMap { client ->
            Single.create<Int> {
                val result = client.queryPurchases(skuType)
                purchasesUpdated.onNext(PurchasesUpdate(result))
            }
        }.subscribeOn(scheduler)
    }

    /**
     * All of the InApp purchases that have taken place already on by one and then completes.
     * In case there were none the Observable will just complete.
     */
    @get:CheckReturnValue val purchasedInApps: Observable<PurchaseResponse>
        get() = getPurchased(BillingClient.SkuType.INAPP) {
            PurchaseResponse(it)
        }

    /**
     * All of the subscription purchases that have taken place already on by one and then completes.
     * In case there were none the Observable will just complete.
     */
    @get:CheckReturnValue val purchasedSubscriptions: Observable<PurchaseResponse>
        get() = getPurchased(BillingClient.SkuType.SUBS) {
            PurchaseResponse(it)
        }

    @CheckReturnValue
    private fun <T> getPurchased(
            skuType: String, converter: (Purchase) -> T
    ): @NonNull Observable<T> = connect().flatMapObservable { client ->
        Observable.create<T> { emitter ->
            val result = client.queryPurchases(skuType)
            if (result.responseCode == BillingResponse.OK) {
                result.purchasesList?.forEach {
                    emitter.onNext(converter.invoke(it))
                }

                emitter.onComplete()
            } else {
                emitter.onError(InAppBillingException(result.responseCode))
            }
        }
    }.subscribeOn(scheduler)

    @CheckReturnValue
    private fun connect() = Single.create<BillingClient> { emitter ->
        if (billingClient == null || billingClient?.isReady == false) {
            val client =
                    BillingClient.newBuilder(activity.application)
                            .enablePendingPurchases()
                            .setListener { result, purchases ->
                                purchasesUpdated.onNext(PurchasesUpdate(result, purchases))
                            }
                            .build()

            billingClient = client

            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult?) {
                    if (result?.responseCode == BillingResponse.OK) {
                        logger.d("Connected to BillingClient")
                        emitter.onSuccess(client)
                    } else {
                        logger.d("Could not connect to BillingClient. ResponseCode: ${result?.responseCode}")
                        billingClient = null
                    }
                }

                override fun onBillingServiceDisconnected() {
                    billingClient = null // We'll build up a new connection upon next request.
                }

            })
        } else {
            emitter.onSuccess(requireNotNull(billingClient))
        }
    }

    /**
     * Purchases the given inventory. which can be an InApp purchase or a subscription.
     * You can get an instance of Inventory through the [queryInAppPurchases] or
     * [querySubscriptions] method. Make sure that the billing for the type is supported by
     * using [isBillingForSubscriptionsSupported] for subscriptions.
     * In case of an error a [PurchaseException] will be emitted.
     *
     * The values of [accountId] and [profileId] will be hashed to remove personally identifying
     * information. Values are hashed using [String.toSha256] which is included in this library.
     *
     * @param[inventory] the given inventory to purchase. Can either be a one time purchase or a subscription.
     * @param[accountId] account id to be sent with the purchase
     * @param[profileId] profile id to be sent with the purchase if your app supports multiple profiles
     */
    @CheckReturnValue
    fun purchase(
            inventory: Inventory, accountId: String? = null, profileId: String? = null
    ): Single<BillingResult> {
        return connect().flatMap { client: BillingClient ->
            Single.create<BillingResult> { emitter ->
                val builder = BillingFlowParams.newBuilder().setSkuDetails(inventory.skuDetails)

                // Add hashed identifiers to request
                if (!accountId.isNullOrEmpty()) {
                    builder.setObfuscatedAccountId(accountId.toSha256())
                }
                if (!profileId.isNullOrEmpty()) {
                    builder.setObfuscatedProfileId(profileId.toSha256())
                }

                val params = builder.build()

                val result: BillingResult = client.launchBillingFlow(activity, params)
                logger.d("ResponseCode ${result.responseCode} for purchase when launching billing flow with ${inventory.toJson()}")

                emitter.onSuccess(result)
            }
        }.subscribeOn(scheduler)
    }
}
