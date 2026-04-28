package se.signalare.observer.iab

interface OnPurchaseListener {

    fun onPurchaseResult(isPurchaseSuccess: Boolean, message: String)
}
