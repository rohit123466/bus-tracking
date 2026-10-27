package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.live.RealtimeSnapshot;
import com.google.transit.realtime.GtfsRealtime.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class DtcApiClientTest {
    HttpServer server;
    RealtimeSnapshot snapshot;
    DtcApiClient client;
    int status;
    byte[] body;
    String etag;
    final java.util.List<String> ifNoneMatch = new java.util.ArrayList<>();
    @BeforeEach void setup() throws Exception {
        status = 200; body = new byte[]{1, 2, 3}; etag = null;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed", exchange -> {
            String sent = exchange.getRequestHeaders().getFirst("If-None-Match");
            ifNoneMatch.add(sent);
            if (etag != null && etag.equals(sent)) { exchange.sendResponseHeaders(304, -1); exchange.close(); return; }
            if (etag != null) exchange.getResponseHeaders().set("ETag", etag);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        snapshot = new RealtimeSnapshot(); client = new DtcApiClient(snapshot);
        ReflectionTestUtils.setField(client, "apiUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/feed");
        ReflectionTestUtils.setField(client, "apiKey", "test-secret");
        ReflectionTestUtils.setField(client, "tripUpdatesUrl", "");
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void missingCredentialsAreVisible() throws Exception {
        ReflectionTestUtils.setField(client, "apiKey", "");
        assertTrue(client.fetchVehiclePositions().isEmpty());
        assertTrue(snapshot.feedError.contains("DTC_API_KEY"));
    }
    @Test void authorizationFailureDoesNotLeakKey() {
        status = 403;
        var error = assertThrows(IllegalStateException.class, client::fetchVehiclePositions);
        assertTrue(snapshot.feedError.contains("HTTP 403"));
        assertFalse(error.toString().contains("test-secret"));
        assertNull(error.getCause());
    }
    @Test void malformedResponseIsVisible() {
        assertThrows(IllegalStateException.class, client::fetchVehiclePositions);
        assertTrue(snapshot.feedError.contains("malformed"));
    }
    FeedEntity vehicle(String entity, String id, long stamp, float latitude) {
        return FeedEntity.newBuilder().setId(entity).setVehicle(VehiclePosition.newBuilder()
            .setVehicle(VehicleDescriptor.newBuilder().setId(id))
            .setPosition(Position.newBuilder().setLatitude(latitude).setLongitude(77.2f))
            .setTimestamp(stamp)).build();
    }
    @Test void publisherReceivesOnlyFreshValidUniqueVehicles() throws Exception {
        long now = Instant.now().getEpochSecond();
        body = FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0"))
            .addEntity(vehicle("1", "bus", now - 10, 28.6f))
            .addEntity(vehicle("2", "bus", now, 28.7f))
            .addEntity(vehicle("3", "old", now - 300, 28.6f))
            .addEntity(vehicle("4", "invalid", now, 100f)).build().toByteArray();
        var events = client.fetchVehiclePositions();
        assertEquals(1, events.size());
        assertEquals("bus", events.getFirst().getVehicleId());
        assertEquals(now, events.getFirst().getTimestamp());
    }
    @Test void unchangedFeedIsNotDownloadedOrRepublished() throws Exception {
        long now = Instant.now().getEpochSecond();
        etag = "\"v1\"";
        body = FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0"))
            .addEntity(vehicle("1", "bus", now, 28.6f)).build().toByteArray();
        assertEquals(1, client.fetchVehiclePositions().size());
        long generation = snapshot.generation();

        // Same ETag: 304, nothing new to publish, positions and generation kept.
        assertTrue(client.fetchVehiclePositions().isEmpty());
        assertEquals("\"v1\"", ifNoneMatch.getLast());
        assertEquals(generation, snapshot.generation());
        assertEquals(1, snapshot.vehicles().size());
        assertNull(snapshot.feedError);

        // A new feed version is downloaded and replaces the snapshot.
        etag = "\"v2\"";
        body = FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0"))
            .addEntity(vehicle("1", "bus", now + 10, 28.61f)).build().toByteArray();
        assertEquals(1, client.fetchVehiclePositions().size());
        assertEquals(now + 10, snapshot.vehicles().get("bus").timestamp());
        assertTrue(snapshot.generation() > generation);
    }
    @Test void unchangedFeedStillReportsWhenItsPositionsHaveAged() throws Exception {
        long now = Instant.now().getEpochSecond();
        etag = "\"v1\"";
        body = FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0"))
            .addEntity(vehicle("1", "bus", now - 119, 28.6f)).build().toByteArray();
        client.fetchVehiclePositions();
        assertNull(snapshot.feedError);
        // Re-check as a re-accepted identical feed would: the only position is now too old.
        Thread.sleep(2100);
        long generation = snapshot.generation();
        assertTrue(client.fetchVehiclePositions().isEmpty());
        assertTrue(snapshot.feedError.contains("no fresh vehicle positions"));
        assertTrue(snapshot.generation() > generation, "cached answers must see the new feed state");
    }
}
