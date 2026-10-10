package com.dtc.bus_tracker;

import com.dtc.bus_tracker.service.GtfsImportService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BusTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BusTrackerApplication.class, args);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "gtfs.import.enabled", havingValue = "true", matchIfMissing = true)
    CommandLineRunner init(GtfsImportService gtfsImportService) {
        return args -> gtfsImportService.importAll();
    }
}