[rxbilling](../../index.md) / [ml.introspectsoft.rxbilling](../index.md) / [RxBilling](index.md) / [consumePurchase](./consume-purchase.md)

# consumePurchase

`@CheckReturnValue fun consumePurchase(purchasedInApp: `[`Purchased`](../-purchased/index.md)`): Single<`[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`>`

Consumes the given InApp purchase which has been bought.
Purchases not acknowledged or consumed after 3 days are refunded.

### Parameters

`purchasedInApp` - the purchased in app purchase to consume

**Return**
Single containing the BillingResponse
