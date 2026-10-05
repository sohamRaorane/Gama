package com.meshpay.ingestion.config;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the RSA private key the backend uses to decrypt mesh packets.
 *
 * <p>The key never exists in code or in the repository: it is provided as a
 * base64-encoded PKCS#8 DER blob through the {@code MESH_CRYPTO_PRIVATE_KEY}
 * environment variable (mapped to {@code meshpay.crypto.private-key}, and
 * loaded from {@code .env} by DotenvLoader for local runs). Only the matching
 * <em>public</em> key is ever handed to senders — the simulator/bridge encrypts
 * with it and the backend alone can unwrap the AES session key.
 *
 * <p><b>Fail fast:</b> the service refuses to start when the property is
 * missing or malformed. A running ingestion service that silently cannot
 * decrypt is worse than one that refuses to boot, and this error is far easier
 * to diagnose than a stream of 500s at request time.
 *
 * <p>Tests never load this configuration: they generate an ephemeral keypair
 * with {@code RsaOaepCipher.generateKeyPair()} (as the existing crypto tests do).
 */
@Configuration
public class CryptoKeyConfig {

    @Bean
    public PrivateKey meshRecipientPrivateKey(
            @Value("${meshpay.crypto.private-key:}") String base64Pkcs8PrivateKey) {
        if (base64Pkcs8PrivateKey == null || base64Pkcs8PrivateKey.isBlank()) {
            throw new IllegalStateException(
                    "meshpay.crypto.private-key is not configured. Set MESH_CRYPTO_PRIVATE_KEY "
                            + "to a base64-encoded PKCS#8 RSA private key (see .env.example).");
        }
        try {
            byte[] der = Base64.getDecoder().decode(base64Pkcs8PrivateKey.trim());
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            throw new IllegalStateException(
                    "meshpay.crypto.private-key is not a valid base64 PKCS#8 RSA private key", ex);
        }
    }
}
