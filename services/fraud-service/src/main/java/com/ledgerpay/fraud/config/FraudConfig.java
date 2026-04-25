package com.ledgerpay.fraud.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FraudProperties.class)
public class FraudConfig {
}
