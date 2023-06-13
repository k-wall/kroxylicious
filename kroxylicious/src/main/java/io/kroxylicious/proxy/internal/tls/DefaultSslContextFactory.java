/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.tls;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Optional;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import io.kroxylicious.proxy.config.tls.PasswordProvider;
import io.kroxylicious.proxy.config.tls.Tls;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "Requires ability to consume file resources from arbitrary, user-specified, locations on the file-system.")
public class DefaultSslContextFactory implements SslContextFactory {

    @Override
    public SslContext buildServerSslContext(Tls tls) {
        try {
            var builder = tls.keyStore().map(ks -> {
                var keyStoreFile = new File(ks.storeFile());
                if (ks.isPemType()) {
                    return SslContextBuilder.forServer(keyStoreFile, keyStoreFile,
                            Optional.ofNullable(ks.keyPassword()).map(PasswordProvider::getProvidedPassword).map(String::new).orElse(null));
                }
                else {
                    try (var is = new FileInputStream(keyStoreFile)) {
                        var password = Optional.ofNullable(ks.storePassword()).map(PasswordProvider::getProvidedPassword).map(String::toCharArray).orElse(null);
                        var keyStore = KeyStore.getInstance(ks.getType());
                        keyStore.load(is, password);
                        var keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                        keyManagerFactory.init(keyStore,
                                Optional.ofNullable(ks.keyPassword()).map(PasswordProvider::getProvidedPassword).map(String::toCharArray).orElse(password));
                        return SslContextBuilder.forServer(keyManagerFactory);
                    }
                    catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException | UnrecoverableKeyException e) {
                        throw new RuntimeException(e);
                    }
                }
            });

            if (builder.isEmpty() && tls.keyPair().isPresent()) {
                builder = tls.keyPair().map(kp -> {
                    var keyFile = new File(kp.privateKeyFile());
                    var keyCertChainFile = new File(kp.certificateFile());
                    // if (!keyFile.exists()) {
                    // throw new FileNotFoundException("could not find key file: " + keyFile);
                    // }
                    // if (!keyCertChainFile.exists()) {
                    // throw new FileNotFoundException("could not find certificate file: " + keyCertChainFile);
                    // }

                    return SslContextBuilder.forServer(keyCertChainFile,
                            keyFile,
                            Optional.ofNullable(kp.keyPassword()).map(PasswordProvider::getProvidedPassword).map(String::new).orElse(null));
                });
            }

            if (builder.isEmpty()) {
                throw new IllegalStateException("Unexpected state whilst generating SSL context");
            }
            return builder.get().build();
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public SslContext buildClientSslContext(Tls clientTls) {

        try {
            var sslContextBuilder = SslContextBuilder.forClient();

            clientTls.trustStore().ifPresent(ct -> {
                var trustStore = new File(ct.storeFile());
                if (ct.isPemType()) {
                    sslContextBuilder.trustManager(trustStore);
                }
                else {
                    try (var is = new FileInputStream(trustStore)) {

                        var password = Optional.ofNullable(ct.storePassword()).map(PasswordProvider::getProvidedPassword).map(String::toCharArray).orElse(null);
                        var keyStore = KeyStore.getInstance(ct.getType());
                        keyStore.load(is, password);

                        var trustManagerFactory = TrustManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                        trustManagerFactory.init(keyStore);
                        sslContextBuilder.trustManager(trustManagerFactory);
                    }
                    catch (GeneralSecurityException | IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
            clientTls.insecureTls().ifPresent(insecure -> {
                if (insecure) {
                    sslContextBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
                }
            });

            return sslContextBuilder.build();
        }
        catch (SSLException ex) {
            throw new RuntimeException(ex);
        }
    }
}
