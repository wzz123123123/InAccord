package com.inforvans.accord;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(systemName = "Accord Control Worker")
@SpringBootApplication(scanBasePackages = "com.inforvans.accord")
public class ControlWorkerApplication {
    public static void main(String[] args) {
        System.setProperty("accord.process-role", "control-worker");
        SpringApplication.run(ControlWorkerApplication.class, args);
    }
}
