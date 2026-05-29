/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * Authentication token management for CipherTrust Manager.
 * <p>
 * Uses the {@code BearerTokenService} pattern for JWT token acquisition, caching,
 * and refresh. Token services are wrapped with {@code CachingBearerTokenService}
 * for automatic token management.
 * </p>
 */
package io.kroxylicious.kms.provider.thales.ciphertrust.auth;
