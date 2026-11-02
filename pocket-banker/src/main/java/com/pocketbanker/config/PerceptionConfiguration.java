package com.pocketbanker.config;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class PerceptionConfiguration {
  @Bean
  public Clock perceptionClock() {
    return Clock.systemUTC();
  }

  @Bean(name = "workflowWorkers")
  public ThreadPoolTaskExecutor workflowWorkers() {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(64);
    executor.setThreadNamePrefix("workflow-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(15);
    return executor;
  }

  @Bean(name = "workflowNodeWorkers")
  public ThreadPoolTaskExecutor workflowNodeWorkers() {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(32);
    executor.setThreadNamePrefix("workflow-node-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(15);
    return executor;
  }

  @Bean(name = "perceptionMailExecutor")
  public ThreadPoolTaskExecutor perceptionMailExecutor() {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(8);
    executor.setThreadNamePrefix("perception-mail-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(20);
    return executor;
  }

  @Bean(name = "perceptionExecutor")
  public ThreadPoolTaskExecutor perceptionExecutor(
      @Value("${perception.workers:2}") int workers,
      @Value("${perception.queue-capacity:32}") int capacity) {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(workers);
    executor.setMaxPoolSize(workers);
    executor.setQueueCapacity(capacity);
    executor.setThreadNamePrefix("perception-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(20);
    return executor;
  }
}
