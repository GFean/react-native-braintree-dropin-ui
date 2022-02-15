package tech.power.RNBraintreeDropIn;
import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import com.braintreepayments.api.models.PayPalAccountNonce;
import com.braintreepayments.api.models.PostalAddress;
import com.cardinalcommerce.dependencies.internal.minidev.json.JSONObject;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.braintreepayments.api.dropin.DropInActivity;
import com.braintreepayments.api.dropin.DropInRequest;
import com.braintreepayments.api.dropin.DropInResult;
import com.braintreepayments.api.models.PaymentMethodNonce;
import com.braintreepayments.api.models.CardNonce;
import com.braintreepayments.api.models.ThreeDSecureInfo;
import com.braintreepayments.api.models.GooglePaymentRequest;
import com.google.android.gms.wallet.TransactionInfo;
import com.google.android.gms.wallet.WalletConstants;
import com.braintreepayments.api.models.PayPalRequest;

public class RNBraintreeDropInModule extends ReactContextBaseJavaModule {

  private Promise mPromise;
  private static final int DROP_IN_REQUEST = 0x444;
  private boolean isVerifyingThreeDSecure = false;
  private ReadableMap globalOptions = Arguments.createMap();
  public RNBraintreeDropInModule(ReactApplicationContext reactContext) {
    super(reactContext);
    reactContext.addActivityEventListener(mActivityListener);
  }
  @ReactMethod
  public void show(final ReadableMap options, final Promise promise) {
    globalOptions = options;
    isVerifyingThreeDSecure = false;
    if (!options.hasKey("clientToken")) {
      promise.reject("NO_CLIENT_TOKEN", "You must provide a client token");
      return;
    }
    Activity currentActivity = getCurrentActivity();
    if (currentActivity == null) {
      promise.reject("NO_ACTIVITY", "There is no current activity");
      return;
    }
    DropInRequest dropInRequest = new DropInRequest().clientToken(options.getString("clientToken"));
    if(options.hasKey("vaultManager")) {
      dropInRequest.vaultManager(options.getBoolean("vaultManager"));
    }
    dropInRequest.collectDeviceData(true);

    if(options.getBoolean("googlePay")){
      GooglePaymentRequest googlePaymentRequest = new GooglePaymentRequest()
              .transactionInfo(TransactionInfo.newBuilder()
                      .setTotalPrice(options.getString("orderTotal"))
                      .setTotalPriceStatus(WalletConstants.TOTAL_PRICE_STATUS_FINAL)
                      .setCurrencyCode(options.getString("currencyCode"))
                      .build())
              .billingAddressRequired(false)
              .shippingAddressRequired(false)
              .googleMerchantId(options.getString("googlePayMerchantId"))
              .environment(options.getString("environment"));
      dropInRequest.googlePaymentRequest(googlePaymentRequest);
    }else{
      dropInRequest.disableGooglePayment();
    }
    if(options.getBoolean("payPal") && options.getBoolean("shippingAddressRequired")){
      PayPalRequest payPalPaymentRequest = new PayPalRequest()
              .shippingAddressRequired(true).shippingAddressEditable(true);
      dropInRequest.paypalRequest(payPalPaymentRequest);
    }
     if(!options.getBoolean("payPal")){ 
    //disable paypal 
     dropInRequest.disablePayPal(); 
      }

    if(options.getBoolean("cardDisabled")) {
      dropInRequest.disableCard();
    }
    
    if(!options.getBoolean("savePaymentDetails")) {
     dropInRequest.vaultCard(false);
   }
    if (options.hasKey("threeDSecure")) {
      final ReadableMap threeDSecureOptions = options.getMap("threeDSecure");
      if (!threeDSecureOptions.hasKey("amount")) {
        promise.reject("NO_3DS_AMOUNT", "You must provide an amount for 3D Secure");
        return;
      }
      isVerifyingThreeDSecure = true;
      dropInRequest
              .amount(String.valueOf(threeDSecureOptions.getDouble("amount")))
              .requestThreeDSecureVerification(true);
    }
    mPromise = promise;
    currentActivity.startActivityForResult(dropInRequest.getIntent(currentActivity), DROP_IN_REQUEST);
  }
  private final ActivityEventListener mActivityListener = new BaseActivityEventListener() {
    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
      super.onActivityResult(requestCode, resultCode, data);
      if (requestCode != DROP_IN_REQUEST || mPromise == null) {
        return;
      }
      if (resultCode == Activity.RESULT_OK) {
        DropInResult result = data.getParcelableExtra(DropInResult.EXTRA_DROP_IN_RESULT);
        PaymentMethodNonce paymentMethodNonce = result.getPaymentMethodNonce();
        String deviceData = result.getDeviceData();
        if (isVerifyingThreeDSecure && paymentMethodNonce instanceof CardNonce) {
          CardNonce cardNonce = (CardNonce) paymentMethodNonce;
          ThreeDSecureInfo threeDSecureInfo = cardNonce.getThreeDSecureInfo();
          if (!threeDSecureInfo.isLiabilityShiftPossible()) {
            mPromise.reject("3DSECURE_NOT_ABLE_TO_SHIFT_LIABILITY", "3D Secure liability cannot be shifted");
          } else if (!threeDSecureInfo.isLiabilityShifted()) {
            mPromise.reject("3DSECURE_LIABILITY_NOT_SHIFTED", "3D Secure liability was not shifted");
          } else {
            resolvePayment(paymentMethodNonce, deviceData);
          }
        } else {
          resolvePayment(paymentMethodNonce, deviceData);
        }
      } else if (resultCode == Activity.RESULT_CANCELED) {
        mPromise.reject("USER_CANCELLATION", "The user cancelled");
      } else {
        Exception exception = (Exception) data.getSerializableExtra(DropInActivity.EXTRA_ERROR);
        mPromise.reject(exception.getMessage(), exception.getMessage());
      }
      mPromise = null;
    }
  };
  private final void resolvePayment(PaymentMethodNonce paymentMethodNonce, String deviceData) {
    WritableMap jsResult = Arguments.createMap();
    jsResult.putString("nonce", paymentMethodNonce.getNonce());
    jsResult.putString("type", paymentMethodNonce.getTypeLabel());
    jsResult.putString("description", paymentMethodNonce.getDescription());
    jsResult.putBoolean("isDefault", paymentMethodNonce.isDefault());
    jsResult.putString("deviceData", deviceData);
    if (paymentMethodNonce instanceof PayPalAccountNonce && globalOptions.getBoolean("shippingAddressRequired")) {
      PayPalAccountNonce payPalAccountNonce = (PayPalAccountNonce)paymentMethodNonce;
      // Access additional information
      String email = payPalAccountNonce.getEmail();
      String firstName = payPalAccountNonce.getFirstName();
      String lastName = payPalAccountNonce.getLastName();
      String phone = payPalAccountNonce.getPhone();

      PostalAddress shippingAddress = payPalAccountNonce.getShippingAddress();
      WritableMap shippingAddressJSON = Arguments.createMap();
      
      shippingAddressJSON.putString("streetAddress", shippingAddress.getStreetAddress());
      shippingAddressJSON.putString("locality", shippingAddress.getLocality());
      shippingAddressJSON.putString("country", "");
      shippingAddressJSON.putString("countryCodeAlpha2", shippingAddress.getCountryCodeAlpha2());
      shippingAddressJSON.putString("email", email);
      shippingAddressJSON.putString("firstName", firstName);
      shippingAddressJSON.putString("lastName", lastName);
      shippingAddressJSON.putString("mobilePhone", shippingAddress.getPhoneNumber());
      shippingAddressJSON.putString("region", shippingAddress.getRegion());
      shippingAddressJSON.putString("postalCode", shippingAddress.getPostalCode());
      jsResult.putMap("shippingAddress", shippingAddressJSON);
    }
    mPromise.resolve(jsResult);
  }
  @Override
  public String getName() {
    return "RNBraintreeDropIn";
  }
}
