# Thales CipherTrust Manager KMS Provider Implementation Plan

## Context

This change implements a new KMS provider for Thales CipherTrust Manager (CTM) to support Kroxylicious record encryption, as described in issue #3146. CTM is an enterprise key management solution similar to AWS KMS or HashiCorp Vault, but unlike those services, it doesn't natively support envelope encryption. We'll implement envelope encryption using CTM's primitive cryptographic operations (random, encrypt, decrypt).

**Why this change:** Enable Kroxylicious users to use Thales CipherTrust Manager for record encryption key management, providing an enterprise-grade KMS option with centralized key lifecycle management.

**Challenge:** CTM doesn't expose envelope encryption APIs like AWS KMS or Vault's transit engine. We must implement envelope encryption ourselves using:
- `/api/v1/vault/random` - Generate random DEK bytes
- `/api/v1/crypto/encrypt` - Wrap DEK with KEK
- `/api/v1/crypto/decrypt` - Unwrap DEK

## Architecture

Following the established KMS provider pattern used by AWS KMS, HashiCorp Vault, and Azure Key Vault implementations:

```
KmsService (lifecycle)
    ↓ initialize(Config)
    ↓ buildKms()
Kms (operations)
    ↓ generateDekPair() → random + encrypt
    ↓ decryptEdek() → decrypt
    ↓ resolveAlias()
```

## Module Structure

### Provider Module
**Path:** `kroxylicious-kms-providers/kroxylicious-kms-provider-thales-ciphertrust-manager/`

**Key classes:**
- `CipherTrustKmsService` - Lifecycle management, marked with `@Plugin(configType = Config.class)`
- `CipherTrustKms` - Core operations (implements `Kms<String, CipherTrustEdek>`)
- `CipherTrustEdek` - EDEK record containing encrypted DEK plus metadata needed for decryption
- `CipherTrustEdekSerde` - Serialization using Kafka ByteUtils for efficient encoding
- `CipherTrustAuthenticator` - JWT token management with caching and expiry handling
- `config/Config` - Configuration record with Jackson annotations
- `model/*` - HTTP request/response model classes

**Package:** `io.kroxylicious.kms.provider.thales.ciphertrust`

### Test Support Module
**Path:** `kroxylicious-kms-providers/kroxylicious-kms-provider-thales-ciphertrust-manager-test-support/`

**Key classes:**
- `CipherTrustMockServer` - WireMock-based CTM API simulator
- `CipherTrustTestKmsFacade` - TestKmsFacade implementation using mock server
- `AbstractCipherTrustTestKmsFacade` - Base class with common testing logic
- `CipherTrustTestKmsFacadeFactory` - ServiceLoader factory for test integration
- `RealCipherTrustTestKmsFacade` - Optional facade for testing against real CTM (environment variable configured)

**Package:** `io.kroxylicious.testing.kms.ciphertrust`

## Critical Files to Create

### 1. Configuration Model
**Files:** `config/Config.java`, `config/UserCredentials.java`, `config/ClientCredentials.java`

Main configuration record:
```java
public record Config(
    @JsonProperty(value = "endpointUrl", required = true) URI endpointUrl,
    @JsonProperty("userCredentials") @Nullable UserCredentials userCredentials,
    @JsonProperty("clientCredentials") @Nullable ClientCredentials clientCredentials,
    @JsonProperty(value = "tls", required = false) @Nullable Tls tls
) {
    public Config {
        Objects.requireNonNull(endpointUrl);
        
        // Exactly one credential type must be specified
        if (userCredentials == null && clientCredentials == null) {
            throw new KmsException("Either userCredentials or clientCredentials must be specified");
        }
        if (userCredentials != null && clientCredentials != null) {
            throw new KmsException("Cannot specify both userCredentials and clientCredentials");
        }
    }
}
```

User credentials record:
```java
public record UserCredentials(
    @JsonProperty(required = true) String username,
    @JsonProperty(required = true) PasswordProvider password
) {
    public UserCredentials {
        Objects.requireNonNull(username);
        Objects.requireNonNull(password);
        if (username.isEmpty()) {
            throw new IllegalArgumentException("username cannot be empty");
        }
    }
}
```

