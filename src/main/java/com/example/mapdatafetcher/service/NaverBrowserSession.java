package com.example.mapdatafetcher.service;

import com.example.mapdatafetcher.exception.CaptureException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Locale;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Uses a normal Chrome profile; never submits credentials or solves verification challenges. */
@Component
public class NaverBrowserSession {
  private final String userDataDir;
  private volatile Instant retryAfter = Instant.EPOCH;

  public NaverBrowserSession(@Value("${naver.map.selenium.user-data-dir:}") String userDataDir) {
    this.userDataDir = userDataDir;
  }

  /** The resolved property also covers CLI/system configuration, not just environment variables. */
  public boolean hasPersistentProfile() {
    return userDataDir != null && !userDataDir.isBlank();
  }

  public void beforeSearch() {
    if (Instant.now().isBefore(retryAfter)) {
      throw failure("rate_limited", "Naver rate-limit cooldown is active; wait before retrying");
    }
  }

  public void configure(ChromeOptions options) {
    if (!hasPersistentProfile()) {
      return;
    }
    try {
      Path profile = Path.of(userDataDir).normalize();
      if (!profile.isAbsolute()) {
        throw new CaptureException(
            "session_configuration",
            "browser_startup",
            "Chrome profile path must be absolute",
            null);
      }
      Files.createDirectories(
          profile,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      if (!Files.getPosixFilePermissions(profile)
          .equals(PosixFilePermissions.fromString("rwx------"))) {
        throw new CaptureException(
            "session_configuration",
            "browser_startup",
            "Chrome profile directory must have mode 0700",
            null);
      }
      options.addArguments("--user-data-dir=" + profile);
    } catch (IOException | UnsupportedOperationException | InvalidPathException exception) {
      throw new CaptureException(
          "session_configuration",
          "browser_startup",
          "Could not open private Chrome profile directory",
          exception);
    }
  }

  /** Only search/navigation responses matter; telemetry failures must not fail a search. */
  public void checkResponse(
      String url, int status, boolean matchingSearchResponse, boolean documentResponse) {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException exception) {
      return;
    }
    String host = uri.getHost();
    if (host == null || !(host.equals("naver.com") || host.endsWith(".naver.com"))) {
      return;
    }
    String path = uri.getPath() == null ? "" : uri.getPath();
    boolean navigation =
        (host.equals("map.naver.com") && path.startsWith("/p/"))
            || (host.equals("pcmap.place.naver.com") && path.startsWith("/place/list"))
            || host.equals("nid.naver.com");
    if (!matchingSearchResponse && !(documentResponse && navigation)) {
      return;
    }
    if (status == 429) {
      // One local cooldown, no automatic retry or identity/profile rotation.
      retryAfter = Instant.now().plusSeconds(60);
      throw failure("rate_limited", "Naver returned HTTP 429; pause requests before retrying");
    }
    if (status == 401) {
      throw failure("login_required", "Naver requires a legitimate account sign-in");
    }
    if (status == 403) {
      throw failure(
          "access_denied", "Naver denied this request; inspect the browser before retrying");
    }
  }

  /** Positive evidence only: absence of results or GraphQL is not an authentication failure. */
  public void checkPage(WebDriver driver) {
    try {
      checkAvailablePage(driver);
      // Login/verification can replace the cross-origin result frame while Maps stays open.
      if (Boolean.TRUE.equals(
          ((JavascriptExecutor) driver).executeScript("return window === window.top"))) {
        for (WebElement frame : driver.findElements(By.cssSelector("iframe#searchIframe"))) {
          if (frame.isDisplayed()) {
            try {
              driver.switchTo().frame(frame);
              checkAvailablePage(driver);
            } finally {
              driver.switchTo().defaultContent();
            }
            break;
          }
        }
      }
    } catch (WebDriverException exception) {
      // A closed/navigating browser cannot prove an auth failure. Preserve the original failure.
    }
  }

  private void checkAvailablePage(WebDriver driver) {
    URI uri;
    try {
      uri = URI.create(driver.getCurrentUrl());
    } catch (IllegalArgumentException exception) {
      return;
    }
    String host = uri.getHost();
    if (host == null || !(host.equals("naver.com") || host.endsWith(".naver.com"))) {
      return;
    }
    String pageState =
        String.valueOf(
            ((JavascriptExecutor) driver)
                .executeScript(
                    "if (!(location.hostname === 'naver.com' || location.hostname.endsWith('.naver.com'))) return '';"
                        + "const visible=e=>e && e.getClientRects().length>0;"
                        + "const text=document.body?.innerText || '';"
                        + "if ([...document.querySelectorAll('input#captcha,input[name=\"captcha\"],img#captchaimg')].some(visible)"
                        + " || /자동입력 방지문자|자동입력 방지 문자/.test(text)) return 'verification_required';"
                        + "if (location.hostname === 'nid.naver.com' && location.pathname.includes('nidlogin.login')) return 'login_required';"
                        + "return '';"));
    if ("verification_required".equals(pageState)) {
      throw failure(
          "verification_required", "Complete Naver verification in the private browser profile");
    }
    if (host.toLowerCase(Locale.ROOT).equals("nid.naver.com")
            && uri.getPath() != null
            && uri.getPath().contains("nidlogin.login")
        || "login_required".equals(pageState)) {
      throw failure("login_required", "Sign in to Naver in the private browser profile");
    }
  }

  private CaptureException failure(String category, String message) {
    return new CaptureException(category, "authentication", message, null);
  }
}
