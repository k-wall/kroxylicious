/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter.oauthbearer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.security.sasl.Sasl;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;

import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.message.SaslAuthenticateRequestData;
import org.apache.kafka.common.message.SaslAuthenticateResponseData;
import org.apache.kafka.common.message.SaslHandshakeRequestData;
import org.apache.kafka.common.message.SaslHandshakeResponseData;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerValidatorCallbackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.LoadingCache;

import io.kroxylicious.proxy.filter.FilterContext;
import io.kroxylicious.proxy.filter.RequestFilterResult;
import io.kroxylicious.proxy.filter.ResponseFilterResult;
import io.kroxylicious.proxy.filter.SaslAuthenticateRequestFilter;
import io.kroxylicious.proxy.filter.SaslAuthenticateResponseFilter;
import io.kroxylicious.proxy.filter.SaslHandshakeRequestFilter;
import io.kroxylicious.proxy.filter.oauthbearer.sasl.BackoffStrategy;
import io.kroxylicious.proxy.tag.VisibleForTesting;

import static org.apache.kafka.common.protocol.Errors.NONE;
import static org.apache.kafka.common.protocol.Errors.SASL_AUTHENTICATION_FAILED;
import static org.apache.kafka.common.protocol.Errors.UNKNOWN_SERVER_ERROR;
import static org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule.OAUTHBEARER_MECHANISM;

/**
 * OauthBearerValidation filter enables a validation on the JWT token received from client before forwarding it to cluster.
 * <p>
 * If the token is not validated, then the request is short-circuited.
 * It reduces resource consumption on the cluster when a client sends too many invalid SASL requests.
 */
public class OauthBearerValidationFilter
        implements SaslHandshakeRequestFilter, SaslAuthenticateRequestFilter,
        SaslAuthenticateResponseFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(OauthBearerValidationFilter.class);

    private final ScheduledExecutorService executorService;
    private final BackoffStrategy strategy;
    private final LoadingCache<String, AtomicInteger> rateLimiter;
    private final OAuthBearerValidatorCallbackHandler oauthHandler;
    private SaslServer saslServer;

    private boolean validateAuthentication = false;

    public OauthBearerValidationFilter(ScheduledExecutorService executorService, SharedOauthBearerValidationContext sharedContext) {
        this.executorService = executorService;
        this.strategy = sharedContext.backoffStrategy();
        this.rateLimiter = sharedContext.rateLimiter();
        this.oauthHandler = sharedContext.oauthHandler();
    }

    /**
     * Init the SaslServer for downstream SASL
     */
    @Override
    public CompletionStage<RequestFilterResult> onSaslHandshakeRequest(short apiVersion, RequestHeaderData header, SaslHandshakeRequestData request,
                                                                       FilterContext context) {
        try {
            if (OAUTHBEARER_MECHANISM.equals(request.mechanism())) {
                this.saslServer = Sasl.createSaslServer(OAUTHBEARER_MECHANISM, "kafka", null, null, this.oauthHandler);
                this.validateAuthentication = true;
            }
        }
        catch (SaslException e) {
            LOGGER.debug("SASL error : {}", e.getMessage(), e);
            return context.requestFilterResultBuilder()
                    .shortCircuitResponse(new SaslHandshakeResponseData().setErrorCode(UNKNOWN_SERVER_ERROR.code()))
                    .withCloseConnection()
                    .completed();
        }
        return context.forwardRequest(header, request);
    }

    @Override
    public CompletionStage<RequestFilterResult> onSaslAuthenticateRequest(short apiVersion, RequestHeaderData header,
                                                                          SaslAuthenticateRequestData request, FilterContext context) {
        if (!validateAuthentication) {
            // client is already authenticated or is not using OAUTHBEARER mechanism, we can forward the request to cluster
            return context.forwardRequest(header, request);
        }
        else {
            return authenticate(request.authBytes())
                    .thenCompose(bytes -> context.forwardRequest(header, request))
                    .exceptionallyCompose(e -> {
                        if (e.getCause() instanceof SaslAuthenticationException) {
                            SaslAuthenticateResponseData failedResponse = new SaslAuthenticateResponseData()
                                    .setErrorCode(SASL_AUTHENTICATION_FAILED.code())
                                    .setErrorMessage(e.getMessage())
                                    .setAuthBytes(request.authBytes());
                            LOGGER.debug("SASL Authentication failed : {}", e.getMessage(), e);
                            return context.requestFilterResultBuilder().shortCircuitResponse(failedResponse).withCloseConnection().completed();
                        }
                        else {
                            LOGGER.debug("SASL error : {}", e.getMessage(), e);
                            return context.requestFilterResultBuilder()
                                    .shortCircuitResponse(
                                            new SaslAuthenticateResponseData()
                                                    .setErrorCode(UNKNOWN_SERVER_ERROR.code())
                                                    .setAuthBytes(request.authBytes()))
                                    .withCloseConnection()
                                    .completed();
                        }
                    });
        }
    }

    @Override
    public CompletionStage<ResponseFilterResult> onSaslAuthenticateResponse(short apiVersion, ResponseHeaderData header,
                                                                            SaslAuthenticateResponseData response, FilterContext context) {
        if (response.errorCode() == NONE.code()) {
            this.validateAuthentication = false;
        }
        return context.forwardResponse(header, response);
    }

    private CompletionStage<byte[]> authenticate(byte[] authBytes) {
        String digest;
        try {
            digest = digestBytes(authBytes);
        }
        catch (NoSuchAlgorithmException e) {
            return CompletableFuture.failedStage(e);
        }
        Duration delay = strategy.getDelay(rateLimiter.get(digest).get());
        return schedule(() -> {
            try {
                return CompletableFuture.completedStage(doAuthenticate(authBytes));
            }
            catch (Exception e) {
                return CompletableFuture.failedStage(e);
            }
        }, delay)
                .whenComplete((bytes, e) -> {
                    if (e != null) {
                        rateLimiter.get(digest).incrementAndGet();
                    }
                    else {
                        rateLimiter.invalidate(digest);
                    }
                });
    }

    private byte[] doAuthenticate(byte[] authBytes) throws SaslException {
        try {
            byte[] bytes = this.saslServer.evaluateResponse(authBytes);
            if (!this.saslServer.isComplete()) {
                throw new SaslAuthenticationException("SASL failed : " + new String(bytes, StandardCharsets.UTF_8));
            }
            return bytes;
        }
        finally {
            this.saslServer.dispose();
        }
    }

    @SuppressWarnings("java:S1602") // not able to test the scheduled lambda otherwise
    private <A> CompletionStage<A> schedule(Supplier<CompletionStage<A>> operation, Duration duration) {
        if (duration.equals(Duration.ZERO)) {
            return operation.get();
        }
        CompletableFuture<A> future = new CompletableFuture<>();
        executorService.schedule(() -> {
            operation.get().whenComplete((a, throwable) -> {
                if (throwable != null) {
                    future.completeExceptionally(throwable);
                }
                else {
                    future.complete(a);
                }
            });
        }, duration.toMillis(), TimeUnit.MILLISECONDS);
        return future;
    }

    @VisibleForTesting
    public static String digestBytes(byte[] input) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        byte[] hashBytes = digest.digest(input);

        return HexFormat.of().formatHex(hashBytes);

    }

}