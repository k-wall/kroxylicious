/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.tls;

import java.io.IOException;
import java.security.KeyException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.spec.InvalidKeySpecException;
import java.util.Optional;
import java.util.stream.Stream;

import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.kroxylicious.proxy.config.tls.FilePasswordSource;
import io.kroxylicious.proxy.config.tls.KeyPair;
import io.kroxylicious.proxy.config.tls.KeyStore;
import io.kroxylicious.proxy.config.tls.PasswordSource;
import io.kroxylicious.proxy.config.tls.StringPasswordSource;
import io.kroxylicious.proxy.config.tls.Tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assumptions.assumeThatCode;

/*

KW
TODO - ensure all exception include the filename(s)
mask passwords in toStrings()
server trustStore
TLS cipher suite exclusions
 */
class DefaultSslContextFactoryTest {
    private static final String JKS = "JKS";
    private static final String PKCS_12 = "PKCS12";
    private static final String PEM = KeyStore.PEM;
    private static final PasswordSource STOREPASS = new StringPasswordSource("storepass");
    private static final PasswordSource KEYPASS = new StringPasswordSource("keypass");
    public static final PasswordSource BADPASS = new StringPasswordSource("badpass");
    public static final PasswordSource KEYSTORE_FILE_PASSWORD_SOURCE = new FilePasswordSource(getFile("storepass.password"));
    public static final PasswordSource KEYPASS_FILE_PASSWORD_SOURCE = new FilePasswordSource(getFile("keypass.password"));
    private static final String NOT_EXIST = "/does/not/exist";
    private final DefaultSslContextFactory factory = new DefaultSslContextFactory();

