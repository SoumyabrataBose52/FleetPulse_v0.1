package com.fleetpulse.orderer;

import com.fleetpulse.orderer.config.OrdererProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FleetPulse Telemetry Orderer and Fast-Path Service (§4.5, §7.6, §7.7, §8.M1, §15.7).
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(OrdererProperties.class)
public class OrdererApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrdererApplication.class, args);
    }
}
