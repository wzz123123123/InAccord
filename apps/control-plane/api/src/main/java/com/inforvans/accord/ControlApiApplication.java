package com.inforvans.accord;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(systemName = "Accord Control API")
@SpringBootApplication(scanBasePackages = "com.inforvans.accord")
public class ControlApiApplication {
    public static void main(String[] args) {
        System.setProperty("accord.process-role", "control-api");
        SpringApplication.run(ControlApiApplication.class, args);
    }
}
