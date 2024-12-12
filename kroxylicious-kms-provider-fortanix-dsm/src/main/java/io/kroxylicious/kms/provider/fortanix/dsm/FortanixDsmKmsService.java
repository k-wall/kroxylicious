/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.fortanix.dsm;

import java.time.Duration;
import java.util.Objects;

import io.kroxylicious.kms.provider.fortanix.dsm.config.Config;
import io.kroxylicious.kms.service.KmsService;
import io.kroxylicious.proxy.plugin.Plugin;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An implementation of the {@link KmsService} interface backed by a remote instance of AWS KMS.
 */
@Plugin(configType = Config.class)
public class FortanixDsmKmsService implements KmsService<Config, String, FortanixDsmKmsEdek> {

    @SuppressWarnings("java:S3077") // KMS services are thread safe. As Config is immutable, volatile is sufficient to ensure its safe publication between threads.
    private volatile Config config;

    @Override
    public void initialize(@NonNull Config config) {
        Objects.requireNonNull(config);
        this.config = config;
    }

    @NonNull
    @Override
    public FortanixDsmKms buildKms() {
        Objects.requireNonNull(config, "KMS service not initialized");
        return new FortanixDsmKms(config.endpointUrl(),
                config.accessKey().getProvidedPassword(),
                config.secretKey().getProvidedPassword(),
                config.region(),
                Duration.ofSeconds(20), config.sslContext());
    }
}
