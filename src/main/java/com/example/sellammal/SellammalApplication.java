package com.example.sellammal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class SellammalApplication {

	public static void main(String[] args) {
		SpringApplication.run(SellammalApplication.class, args);
	}

}
