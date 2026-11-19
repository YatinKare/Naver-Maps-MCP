package com.example.mapdatafetcher.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Monotonic stage durations; never records queries, URLs, cookies or result bodies. */
final class SearchTiming {
  private final long started = System.nanoTime();
  private final String operation;
  private final int page;
  private final Map<String, Long> durations = new LinkedHashMap<>();
  private long stageStarted = started;
  private String stage = "browser_startup";

  SearchTiming(String operation, int page) {
    this.operation = operation;
    this.page = page;
  }

  void stage(String next) {
    long now = System.nanoTime();
    durations.merge(stage, now - stageStarted, Long::sum);
    stageStarted = now;
    stage = next;
  }

  void log(Logger logger) {
    stage("complete");
    Map<String, Long> millis = new LinkedHashMap<>();
    durations.forEach((name, nanos) -> millis.put(name, TimeUnit.NANOSECONDS.toMillis(nanos)));
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request
        && request.getResponse() != null) {
      request
          .getResponse()
          .setHeader(
              "Server-Timing",
              millis.entrySet().stream()
                  .map(entry -> entry.getKey() + ";dur=" + entry.getValue())
                  .collect(Collectors.joining(", ")));
    }
    logger.info(
        "Naver capture timing: operation={}, page={}, total_ms={}, stages_ms={}",
        operation,
        page,
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
        millis);
  }
}
