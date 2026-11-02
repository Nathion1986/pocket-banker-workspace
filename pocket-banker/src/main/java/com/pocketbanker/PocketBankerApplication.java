package com.pocketbanker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PocketBankerApplication {
  public static void main(String[] args) {
    SpringApplication.run(PocketBankerApplication.class, args);
  }
}