Client credentials record (placeholder for future):
```java
public record ClientCredentials(
    @JsonProperty(required = true) String clientId,
    @JsonProperty(required = true) PasswordProvider clientSecret
) {
    public ClientCredentials {
        Objects.requireNonNull(clientId);
        Objects.requireNonNull(clientSecret);
        if (clientId.isEmpty()) {
            throw new IllegalArgumentException("clientId cannot be empty");
        }
    }
}
```

**YAML examples:**

User authentication (initial implementation):
```yaml
endpointUrl: https://ctm.example.com
userCredentials:
  username: admin
  password: 
    passwordFile: /path/to/password
tls: null
```

Client authentication (future enhancement):
```yaml
endpointUrl: https://ctm.example.com
clientCredentials:
  clientId: my-client-id
  clientSecret:
    passwordFile: /path/to/secret
```

**Pattern reference:** `/Users/kwall/src/kroxylicious/kroxylicious-kms-providers/kroxylicious-kms-provider-aws-kms/src/main/java/io/kroxylicious/kms/provider/aws/kms/config/CredentialsConfig.java` (same pattern: separate nullable fields, exactly one must be set)

### 2. EDEK Structure
**File:** `CipherTrustEdek.java`

**VERIFIED FROM REAL API:** CTM's encrypt endpoint returns these fields (from issue comment #4565592584):
```json
{
  "ciphertext": "VY2D+Q9UyPRj2tIlHP/yVQ==",
  "tag": "ws/1krVDXQKA1JlThx6Ejg==",
  "id": "mykey1",
  "version": 0,
  "mode": "gcm",
  "iv": "GB1yLYeN5IljclAc38x6ow=="
}
```

All binary fields are base64-encoded. Decrypt requires all these fields.

**EDEK fields needed:**
- `String id` - KEK identifier (called "id" in CTM API, corresponds to kekRef)
- `byte[] ciphertext` - Encrypted DEK (base64 decoded from CTM)
- `byte[] tag` - Authentication tag for GCM mode (base64 decoded)
- `int version` - Key version (CTM supports versioning)
- `String mode` - Encryption mode ("gcm")
- `byte[] iv` - Initialization vector (base64 decoded)

**Note:** We do not use AAD (Additional Authenticated Data), so it is excluded from the EDEK structure.

**Pattern reference:** `/Users/kwall/src/kroxylicious/kroxylicious-kms-providers/kroxylicious-kms-provider-hashicorp-vault/src/main/java/io/kroxylicious/kms/provider/hashicorp/vault/VaultEdek.java` (simpler structure, but shows equals/hashCode override pattern for byte arrays)

### 3. EDEK Serialization
**File:** `CipherTrustEdekSerde.java`

Uses Kafka `ByteUtils` for varint encoding (efficient size representation):
```java
serialize():
  [varint: id length] [UTF-8: id]
  [varint: version]
  [varint: mode length] [UTF-8: mode]
  [varint: ciphertext length] [bytes: ciphertext]
  [varint: tag length] [bytes: tag]
  [varint: iv length] [bytes: iv]
```

**Note:** No AAD field in serialization since we don't use it.

**Pattern reference:** `/Users/kwall/src/kroxylicious/kroxylicious-kms-providers/kroxylicious-kms-provider-hashicorp-vault/src/main/java/io/kroxylicious/kms/provider/hashicorp/vault/VaultEdekSerde.java`

### 4. Authentication Management
**Files:** `auth/UserAuthenticationTokenService.java`, `auth/ClientAuthenticationTokenService.java` (future)

