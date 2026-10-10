/*
 * Copyright 2026 Traccar contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.UriBuilder;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.storage.StorageException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class WalletClient {

    private static final String RECORDS_URL = "https://rest.budgetbakers.com/wallet/v1/api/records";
    private static final int MAX_PAGES = 100;

    public record WalletData(String accountId, List<JsonNode> records, String fetchedAt, String lastDataChangeAt,
                             boolean syncInProgress) { }

    public static class WalletException extends IOException {
        private final String code;
        private final long retryAfter;

        public WalletException(String code) {
            this(code, 0);
        }

        public WalletException(String code, long retryAfter) {
            super(code);
            this.code = code;
            this.retryAfter = retryAfter;
        }

        public String getCode() {
            return code;
        }

        public long getRetryAfter() {
            return retryAfter;
        }
    }

    private record Period(String accountId, Instant from, Instant to) { }

    private final Config config;
    private final WalletTokenStore tokenStore;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final Clock clock;
    private final Map<Period, WalletData> cache = new LinkedHashMap<>();
    private Instant retryAt = Instant.MIN;
    private String activeToken;

    @Inject
    public WalletClient(Config config, ObjectMapper objectMapper, WalletTokenStore tokenStore) {
        this(config, objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                Clock.systemUTC(), tokenStore);
    }

    WalletClient(Config config, ObjectMapper objectMapper, HttpClient client, Clock clock,
                 WalletTokenStore tokenStore) {
        this.config = config;
        this.tokenStore = tokenStore;
        this.objectMapper = objectMapper;
        this.client = client;
        this.clock = clock;
    }

    public boolean isConfigured() throws StorageException {
        String token = tokenStore.getToken();
        return token != null && !token.isBlank();
    }

    public synchronized WalletData getRecords(String accountId, Instant from, Instant to) throws WalletException {
        String token;
        try {
            token = tokenStore.getToken();
        } catch (StorageException e) {
            throw new WalletException("walletTokenDuplicate".equals(e.getMessage())
                    || "walletTokenAttributeInvalid".equals(e.getMessage())
                    ? e.getMessage() : "walletSettingsUnavailable");
        }
        if (token == null || token.isBlank()) {
            cache.clear();
            activeToken = null;
            throw new WalletException("walletNotConfigured");
        }
        if (!token.equals(activeToken)) {
            cache.clear();
            retryAt = Instant.MIN;
            activeToken = token;
        }
        if (accountId == null || accountId.isBlank()) {
            throw new WalletException("walletAccountMissing");
        }
        var period = new Period(accountId, from, to);
        var cached = cache.get(period);
        int cacheSeconds = Math.max(30, Math.min(3600, config.getInteger(Keys.WALLET_CACHE_SECONDS)));
        if (cached != null && Instant.parse(cached.fetchedAt()).plusSeconds(cacheSeconds).isAfter(clock.instant())) {
            return cached;
        }
        if (retryAt.isAfter(clock.instant())) {
            throw new WalletException("walletRateLimited", Duration.between(clock.instant(), retryAt).toSeconds() + 1);
        }
        var records = new LinkedHashMap<String, JsonNode>();
        int offset = 0;
        String lastChange = null;
        boolean syncing = false;
        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                var uri = UriBuilder.fromUri(RECORDS_URL)
                        .queryParam("accountId", accountId)
                        .queryParam("recordDate", "gte." + from, "lte." + to)
                        .queryParam("limit", 200).queryParam("offset", offset).build();
                var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/json").GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                checkResponse(response);
                var root = objectMapper.readTree(response.body());
                if (root == null || !root.path("records").isArray()) {
                    throw new WalletException("walletInvalidData");
                }
                lastChange = response.headers().firstValue("X-Last-Data-Change-At").orElse(lastChange);
                syncing |= Boolean.parseBoolean(response.headers().firstValue("X-Sync-In-Progress").orElse("false"));
                for (var record : root.path("records")) {
                    if (!accountId.equals(record.path("accountId").asText())) {
                        continue;
                    }
                    Instant date = Instant.parse(record.path("recordDate").asText());
                    if (!date.isBefore(from) && !date.isAfter(to)) {
                        if (record.path("id").asText().isBlank()) {
                            throw new WalletException("walletInvalidData");
                        }
                        records.put(record.path("id").asText(), record);
                    }
                }
                var next = root.get("nextOffset");
                if (next == null || next.isNull()) {
                    var result = new WalletData(accountId, List.copyOf(records.values()), clock.instant().toString(),
                            lastChange, syncing);
                    cache.put(period, result);
                    if (cache.size() > 24) {
                        cache.remove(cache.keySet().iterator().next());
                    }
                    return result;
                }
                if (!next.isIntegralNumber() || next.intValue() <= offset) {
                    throw new WalletException("walletInvalidData");
                }
                offset = next.intValue();
            }
            throw new WalletException("walletTooManyRecords");
        } catch (WalletException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WalletException("walletUnavailable");
        } catch (IOException | IllegalArgumentException e) {
            throw new WalletException("walletUnavailable");
        }
    }

    private void checkResponse(HttpResponse<String> response) throws WalletException {
        switch (response.statusCode()) {
            case 200 -> { }
            case 401, 403 -> throw new WalletException("walletAuthentication");
            case 409 -> throw new WalletException("walletSyncing");
            case 429 -> {
                long seconds = 60;
                try {
                    seconds = Math.max(1,
                            Integer.parseInt(response.headers().firstValue("Retry-After").orElse("60")));
                } catch (NumberFormatException ignore) {
                }
                retryAt = clock.instant().plusSeconds(seconds);
                throw new WalletException("walletRateLimited", seconds);
            }
            default -> throw new WalletException("walletUnavailable");
        }
    }
}