    public static Stream<Arguments> serverWithKeyStore() {
        return Stream.of(
                Arguments.of("Platform Default Store JKS", null, "server.jks", STOREPASS, null),
                Arguments.of("JKS store=key", JKS, "server.jks", STOREPASS, null),
                Arguments.of("JKS store=key explicit", JKS, "server.jks", STOREPASS, STOREPASS),
                Arguments.of("JKS store!=key", JKS, "server_diff_keypass.jks", STOREPASS, KEYPASS),
                Arguments.of("PKCS12", PKCS_12, "server.p12", STOREPASS, null),
                Arguments.of("Combined key/crt PEM passed as keyStore (KIP-651)", PEM, "server_key_crt.pem", null, null),
                Arguments.of("Combined key/crt PEM passed as keyStore (KIP-651) with encrypted key", PEM, "server_crt_encrypted_key.pem", null, KEYPASS),
                Arguments.of("JKS keystore from file", JKS, "server_diff_keypass.jks", KEYSTORE_FILE_PASSWORD_SOURCE, KEYPASS_FILE_PASSWORD_SOURCE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource()
    public void serverWithKeyStore(String name,
                                   String storeType,
                                   String storeFile, PasswordSource storePassword, PasswordSource keyPassword) {
        checkPlatformSupportForKeyType(storeType);

        var keyStore = new KeyStore(getFile(storeFile), storePassword, keyPassword, storeType);
        var tls = new Tls(Optional.of(keyStore), Optional.empty(), Optional.empty(), Optional.empty());

        var sslContext = factory.buildServerSslContext(tls);
        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isServer()).isTrue();
    }

    @Test
    public void serverKeyStoreFileNotFound() {
        var tls = new Tls(Optional.of(new KeyStore(NOT_EXIST, null, null, null)),
                Optional.empty(), Optional.empty(), Optional.empty());

        assertThatCode(() -> factory.buildServerSslContext(tls)).hasCauseInstanceOf(IOException.class).hasMessageContaining(NOT_EXIST);
    }

    @Test
    public void serverKeyStoreIncorrectPassword() {
        var keyStore = new KeyStore(getFile("server.jks"),
                BADPASS,
                null,
                null);
        var tls = new Tls(Optional.of(keyStore),
                Optional.empty(), Optional.empty(), Optional.empty());

        assertThatCode(() -> factory.buildServerSslContext(tls)).hasRootCauseInstanceOf(UnrecoverableKeyException.class);
    }

    @Test
    public void serverKeyStoreIncorrectKeyPassword() {
        var keyStore = new KeyStore(getFile("server_diff_keypass.jks"),
                STOREPASS,
                BADPASS,
                null);
        var tls = new Tls(Optional.of(keyStore),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        assertThatCode(() -> factory.buildServerSslContext(tls)).hasRootCauseInstanceOf(UnrecoverableKeyException.class);
    }

    @Test
    public void serverKeyPair() {
        var keyPair = new KeyPair(getFile("server.key"), getFile("server.crt"), null);

        var tls = new Tls(Optional.empty(),
                Optional.of(keyPair),
                Optional.empty(),
                Optional.empty());

        var sslContext = factory.buildServerSslContext(tls);
        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isServer()).isTrue();
    }

    @Test
    public void serverKeyPairKeyProtectedWithPassword() {
        var keyPair = new KeyPair(getFile("server_encrypted.key"), getFile("server.crt"), new StringPasswordSource("keypass"));

        var tls = new Tls(Optional.empty(),
                Optional.of(keyPair),
                Optional.empty(),
                Optional.empty());

        var sslContext = factory.buildServerSslContext(tls);
        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isServer()).isTrue();
    }

    @Test
    public void serverKeyPairIncorrectKeyPassword() {
        var keyPair = new KeyPair(getFile("server_encrypted.key"), getFile("server.crt"), BADPASS);

        var tls = new Tls(Optional.empty(),
                Optional.of(keyPair),
                Optional.empty(),
                Optional.empty());

        assertThatCode(() -> factory.buildServerSslContext(tls)).hasCauseInstanceOf(InvalidKeySpecException.class);
    }

    @Test
    public void serverKeyPairCertificateNotFound() {
        serverKeyPairNotFound(getFile("server.key"), NOT_EXIST).hasCauseInstanceOf(CertificateException.class).hasStackTraceContaining(NOT_EXIST);
    }

    @Test
    public void serverKeyPairKeyNotFound() {
        serverKeyPairNotFound(NOT_EXIST, getFile("server.crt")).hasCauseInstanceOf(KeyException.class).hasStackTraceContaining(NOT_EXIST);
    }

    @Test
    public void clientPlatformTrust() {
        var tls = new Tls(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var sslContext = factory.buildClientSslContext(tls);
        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isClient()).isTrue();

    }

    public static Stream<Arguments> clientWithTrustStore() {
        return Stream.of(
                Arguments.of("Platform Default Store JKS", null, "client.jks", STOREPASS),
                Arguments.of("JKS", JKS, "client.jks", STOREPASS),
                Arguments.of("PKCS12", PKCS_12, "server.p12", STOREPASS),
                Arguments.of("CRT PEM passed as keyStore (KIP-651)", PEM, "server.crt", STOREPASS));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource()
    public void clientWithTrustStore(String name, String storeType, String storeFile, PasswordSource storePassword) {
        checkPlatformSupportForKeyType(storeType);

        var trustStore = new KeyStore(getFile(storeFile),
                storePassword,
                null,
                storeType);
        var tls = new Tls(Optional.empty(), Optional.empty(), Optional.of(trustStore), Optional.empty());

        var sslContext = factory.buildClientSslContext(tls);
        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isClient()).isTrue();
    }

    @Test
    public void clientTrustStoreIncorrectPassword() {
        var trustStore = new KeyStore(getFile("client.jks"),
                BADPASS,
                null,
                null);

        var tls = new Tls(Optional.of(trustStore),
                Optional.empty(), Optional.of(trustStore), Optional.empty());
        assertThatCode(() -> factory.buildClientSslContext(tls)).hasRootCauseInstanceOf(UnrecoverableKeyException.class);
    }

    private AbstractThrowableAssert<?, ? extends Throwable> serverKeyPairNotFound(String serverPrivateKeyFile, String serverCertificateFile) {
        var keyPair = new KeyPair(serverPrivateKeyFile, serverCertificateFile, null);

        var tls = new Tls(Optional.empty(),
                Optional.of(keyPair),
                Optional.empty(),
                Optional.empty());

        return assertThatCode(() -> factory.buildServerSslContext(tls));
    }

    private void checkPlatformSupportForKeyType(String keyStoreType) {
        if (keyStoreType != null && !PEM.equals(keyStoreType)) {
            assumeThatCode(() -> java.security.KeyStore.getInstance(keyStoreType)).doesNotThrowAnyException();
        }
    }

    private static String getFile(String resource) {
        var url = DefaultSslContextFactoryTest.class.getResource(resource);
        assertThat(url).isNotNull();
        return url.getFile();
    }
}