CTM uses JWT tokens with expiry. Supports two authentication modes (see https://docs-cybersec.thalesgroup.com/bundle/latest-cdsp-cm/page/admin/cm_admin/authentication/rest-api/index.html):

**User Authentication (initial implementation):**
- **Endpoint:** POST `/api/v1/auth/tokens/`
- **Initial auth request:**
  ```json
  {
    "username": "admin",
    "password": "password"
  }
  ```
- **Refresh request:**
  ```json
  {
    "refresh_token": "..."
  }
  ```
- **Response (both cases):** `{"jwt": "eyJ...", "duration": 300, "refresh_token": "..."}`

**Full Request Schema** (optional fields):
```json
{
  "username": string,           // Required for initial auth
  "password": string,           // Required for initial auth
  "refresh_token": string,      // Required for refresh
  "grant_type": string,         // Optional
  "refresh_token_lifetime": int,// Optional
  "refresh_token_revoke_unused_in": int, // Optional
  "renew_refresh_token": bool,  // Optional
  "auth_domain": string,        // Optional
  "client_id": string,          // Optional
  "connection": string,         // Optional
  "cookies": bool,              // Optional
  "domain": string,             // Optional
  "labels": [string]            // Optional
}
```

**Token Refresh Flow:**
1. Initial authentication: Send username + password → receive JWT + refresh_token
2. When token expires: Send refresh_token → receive new JWT + new refresh_token
3. Same endpoint for both operations (`/api/v1/auth/tokens/`)
4. Refresh avoids repeatedly sending username/password over the network

**Client Authentication (future enhancement):**
- **Endpoint:** POST `/api/v1/auth/tokens/` (same endpoint)
- **Request:** `{"grant_type": "client_credentials", "client_id": "...", "client_secret": "..."}`
- **Response:** `{"jwt": "eyJ...", "duration": 300}`
- **Note:** Requires client registration first via POST `/api/v1/auth/self/registration/`

**Follow Azure KMS Pattern:**

Use the existing `BearerTokenService` infrastructure from Azure provider:

1. **Reuse `BearerTokenService` interface** (from `kroxylicious-kms-provider-azure-key-vault-kms`)
   - Already defines `CompletionStage<BearerToken> getBearerToken()`
   - Already has `close()` for cleanup

2. **Reuse `CachingBearerTokenService`** (from `kroxylicious-kms-provider-azure-key-vault-kms`)
   - Handles token caching with expiry
   - Thread-safe refresh logic with state machine
   - 5-minute refresh margin (configurable)
   - Zero implementation needed - just use it!

3. **Implement concrete token services:**
   ```java
   class UserAuthenticationTokenService implements BearerTokenService {
       private final URI endpointUrl;
       private final String username;
       private final String password;
       private final HttpClient client;
       private final AtomicReference<String> refreshToken = new AtomicReference<>();
       
       @Override
       public CompletionStage<BearerToken> getBearerToken() {
           String currentRefreshToken = refreshToken.get();
           if (currentRefreshToken != null) {
               // Use refresh token to get new JWT (avoids sending password)
               return refreshWithToken(currentRefreshToken)
                   .exceptionallyCompose(error -> {
                       // If refresh fails, fall back to password auth
                       refreshToken.set(null);
                       return authenticateWithPassword();
                   });
           } else {
               // Initial authentication with username/password
               return authenticateWithPassword();
           }
       }
       
       private CompletionStage<BearerToken> authenticateWithPassword() {
           // POST /api/v1/auth/tokens/ with {"username": username, "password": password}
           // Parse response {"jwt": "...", "duration": 300, "refresh_token": "..."}
           // Store refresh_token: refreshToken.set(response.refreshToken)
           // Convert to BearerToken(jwt, expiresAt = now + duration)
       }
       
       private CompletionStage<BearerToken> refreshWithToken(String token) {
           // POST /api/v1/auth/tokens/ with {"refresh_token": token}
           // Parse response {"jwt": "...", "duration": 300, "refresh_token": "..."}
           // Update refresh_token: refreshToken.set(response.refreshToken)
           // Convert to BearerToken(jwt, expiresAt = now + duration)
       }
   }
   
   class ClientAuthenticationTokenService implements BearerTokenService {
       // Future implementation for client credentials
   }
   ```

4. **Wire it up in `CipherTrustKmsService.buildKms()`:**
   ```java
   BearerTokenService delegate;
   if (config.userCredentials() != null) {
       delegate = new UserAuthenticationTokenService(...);
   } else {
       delegate = new ClientAuthenticationTokenService(...);
   }
   BearerTokenService tokenService = new CachingBearerTokenService(delegate, Clock.systemUTC());
   return new CipherTrustKms(config.endpointUrl(), tokenService, ...);
   ```

5. **In `CipherTrustKms`, use the token service:**
   ```java
   private final BearerTokenService tokenService;
   
   private CompletionStage<HttpRequest> createAuthenticatedRequest(URI uri, ...) {
       return tokenService.getBearerToken()
           .thenApply(token -> HttpRequest.newBuilder()
               .uri(uri)
               .header("Authorization", "Bearer " + token.bearerToken())
               .build());
   }
   ```

**Benefits of this approach:**
- Reuses battle-tested token caching logic
- Thread-safe by design (state machine in `CachingBearerTokenService`)
- Automatic refresh with safety margin
- No need to implement our own caching/expiry logic
- Consistent with Azure KMS provider

**Pattern reference:** 
- Interface: `/Users/kwall/src/kroxylicious/kroxylicious-kms-providers/kroxylicious-kms-provider-azure-key-vault-kms/src/main/java/io/kroxylicious/kms/provider/azure/auth/BearerTokenService.java`
- Caching: `/Users/kwall/src/kroxylicious/kroxylicious-kms-providers/kroxylicious-kms-provider-azure-key-vault-kms/src/main/java/io/kroxylicious/kms/provider/azure/auth/CachingBearerTokenService.java`
- Example impl: `/Users/kwall/src/kroxylicious/kroxylicious-kms-providers/kroxylicious-kms-provider-azure-key-vault-kms/src/main/java/io/kroxylicious/kms/provider/azure/auth/OauthClientCredentialsTokenService.java`

**Pattern reference:** Similar to `/Users/kwall/src/kroxylicious/kroxylicious-kms-providers/kroxylicious-kms-provider-azure-key-vault-kms/src/main/java/io/kroxylicious/kms/provider/azure/auth/CachingBearerTokenService.java` for token caching

### 5. Core KMS Implementation
**File:** `CipherTrustKms.java`

Implements `Kms<String, CipherTrustEdek>`:

**generateDekPair(String kekRef):**
1. Get valid auth token from BearerTokenService
2. Call GET `/api/v1/vault/random?bytes=32` → parse response `{"bytes": "base64..."}`
3. Base64 decode to get plaintext DEK (32 bytes)
4. Call POST `/api/v1/crypto/encrypt` with `{"id": kekRef, "plaintext": "base64(dek)"}`
   - **Note:** Do not include `aad` field (not needed for envelope encryption)
5. Parse response: `{"ciphertext", "tag", "id", "version", "mode", "iv"}` (all base64 except version)
   - Response may include `aad` field but will be empty/null since we didn't send it
6. Base64 decode all binary fields, create `CipherTrustEdek(id, ciphertext, tag, version, mode, iv)`
7. Create `DestroyableRawSecretKey` from plaintext DEK
8. Return `DekPair<CipherTrustEdek>`

**decryptEdek(CipherTrustEdek edek):**
1. Get valid auth token from BearerTokenService
2. Build request with EDEK fields (base64 encode binary fields):
   `{"ciphertext": "base64...", "tag": "base64...", "id": "...", "version": 0, "mode": "gcm", "iv": "base64..."}`
   - **Note:** Do not include `aad` field (we don't use it)
3. Call POST `/api/v1/crypto/decrypt` → parse response `{"plaintext": "base64..."}`
4. Base64 decode plaintext
5. Return `DestroyableRawSecretKey` wrapping plaintext DEK

**resolveAlias(String alias):**

CTM supports key lookup by name via query parameter.

**Implementation approach:**
1. Call GET `/api/v1/vault/keys2?name={alias}`
2. Parse response (array) to extract the key identifier from first result:
   ```json
   [
     {
       "id": "5a78b671-8467-4548-82c8-ebce11bea4d6",
       "name": "My Encryption Key",
       "algorithm": "aes",
       ...
     }
   ]
   ```
3. Return the `id` field (UUID string) as the resolved key reference
4. Empty array or HTTP 404 → throw `UnknownAliasException`

**Notes:**
- Keys are identified by name (simple string, not complex alias objects)
- Return value should be the key ID (UUID) for use in encrypt/decrypt operations
- Query parameter approach allows filtering by name

**Error handling:**
- HTTP 401/403: Token refresh is handled automatically by `CachingBearerTokenService`
  - On next `getBearerToken()` call after expiry, it will refresh automatically
  - No need for manual invalidation or retry logic
- HTTP 404: Throw `UnknownKeyException` (for key operations) or `UnknownAliasException` (for alias resolution)
- HTTP 500+: Throw `KmsException` with status code
- Use structured logging with `LOGGER.atLevel().addKeyValue(...).log(...)`

**Pattern reference:** `/Users/kwall/src/kroxylicious/kroxylicious-kms-providers/kroxylicious-kms-provider-hashicorp-vault/src/main/java/io/kroxylicious/kms/provider/hashicorp/vault/VaultKms.java`

### 6. HTTP Model Classes
**Files:** `model/AuthRequest.java`, `model/AuthResponse.java`, `model/EncryptRequest.java`, `model/EncryptResponse.java`, `model/DecryptRequest.java`, `model/DecryptResponse.java`, `model/RandomResponse.java`, `model/CreateKeyRequest.java`, `model/CreateKeyResponse.java`, `model/GetKeyResponse.java`

All use Jackson `@JsonProperty` annotations. Concrete structures from API testing:

**AuthRequest:** Jackson record with optional fields (username/password for initial, refresh_token for refresh):
```java
public record AuthRequest(
    @Nullable String username,
    @Nullable String password,
    @Nullable String refreshToken,
    @Nullable String grantType,
    @Nullable Integer refreshTokenLifetime,
    @Nullable Integer refreshTokenRevokeUnusedIn,
    @Nullable Boolean renewRefreshToken,
    @Nullable String authDomain,
    @Nullable String clientId,
    @Nullable String connection,
    @Nullable Boolean cookies,
    @Nullable String domain,
    @Nullable List<String> labels
) {
    // Factory methods for clarity:
    static AuthRequest withPassword(String username, String password) {
        return new AuthRequest(username, password, null, ...);
    }
    
    static AuthRequest withRefreshToken(String refreshToken) {
        return new AuthRequest(null, null, refreshToken, ...);
    }
}
```

**AuthResponse:** `{"jwt": "...", "duration": 300, "refresh_token": "..."}` (same for both initial and refresh)
**EncryptRequest:** `{"id": "keyId", "plaintext": "base64"}` (no AAD)
**EncryptResponse:** `{"ciphertext": "base64", "tag": "base64", "id": "keyId", "version": 0, "mode": "gcm", "iv": "base64"}`
**DecryptRequest:** `{"ciphertext": "base64", "tag": "base64", "id": "keyId", "version": 0, "mode": "gcm", "iv": "base64"}` (no AAD)
**DecryptResponse:** `{"plaintext": "base64"}`
**RandomResponse:** `{"bytes": "base64"}`
**GetKeyResponse:** `{"id": "uuid", "name": "...", "algorithm": "aes", ...}` (minimal fields, use `@JsonIgnoreProperties(ignoreUnknown = true)`)

**Note:** All binary data uses base64 encoding. Must encode when sending, decode when receiving.

### 7. Mock Server for Testing
**File:** `CipherTrustMockServer.java` (in test-support module)

WireMock-based simulation of CTM REST APIs:
- POST `/api/v1/auth/tokens/` - Accept either username/password OR refresh_token, return `{"jwt": "mock-token", "duration": 300, "refresh_token": "mock-refresh-token"}`
- GET `/api/v1/vault/random?bytes=N` - Generate N random bytes via `SecureRandom`, return `{"bytes": "base64..."}`
- POST `/api/v1/crypto/encrypt` - Perform real AES-GCM encryption, return all fields (ciphertext, tag, id, version, mode, iv, aad)
- POST `/api/v1/crypto/decrypt` - Perform real AES-GCM decryption, return `{"plaintext": "base64..."}`
- POST `/api/v1/vault/keys2/` - Key creation (store in-memory indexed by ID), return key metadata with ID
- GET `/api/v1/vault/keys2?name={keyName}` - Key lookup by name query parameter, return array of matching keys:
  ```json
  [{"id": "uuid", "name": "keyName", "algorithm": "aes", ...}]
  ```
- DELETE `/api/v1/vault/keys2/{id}` - Key deletion, return 204 No Content

**Note for mock server:** Store keys in a map by ID. For name queries, filter the map and return matching entries in an array. Empty array if no match.

**Approach:** Mock server does real cryptography (not just stubs) for realistic testing. Stores KEKs in-memory map. Validates `Authorization: Bearer {token}` header.

**Pattern reference:** `/Users/kwall/src/kroxylicious/kroxylicious-kms-providers/kroxylicious-kms-provider-hashicorp-vault-test-support/src/main/java/io/kroxylicious/testing/kms/vault/AbstractVaultTestKmsFacade.java` for test facade structure

## Build Configuration

### Parent pom.xml Updates
**File:** `/Users/kwall/src/kroxylicious/kroxylicious-kms-providers/pom.xml`

Add to `<modules>`:
```xml
<module>kroxylicious-kms-provider-thales-ciphertrust-manager</module>
<module>kroxylicious-kms-provider-thales-ciphertrust-manager-test-support</module>
```

### Provider pom.xml
**File:** `kroxylicious-kms-provider-thales-ciphertrust-manager/pom.xml`

**Key dependencies:**
- `kroxylicious-kms` - Core KMS interfaces
- `kroxylicious-annotations` - `@Plugin` annotation
- `kroxylicious-kms-tls-support` - TLS configuration support
- `jackson-databind` - JSON serialization
- `kafka-clients` - Kafka ByteUtils for EDEK serialization
- `slf4j-api` - Logging
- `kroxylicious-kms-test-support` (test scope) - Test framework
- `wiremock` (test scope) - Unit testing with mocked HTTP

### Test Support pom.xml
**File:** `kroxylicious-kms-provider-thales-ciphertrust-manager-test-support/pom.xml`

**Key dependencies:**
- `kroxylicious-kms-provider-thales-ciphertrust-manager` - Provider under test
- `kroxylicious-kms-test-support` - TestKmsFacade framework
- `wiremock` - Mock server implementation
- `jackson-databind` - JSON handling

### ServiceLoader Registration

**Provider:** Create `src/main/resources/META-INF/services/io.kroxylicious.kms.service.KmsService` containing:
```
io.kroxylicious.kms.provider.thales.ciphertrust.CipherTrustKmsService
```

**Test Support:** Create `src/main/resources/META-INF/services/io.kroxylicious.testing.kms.TestKmsFacadeFactory` containing:
```
io.kroxylicious.testing.kms.ciphertrust.CipherTrustTestKmsFacadeFactory
io.kroxylicious.testing.kms.ciphertrust.RealCipherTrustTestKmsFacadeFactory
```

## Implementation Sequence

### Phase 1: Core Infrastructure
1. Create module directories and pom.xml files
2. Create configuration model:
   - `UserCredentials` record (for initial implementation)
   - `ClientCredentials` record (placeholder for future)
   - `Config` record with two nullable credential fields, validation ensures exactly one is set
3. Create HTTP model classes (`AuthRequest`, `AuthResponse`, `EncryptRequest`, `EncryptResponse`, `DecryptRequest`, `DecryptResponse`, `RandomResponse`)
4. Update parent pom.xml with new modules

### Phase 2: API Verification (COMPLETED)
~~Test against real CTM to verify API structure~~

**DONE:** User has provided complete API request/response examples in issue comment #4565592584. API structure is confirmed and documented in this plan.

### Phase 3: EDEK Implementation
1. Define `CipherTrustEdek` record based on real API response
2. Implement `CipherTrustEdekSerde` with correct field serialization
3. Write unit tests for serialization round-trips

### Phase 4: Authentication
1. Add dependency on `kroxylicious-kms-provider-azure-key-vault-kms` for `BearerTokenService` classes (scope: compile)
2. Implement `UserAuthenticationTokenService implements BearerTokenService`:
   - POST `/api/v1/auth/tokens/` with username/password
   - Parse response, convert to `BearerToken` (jwt + expiry)
3. Wire up in `CipherTrustKmsService.buildKms()`:
   - Create delegate token service based on credential type
   - Wrap with `CachingBearerTokenService`
   - Pass to `CipherTrustKms` constructor
4. Update `CipherTrustKms` to use `BearerTokenService`:
   - Call `tokenService.getBearerToken()` for each request
   - Add `Authorization: Bearer {token}` header
5. Add unit tests:
   - Test `UserAuthenticationTokenService` with WireMock
   - Test integration with `CachingBearerTokenService`
6. (Future) Implement `ClientAuthenticationTokenService` when client auth support is needed

### Phase 5: Core KMS Operations
1. Implement `CipherTrustKms.generateDekPair` (random + encrypt flow)
2. Implement `CipherTrustKms.decryptEdek`
3. Implement `CipherTrustKms.resolveAlias`:
   - GET `/api/v1/vault/keys2?name={alias}`
   - Parse response array, extract `id` field from first element
   - Handle empty array → `UnknownAliasException`
4. Implement `CipherTrustKms.edekSerde`
5. Add unit tests using WireMock for all endpoints (including name-based key lookup)

### Phase 6: KMS Service
1. Implement `CipherTrustKmsService`
2. Add ServiceLoader registration
3. Test initialization and KMS building

### Phase 7: Test Infrastructure
1. Implement `CipherTrustMockServer` with WireMock (all endpoints)
2. Implement `AbstractCipherTrustTestKmsFacade` and `CipherTrustTestKmsFacade`
3. Implement `CipherTrustTestKmsFacadeFactory`
4. Implement `RealCipherTrustTestKmsFacade` for real CTM testing
5. Add ServiceLoader registration

### Phase 8: Integration Testing
1. Verify tests run via existing parameterized KMS integration tests
2. Test against mock server
3. Test against real CTM instance using environment variables
4. Verify integration with record encryption filter

## Existing Patterns to Follow

### Reference Implementations
- **HashiCorp Vault** (simplest, closest pattern): `/Users/kwall/src/kroxylicious/kroxylicious-kms-providers/kroxylicious-kms-provider-hashicorp-vault/`
- **AWS KMS** (comprehensive): `/Users/kwall/src/kroxylicious/kroxylicious-kms-providers/kroxylicious-kms-provider-aws-kms/`

### Core Interfaces
- `Kms<K, E>`: `/Users/kwall/src/kroxylicious/kroxylicious-kms/src/main/java/io/kroxylicious/kms/service/Kms.java`
- `KmsService<C, K, E>`: `/Users/kwall/src/kroxylicious/kroxylicious-kms/src/main/java/io/kroxylicious/kms/service/KmsService.java`
- `Serde<T>`: `/Users/kwall/src/kroxylicious/kroxylicious-kms/src/main/java/io/kroxylicious/kms/service/Serde.java`

### Testing Framework
- `TestKmsFacade<C, K, E>`: `/Users/kwall/src/kroxylicious/kroxylicious-kms-test-support/src/main/java/io/kroxylicious/testing/kms/TestKmsFacade.java`
- Vault test facade: `/Users/kwall/src/kroxylicious/kroxylicious-kms-providers/kroxylicious-kms-provider-hashicorp-vault-test-support/src/main/java/io/kroxylicious/testing/kms/vault/`

## Security Considerations

### Credential Handling
- Use `PasswordProvider` to avoid hardcoded credentials
- Clear sensitive data from memory (passwords, tokens, DEK plaintext)
- Don't log JWT tokens or plaintext key material
- Use `DestroyableRawSecretKey` for DEKs to enable secure cleanup

### TLS Configuration
- Support TLS configuration via `Tls` object
- Allow custom trust stores for private CAs
- Default to requiring TLS in production

### Token Security
- Invalidate cached tokens on 401/403 errors
- Use reasonable token expiry (5 minutes default recommended)
- 30-second safety margin to avoid mid-operation expiry

### Logging
- Use structured logging: `LOGGER.atLevel().addKeyValue("key", value).log("message")`
- Never log plaintext DEKs, passwords, or JWT tokens
- Log key IDs and operation types for audit trail
- Apply conditional stack traces on hot paths per `.claude/rules/logging.md`

## Testing Strategy

### Unit Tests (provider module)
- EDEK serialization round-trips
- Token caching and expiry logic
- HTTP operations with WireMock
- Error handling for various HTTP status codes

### Integration Tests (test-support module)
- Full envelope encryption cycle (generate → encrypt → decrypt)
- Token expiry and refresh
- Concurrent operations
- TLS configuration
- Error scenarios (missing keys, invalid credentials)

### Compatibility Tests
- Existing parameterized KMS integration tests will automatically include CTM provider
- Integration test: `/Users/kwall/src/kroxylicious/kroxylicious-integration-tests/src/test/java/io/kroxylicious/it/kms/service/KmsIT.java`

### Real CTM Testing
- Use `KROXYLICIOUS_KMS_FACADE_FACTORY_CLASS_NAME_FILTER=RealCipherTrust` to select real facade
- Configure `CIPHERTRUST_URL` environment variable
- Run integration tests against user's AWS-hosted CTM instance

## Verification Plan

After implementation, verify end-to-end by:

1. **Unit tests pass:** All WireMock-based tests for authentication, encrypt, decrypt
2. **Integration tests pass:** KmsIT runs successfully with CTM provider
3. **Real CTM test:** Run tests against real AWS CTM instance with credentials
4. **Record encryption integration:** Configure record encryption filter with CTM KMS, verify encrypt/decrypt of Kafka records
5. **Error handling:** Verify proper exceptions for missing keys, expired tokens, network failures
6. **Performance:** Check that token caching reduces authentication overhead (measure auth calls vs operations)

## Resolved / Open Questions

### Resolved (from API examples)
1. ✅ **EDEK structure:** Confirmed - needs id, ciphertext, tag, version, mode, iv, aad (all documented above)
2. ✅ **Encryption mode:** CTM uses AES-GCM (mode field in response is "gcm")
3. ✅ **AAD support:** Optional - can be null or empty, but included in response if provided
4. ✅ **Key versioning:** CTM supports key versions (version field in encrypt/decrypt)
5. ✅ **Authentication:** User authentication via POST to `/api/v1/auth/tokens/`, returns JWT with 300s duration

### Resolved Questions
1. ~~**Alias resolution:**~~ ✅ CTM supports GET `/api/v1/vault/keys2?name={keyName}` - query parameter for name-based lookup
2. ~~**Key rotation:**~~ ✅ Defer to future enhancement (not needed for initial implementation)
3. ~~**Client authentication:**~~ ✅ Config model now supports both user and client auth. Will implement user auth first, add client auth later
4. ~~**AAD usage:**~~ ✅ Don't use AAD (Additional Authenticated Data) - not needed, simpler implementation
5. ~~**Client registration:**~~ ✅ Not our responsibility - users configure CTM externally with client IDs, we just consume the credentials
6. ~~**Refresh token usage:**~~ ✅ Same endpoint (`/api/v1/auth/tokens/`), pass `{"refresh_token": "..."}` instead of username/password
