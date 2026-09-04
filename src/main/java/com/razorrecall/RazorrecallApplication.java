package com.razorrecall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RazorrecallApplication {

	public static void main(String[] args) {
		SpringApplication.run(RazorrecallApplication.class, args);
	}

}
