package com.example.mapdatafetcher.service;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.logging.LogType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Owns a single reusable Chrome process. Callers must release every acquired lease. */
@Component
public class NaverBrowserPool {
  private static final String[] NAVER_ORIGINS = {
    "https://map.naver.com", "https://pcmap.place.naver.com"
  };

  private final boolean reuseBrowser;
  private final long idleTimeoutNanos;
  private final ScheduledExecutorService reaper;
  private ChromeDriver driver;
  private boolean leased;
  private Thread leaseOwner;
  private boolean preserveProfile;
  private boolean cleanupPending;
  private long lastReleasedNanos;
  private int successfulUses;
  private boolean closed;

  public NaverBrowserPool(
      @Value("${naver.map.selenium.reuse-browser:true}") boolean reuseBrowser,
      @Value("${naver.map.selenium.idle-timeout:PT45S}") Duration idleTimeout) {
    if (idleTimeout == null || idleTimeout.isZero() || idleTimeout.isNegative()) {
      throw new IllegalArgumentException("naver.map.selenium.idle-timeout must be positive");
    }
    this.reuseBrowser = reuseBrowser;
    this.idleTimeoutNanos = idleTimeout.toNanos();
    AtomicInteger threadNumber = new AtomicInteger();
    this.reaper =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              Thread thread =
                  new Thread(task, "naver-browser-pool-reaper-" + threadNumber.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
    long intervalMillis = Math.max(1_000L, Math.min(15_000L, idleTimeout.toMillis() / 3));
    this.reaper.scheduleWithFixedDelay(
        this::reapIdleBrowser, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
  }

  /**
   * Acquires the sole lease, lazily creating Chrome. A configured persistent profile is preserved
   * on successful release; callers pass that fact explicitly so this component never guesses.
   */
  public synchronized ChromeDriver acquire(
      Supplier<ChromeDriver> driverFactory, boolean preserveConfiguredProfile) {
    while (cleanupPending && !closed) {
      try {
        wait();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for browser cleanup", exception);
      }
    }
    if (closed) {
      throw new IllegalStateException("Naver browser pool is shutting down");
    }
    if (leased) {
      throw new IllegalStateException("Naver browser already has an active lease");
    }
    if (!reuseBrowser) {
      discardBrowser();
    } else if (driver != null && isIdleExpired(System.nanoTime())) {
      discardBrowser();
    }
    try {
      if (driver == null) {
        driver = driverFactory.get();
        if (driver == null) {
          throw new IllegalStateException("Chrome driver factory returned null");
        }
        successfulUses = 0;
      }
      leased = true;
      leaseOwner = Thread.currentThread();
      preserveProfile = preserveConfiguredProfile;
      return driver;
    } catch (RuntimeException | Error failure) {
      discardBrowser();
      throw failure;
    }
  }

  /**
   * Releases the lease. Failed searches always discard Chrome; successful reusable sessions queue
   * tab cleanup on the pool executor so response delivery does not wait for browser housekeeping.
   */
  public synchronized void release(boolean success) {
    if (closed) {
      return;
    }
    if (!leased) {
      throw new IllegalStateException("Naver browser has no active lease");
    }
    if (leaseOwner != Thread.currentThread()) {
      throw new IllegalStateException("Naver browser lease must be released by its owner thread");
    }

    ChromeDriver released = driver;
    boolean keepProfileState = preserveProfile;
    leased = false;
    leaseOwner = null;
    preserveProfile = false;
    if (!success || !reuseBrowser) {
      discardBrowser();
      return;
    }
    cleanupPending = true;
    try {
      reaper.execute(() -> cleanupReleasedBrowser(released, keepProfileState));
    } catch (RuntimeException schedulingFailure) {
      cleanupPending = false;
      discardBrowser();
      notifyAll();
    }
  }

  @PreDestroy
  public synchronized void shutdown() {
    closed = true;
    reaper.shutdownNow();
    // Shutdown is allowed to end the active process; the service is stopping and cannot transfer
    // it.
    leased = false;
    leaseOwner = null;
    cleanupPending = false;
    discardBrowser();
    notifyAll();
  }

  private synchronized void cleanupReleasedBrowser(
      ChromeDriver released, boolean keepProfileState) {
    if (closed || driver != released || !cleanupPending) {
      cleanupPending = false;
      notifyAll();
      return;
    }

    boolean cleaned = false;
    try {
      String oldTab = released.getWindowHandle();
      released.switchTo().newWindow(WindowType.TAB);
      String freshTab = released.getWindowHandle();
      released.switchTo().window(oldTab);
      released.close();
      released.switchTo().window(freshTab);
      released.get("about:blank");
      if (!keepProfileState) {
        for (String origin : NAVER_ORIGINS) {
          released.executeCdpCommand(
              "Storage.clearDataForOrigin",
              Map.of(
                  "origin",
                  origin,
                  "storageTypes",
                  "cookies,local_storage,indexeddb,websql,service_workers,cache_storage"));
        }
      }
      released.manage().logs().get(LogType.PERFORMANCE).getAll();
      cleaned = true;
    } catch (RuntimeException cleanupFailure) {
      discardBrowser();
    } finally {
      if (cleaned && driver == released) {
        successfulUses++;
        // Recycle before a browser can grow indefinitely, without adding a cold start to the
        // normal p95 path of an active burst. Idle browsers still close after 45 seconds.
        if (successfulUses >= 64) {
          discardBrowser();
        } else {
          lastReleasedNanos = System.nanoTime();
        }
      } else if (driver == released) {
        discardBrowser();
      }
      cleanupPending = false;
      notifyAll();
    }
  }

  private void reapIdleBrowser() {
    synchronized (this) {
      if (!closed
          && !leased
          && !cleanupPending
          && driver != null
          && isIdleExpired(System.nanoTime())) {
        discardBrowser();
      }
    }
  }

  private boolean isIdleExpired(long nowNanos) {
    return lastReleasedNanos != 0 && nowNanos - lastReleasedNanos >= idleTimeoutNanos;
  }

  private void discardBrowser() {
    ChromeDriver toClose = driver;
    driver = null;
    lastReleasedNanos = 0;
    successfulUses = 0;
    if (toClose != null) {
      try {
        toClose.quit();
      } catch (WebDriverException ignored) {
        // A broken driver is already unusable; do not let cleanup mask the capture failure.
      }
    }
  }
}
