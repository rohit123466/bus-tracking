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
    @BeforeEach void setup() throws Exception {
        status = 200; body = new byte[]{1, 2, 3};
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed", exchange -> {
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
}
