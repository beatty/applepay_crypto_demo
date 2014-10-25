package com.johndbeatty.applepay;

import com.google.gson.Gson;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.KDFParameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMReader;
import org.bouncycastle.util.encoders.Base64;
import org.xmldap.crypto.KDFConcatGenerator;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyAgreement;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Security;
import java.security.UnrecoverableEntryException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Enumeration;
import java.util.Map;

/**
 * Example implementation of Apple Pay encryption
 *
 * WARNINGS:
 *  - You should validate Apple's signature -- this code does not do that currently
 *  - You should use an HSM for merchant EC private keys -- this example assumes an exported .p12 with a password of 'test'
 */
public class Main {
  // Apple Pay uses an 0s for the IV
  private static final byte[] IV = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

  // precompute prefix bytes for the 'other' parameter of the NIST contact KDF
  private static final byte[] KDF_OTHER_BYTES_PREFIX;

  static {
    try {
      KDF_OTHER_BYTES_PREFIX = ((char) 0x0D + "id-aes256-GCM" + "Apple").getBytes("ASCII");
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  public static void main(String[] args) throws IOException, CertificateException, KeyStoreException, NoSuchAlgorithmException, UnrecoverableEntryException, NoSuchProviderException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException, InvalidAlgorithmParameterException, NoSuchPaddingException {
    if (Security.getProvider("BC") == null) {
      Security.addProvider(new BouncyCastleProvider());
    }

    final String paymentTokenFilename = args[0];
    final String merchantPkcFilename = args[1];
    final String pkcs12Filename = args[2];

    // read json payment token
    Map paymentToken = new Gson().fromJson(new FileReader(paymentTokenFilename), Map.class);

    // data is the ciphertext
    byte[] data = Base64.decode((String) paymentToken.get("data"));

    // read the ephemeral public key. it's a PEM file without header/footer -- add it back to make our lives easy
    Map header = (Map) paymentToken.get("header");
    String ephemeralPubKeyStr = "-----BEGIN PUBLIC KEY-----\n" + header.get("ephemeralPublicKey") + "\n-----END PUBLIC KEY-----";
    PEMReader pemReaderPublic = new PEMReader(new StringReader(ephemeralPubKeyStr));
    ECPublicKey ephemeralPublicKey = (ECPublicKey) pemReaderPublic.readObject();

    // Apple assigns a merchant identifier and places it in an extension (OID 1.2.840.113635.100.6.32)
    final X509Certificate merchantCertificate = readDerEncodedX509Certificate(new FileInputStream(merchantPkcFilename));
    byte[] merchantIdentifier = extractMerchantIdentifier(merchantCertificate);

    // load the merchant EC private key
    // WARNING: this key should live permanently in an HSM in a production environment!
    // export it from e.g. mac's keychain
    ECPrivateKey merchantPrivateKey = loadPrivateKey(pkcs12Filename);

    // now we have all the data we need -- decrypt per Apple Pay spec
    final byte[] plaintext = decrypt(data, merchantPrivateKey, ephemeralPublicKey, merchantIdentifier);
    System.out.println(new String(plaintext, "ASCII"));
  }


  public static byte[] decrypt(byte[] ciphertext, ECPrivateKey merchantPrivateKey, ECPublicKey ephemeralPublicKey, byte[] merchantIdentifier) throws InvalidKeyException, NoSuchProviderException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidAlgorithmParameterException, BadPaddingException, IllegalBlockSizeException {
    // ECDH key agreement
    final KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH", "BC");
    keyAgreement.init(merchantPrivateKey);
    keyAgreement.doPhase(ephemeralPublicKey, true);
    final byte[] sharedSecret = keyAgreement.generateSecret();

    // NIST key derivation function w/ Apple Pay specific parameters
    byte[] partyV = merchantIdentifier;
    byte[] other = new byte[KDF_OTHER_BYTES_PREFIX.length + partyV.length];
    System.arraycopy(KDF_OTHER_BYTES_PREFIX, 0, other, 0, KDF_OTHER_BYTES_PREFIX.length);
    System.arraycopy(partyV, 0, other, KDF_OTHER_BYTES_PREFIX.length, partyV.length);

    final Digest digest = new SHA256Digest();
    KDFConcatGenerator kdfConcatGenerator = new KDFConcatGenerator(digest, other);
    kdfConcatGenerator.init(new KDFParameters(sharedSecret, null));
    byte[] aesKey = new byte[32];
    kdfConcatGenerator.generateBytes(aesKey, 0, aesKey.length);

    final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", "BC");
    final SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
    cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(IV));
    return cipher.doFinal(ciphertext);
  }

  private static ECPrivateKey loadPrivateKey(String pkcs12Filename) throws KeyStoreException, NoSuchProviderException, IOException, NoSuchAlgorithmException, CertificateException, UnrecoverableKeyException {
    KeyStore keystore = KeyStore.getInstance("PKCS12", "BC");
    keystore.load(new FileInputStream(pkcs12Filename), "test".toCharArray());
    assert keystore.size() == 1 : "wrong number of entries in keychain";
    Enumeration<String> aliases = keystore.aliases();
    String alias = null;
    while (aliases.hasMoreElements()) {
      alias = aliases.nextElement();
    }
    return (ECPrivateKey) keystore.getKey(alias, null);
  }

  private static byte[] extractMerchantIdentifier(X509Certificate merchantCertificate) throws UnsupportedEncodingException {
    byte[] merchantIdentifierTlv = merchantCertificate.getExtensionValue("1.2.840.113635.100.6.32");
    assert merchantIdentifierTlv.length == 68;
    byte[] merchantIdentifier = new byte[64];
    System.arraycopy(merchantIdentifierTlv, 4, merchantIdentifier, 0, 64);
    return hexStringToByteArray(new String(merchantIdentifier, "ASCII"));
  }

  private static X509Certificate readDerEncodedX509Certificate(InputStream in) throws FileNotFoundException, CertificateException {
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    return (X509Certificate) factory.generateCertificate(in);
  }

  public static byte[] hexStringToByteArray(String s) {
    int len = s.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
          + Character.digit(s.charAt(i + 1), 16));
    }
    return data;
  }
}
