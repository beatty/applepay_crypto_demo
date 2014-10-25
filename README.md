applepay_crypto_demo
====================

Basic demo-quality implementation of Apple Pay crypto, as described at https://developer.apple.com/library/ios/Documentation/PassKit/Reference/PaymentTokenJSON/PaymentTokenJSON.html

IMPORTANT:
 - You should probably use a processor/gateway that does this for you.
 - If you do it yourself, you should never use this code in production! Merchant EC private keys should be generated and remain permanently in an HSM.
 - Didn't implement Apple signature validation yet.
