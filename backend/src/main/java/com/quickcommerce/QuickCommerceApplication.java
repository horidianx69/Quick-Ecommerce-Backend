package com.quickcommerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // runs OrderService.expireOverdue
public class QuickCommerceApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuickCommerceApplication.class, args);
    }
}
