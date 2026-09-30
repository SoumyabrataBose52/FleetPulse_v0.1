package com.fleetpulse.normalizer;

import com.fleetpulse.normalizer.config.NormalizerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * FleetPulse Multi-OEM Normalizer Application (§4.5, §5.2, §15.6).
 */
@SpringBootApplication
@EnableConfigurationProperties(NormalizerProperties.class)
public class NormalizerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NormalizerApplication.class, args);
    }
}
