package com.tinyme.integrations.google.config;

import com.tinyme.integrations.google.service.GoogleTokenCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GoogleEncryptionConfiguration {
    @Bean
    GoogleTokenCipher googleTokenCipher(
            @Value("${tinyme.encryption-key:}") String encodedKeyset) {
        return new GoogleTokenCipher(encodedKeyset);
    }
}
