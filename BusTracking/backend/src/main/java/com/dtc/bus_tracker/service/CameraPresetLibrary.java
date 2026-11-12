package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.config.TicketingProperties;
import com.dtc.bus_tracker.service.StandingZoneTracker.Detection;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The preset standing-area scenarios, read once from
 * {@code ticketing.camera.presets-location} (a JSON file, so scenarios can be
 * added or changed without touching code). Each preset is a list of steps;
 * a step is one camera frame repeated {@code repeat} times:
 * <ul>
 *   <li>{@code detections} - who the camera sees in the standing area;</li>
 *   <li>{@code "signal": false} - the camera sends nothing (disconnected);</li>
 *   <li>{@code "resend": true} - the previous frame is sent again unchanged.</li>
 * </ul>
 * A preset loops when it reaches its last step.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CameraPresetLibrary {

    /** One tick of a preset. {@code detections} is null when no frame is sent. */
    public record FrameSpec(boolean signal, boolean resend, List<Detection> detections) {
    }

    public record Preset(String id, String label, String description, List<FrameSpec> frames) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DetectionJson(String track, StandingZoneTracker.Role role, Double confidence) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StepJson(Integer repeat, Boolean signal, Boolean resend, List<DetectionJson> detections) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PresetJson(String id, String label, String description, List<StepJson> steps) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FileJson(Map<String, String> defaults, List<PresetJson> presets) {
    }

    private final TicketingProperties properties;
    private final ResourceLoader resourceLoader;

    private final Map<String, Preset> presets = new LinkedHashMap<>();
    private final Map<String, String> defaults = new LinkedHashMap<>();

    @PostConstruct
    void load() {
        String location = properties.getCamera().getPresetsLocation();
        try (InputStream in = resourceLoader.getResource(location).getInputStream()) {
            load(new ObjectMapper().readValue(in, FileJson.class));
            log.info("Loaded {} standing-area presets from {}.", presets.size(), location);
        } catch (Exception e) {
            // Without presets the feed simply has nothing to play; device input still works.
            log.error("Could not load standing-area presets from {}: {}", location, e.getMessage());
        }
    }

    private void load(FileJson file) {
        if (file.presets() != null) {
            for (PresetJson p : file.presets()) {
                if (p.id() == null || p.id().isBlank() || p.steps() == null) continue;
                List<FrameSpec> frames = new ArrayList<>();
                for (StepJson step : p.steps()) {
                    FrameSpec spec = toSpec(step);
                    int repeat = step.repeat() == null ? 1 : Math.max(1, step.repeat());
                    for (int i = 0; i < repeat; i++) frames.add(spec);
                }
                if (frames.isEmpty()) continue;
                presets.put(p.id(), new Preset(p.id(), p.label() != null ? p.label() : p.id(),
                        p.description(), List.copyOf(frames)));
            }
        }
        if (file.defaults() != null) {
            file.defaults().forEach((bus, id) -> {
                if (presets.containsKey(id)) defaults.put(bus, id);
            });
        }
    }

    private static FrameSpec toSpec(StepJson step) {
        if (Boolean.FALSE.equals(step.signal())) return new FrameSpec(false, false, null);
        if (Boolean.TRUE.equals(step.resend())) return new FrameSpec(true, true, null);
        List<Detection> detections = new ArrayList<>();
        if (step.detections() != null) {
            for (DetectionJson d : step.detections()) {
                detections.add(new Detection(d.track(),
                        d.role() != null ? d.role() : StandingZoneTracker.Role.PASSENGER,
                        d.confidence() != null ? d.confidence() : 1.0));
            }
        }
        return new FrameSpec(true, false, List.copyOf(detections));
    }

    public List<Preset> all() {
        return List.copyOf(presets.values());
    }

    public Optional<Preset> find(String id) {
        return Optional.ofNullable(id).map(presets::get);
    }

    /** The preset a bus starts on: its configured default, else the first preset. */
    public Optional<Preset> defaultFor(String busNumber) {
        Preset configured = presets.get(defaults.get(busNumber));
        return configured != null ? Optional.of(configured) : presets.values().stream().findFirst();
    }
}
