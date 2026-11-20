package com.example.mapdatafetcher.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.chrome.ChromeDriver;

/** Reads matching first-page place items from the active Naver Maps iframe's Apollo cache. */
final class NaverHydration {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String READ_MATCHING_PLACE_ITEMS =
      """
      const expected = String(arguments[0] ?? '').trim();
      if (!expected) return null;

      let frameUrl;
      try { frameUrl = new URL(location.href); } catch (_) { return null; }
      if (frameUrl.hostname !== 'pcmap.place.naver.com' || !frameUrl.pathname.endsWith('/list'))
        return null;

      const frameQuery = frameUrl.searchParams.get('query');
      const frameSearchText = frameUrl.searchParams.get('searchText');
      if ((frameQuery || '').trim() !== expected && (frameSearchText || '').trim() !== expected)
        return null;

      const displayRaw = frameUrl.searchParams.get('display');
      const frameDisplay = displayRaw === null ? null : Number(displayRaw);
      if (frameDisplay === null || !Number.isInteger(frameDisplay) || frameDisplay <= 0)
        return null;

      const longitude = arguments[1], latitude = arguments[2];
      const sameCoordinate = (actual, expected) => actual !== null && actual !== undefined
        && String(actual).trim() !== '' && Number.isFinite(Number(actual))
        && Math.abs(Number(actual) - Number(expected)) <= 0.0000001;
      if (longitude !== null && latitude !== null
          && (!sameCoordinate(frameUrl.searchParams.get('x'), longitude)
              || !sameCoordinate(frameUrl.searchParams.get('y'), latitude))) return null;

      const cache = window.__APOLLO_STATE__;
      const root = cache && cache.ROOT_QUERY;
      if (!root || typeof root !== 'object') return null;

      const resolveReference = value => {
        let current = value;
        const visited = new Set();
        for (let depth = 0; depth < 8; depth++) {
          if (!current || typeof current !== 'object' || Array.isArray(current)) return null;
          const ref = current.__ref;
          if (typeof ref !== 'string') return current;
          if (visited.has(ref)) return null;
          visited.add(ref);
          current = cache[ref];
        }
        return current && typeof current === 'object' && !Array.isArray(current)
          && typeof current.__ref !== 'string' ? current : null;
      };

      const validItem = item => {
        if (!item || typeof item !== 'object' || Array.isArray(item)) return false;
        if (!(typeof item.id === 'string' || (typeof item.id === 'number' && Number.isFinite(item.id)))
            || String(item.id).trim() === '') return false;
        if (typeof item.name !== 'string' || item.name.trim() === '') return false;
        for (const coordinate of ['x', 'y']) {
          if (item[coordinate] !== undefined && item[coordinate] !== null) {
            if (!(typeof item[coordinate] === 'string' || typeof item[coordinate] === 'number')
                || (String(item[coordinate]).trim() !== '' && !Number.isFinite(Number(item[coordinate]))))
              return false;
          }
        }
        return true;
      };

      const matches = [];
      for (const key of Object.keys(root)) {
        if (!(key === 'placeList' || key.startsWith('placeList('))) continue;
        const open = key.indexOf('(');
        if (open < 0 || !key.endsWith(')')) continue;
        let args;
        try { args = JSON.parse(key.slice(open + 1, -1)); } catch (_) { continue; }
        const input = args && args.input;
        if (!input || typeof input !== 'object' || typeof input.query !== 'string'
            || input.query.trim() !== expected) continue;
        const start = input.start === undefined ? 1 : Number(input.start);
        if (!Number.isInteger(start) || start !== 1) continue;
        if (Number(input.display) !== frameDisplay) continue;
        if (longitude !== null && latitude !== null
            && (!sameCoordinate(input.x, longitude) || !sameCoordinate(input.y, latitude))) continue;

        const placeList = resolveReference(root[key]);
        if (!placeList) continue;
        const businesses = resolveReference(placeList.businesses);
        if (!businesses || !Array.isArray(businesses.items)) continue;
        const items = [];
        let complete = true;
        for (const rawItem of businesses.items) {
          const item = resolveReference(rawItem);
          if (!validItem(item)) { complete = false; break; }
          items.push(item);
        }
        if (!complete) continue;
        matches.push({items, sourceArray: businesses.items});
      }

      if (matches.length === 0) return null;
      if (matches.some(match => match.sourceArray !== matches[0].sourceArray)) return null;
      return JSON.stringify(matches[0].items);
      """;

  private NaverHydration() {}

  /**
   * Returns the ordered items for a query only when the active frame and Apollo cache agree.
   * Returns {@code null} when the frame is not ready, the cache is ambiguous, or references fail.
   */
  static String read(ChromeDriver driver, String expectedQuery, Double longitude, Double latitude) {
    if (driver == null || expectedQuery == null || expectedQuery.isBlank()) {
      return null;
    }
    try {
      Object result =
          ((JavascriptExecutor) driver)
              .executeScript(READ_MATCHING_PLACE_ITEMS, expectedQuery, longitude, latitude);
      if (!(result instanceof String json)) {
        return null;
      }
      JsonNode parsed = OBJECT_MAPPER.readTree(json);
      return parsed != null && parsed.isArray() ? json : null;
    } catch (RuntimeException | java.io.IOException exception) {
      return null;
    }
  }
}
