package com.fleetpulse.streamengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * FleetPulse Stateful Stream Processing Engine (§4.5, §7.10–7.15, §8.M2–M5, §15.9).
 * Processes trips, idling, EV intelligence, and alerts at 100K vehicle scale.
 */
@SpringBootApplication
@EnableAsync
public class StreamEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(StreamEngineApplication.class, args);
    }
}
