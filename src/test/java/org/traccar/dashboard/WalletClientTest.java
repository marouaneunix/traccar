package org.traccar.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class WalletClientTest {

    private static final Instant FROM = Instant.parse("2026-10-10T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-10T23:59:59.999Z");

    private Config config() {
        var config = new Config();
        config.setString(Keys.WALLET_TOKEN, "test-token");
        return config;
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        var response = (HttpResponse<String>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After", List.of("120")), (a, b) -> true));
        return response;
    }

    private WalletTokenStore tokenStore(Config config) throws Exception {
        var store = mock(WalletTokenStore.class);
        when(store.getToken()).thenAnswer(invocation -> config.getString(Keys.WALLET_TOKEN));
        return store;
    }

    private WalletClient wallet(Config config, HttpClient client) throws Exception {
        return new WalletClient(config, new ObjectMapper(), client, Clock.fixed(FROM, ZoneOffset.UTC), tokenStore(config));
    }

    @Test
    public void testAllPagesAreFilteredDeduplicatedAndCached() throws Exception {
        var client = mock(HttpClient.class);
        String record = "{\"id\":\"one\",\"accountId\":\"taxi-account\","
                + "\"recordDate\":\"2026-10-10T12:00:00Z\"}";
        String foreign = record.replace("one", "foreign").replace("taxi-account", "private-account");
        String outside = record.replace("one", "outside").replace("10T12", "09T12");
        var firstPage = response(200, "{\"records\":[" + record + "," + foreign + "],\"nextOffset\":200}");
        var secondPage = response(200, "{\"records\":[" + record + "," + outside + "],\"nextOffset\":null}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(firstPage, secondPage);
        var wallet = wallet(config(), client);
        var result = wallet.getRecords("taxi-account", FROM, TO);
        assertEquals(1, result.records().size());
        assertEquals("one", result.records().get(0).path("id").asText());
        assertEquals(result, wallet.getRecords("taxi-account", FROM, TO));
        var requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertTrue(requests.getAllValues().get(0).uri().getQuery().contains("accountId=taxi-account"));
        assertTrue(requests.getAllValues().get(0).uri().getQuery().contains("recordDate=gte."));
        assertTrue(requests.getAllValues().get(0).uri().getQuery().contains("recordDate=lte."));
        assertTrue(requests.getAllValues().get(1).uri().getQuery().contains("offset=200"));
        assertEquals("Bearer test-token", requests.getValue().headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    public void testRepeatedOffsetCannotReturnPartialTotals() throws Exception {
        var client = mock(HttpClient.class);
        var page = response(200, "{\"records\":[],\"nextOffset\":0}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(page);
        var error = assertThrows(WalletClient.WalletException.class, () -> wallet(config(), client).getRecords("taxi-account", FROM, TO));
        assertEquals("walletInvalidData", error.getCode());
    }

    @Test
    public void testRateLimitBlocksImmediateRetriesAndDoesNotExposeResponseBody() throws Exception {
        var client = mock(HttpClient.class);
        var page = response(429, "sensitive upstream body");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(page);
        var wallet = wallet(config(), client);
        var error = assertThrows(WalletClient.WalletException.class, () -> wallet.getRecords("taxi-account", FROM, TO));
        assertEquals("walletRateLimited", error.getMessage());
        assertEquals(120, error.getRetryAfter());
        assertThrows(WalletClient.WalletException.class, () -> wallet.getRecords("taxi-account", FROM, TO));
        verify(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    public void testMissingTokenMakesNoExternalRequest() throws Exception {
        var config = config();
        config.setString(Keys.WALLET_TOKEN, "");
        var client = mock(HttpClient.class);
        var error = assertThrows(WalletClient.WalletException.class, () -> wallet(config, client).getRecords("taxi-account", FROM, TO));
        assertEquals("walletNotConfigured", error.getCode());
        verifyNoInteractions(client);
    }

    @Test
    public void testFailureOnLaterPageCannotCachePartialData() throws Exception {
        var client = mock(HttpClient.class);
        var firstPage = response(200, "{\"records\":[],\"nextOffset\":200}");
        var failure = response(401, "private upstream content");
        var complete = response(200, "{\"records\":[],\"nextOffset\":null}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(firstPage, failure, complete);
        var wallet = wallet(config(), client);
        var error = assertThrows(WalletClient.WalletException.class, () -> wallet.getRecords("taxi-account", FROM, TO));
        assertEquals("walletAuthentication", error.getCode());
        assertEquals(0, wallet.getRecords("taxi-account", FROM, TO).records().size());
        verify(client, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    public void testCacheExpires() throws Exception {
        var client = mock(HttpClient.class);
        var complete = response(200, "{\"records\":[],\"nextOffset\":null}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(complete);
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(FROM);
        var wallet = new WalletClient(config(), new ObjectMapper(), client, clock, tokenStore(config()));
        wallet.getRecords("taxi-account", FROM, TO);
        wallet.getRecords("taxi-account", FROM, TO);
        when(clock.instant()).thenReturn(FROM.plusSeconds(301));
        wallet.getRecords("taxi-account", FROM, TO);
        verify(client, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    public void testCacheIsIsolatedByAccount() throws Exception {
        var client = mock(HttpClient.class);
        var first = response(200, "{\"records\":[{\"id\":\"one\",\"accountId\":\"taxi-account\","
                + "\"recordDate\":\"2026-10-10T12:00:00Z\"}],\"nextOffset\":null}");
        var second = response(200, "{\"records\":[{\"id\":\"two\",\"accountId\":\"second-account\","
                + "\"recordDate\":\"2026-10-10T12:00:00Z\"}],\"nextOffset\":null}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(first, second);
        var wallet = wallet(config(), client);
        var firstResult = wallet.getRecords("taxi-account", FROM, TO);
        var secondResult = wallet.getRecords("second-account", FROM, TO);
        assertEquals("taxi-account", firstResult.accountId());
        assertEquals("second-account", secondResult.accountId());
        assertEquals("two", secondResult.records().get(0).path("id").asText());
        assertEquals(firstResult, wallet.getRecords("taxi-account", FROM, TO));
        assertEquals(secondResult, wallet.getRecords("second-account", FROM, TO));
        var requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertTrue(requests.getAllValues().get(1).uri().getQuery().contains("accountId=second-account"));
    }

    @Test
    public void testMissingAccountCannotQueryAllWalletAccounts() throws Exception {
        var client = mock(HttpClient.class);
        var wallet = wallet(config(), client);
        var error = assertThrows(WalletClient.WalletException.class, () -> wallet.getRecords("", FROM, TO));
        assertEquals("walletAccountMissing", error.getCode());
        verifyNoInteractions(client);
    }

    @Test
    public void testChangingTokenInvalidatesCachedRecords() throws Exception {
        var client = mock(HttpClient.class);
        var page = response(200, "{\"records\":[],\"nextOffset\":null}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(page);
        var config = config();
        var wallet = wallet(config, client);
        wallet.getRecords("taxi-account", FROM, TO);
        config.setString(Keys.WALLET_TOKEN, "replacement-token");
        wallet.getRecords("taxi-account", FROM, TO);
        var requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("Bearer replacement-token",
                requests.getValue().headers().firstValue("Authorization").orElseThrow());
    }
}
