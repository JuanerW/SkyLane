package com.skylane.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skylane.auth.JwtAuthFilter;
import com.skylane.auth.JwtAuthFilter.Actor;
import com.skylane.common.ApiException;
import com.skylane.ops.OpsDtos.Booking;
import com.skylane.ops.OpsDtos.CreateDevice;
import com.skylane.ops.OpsDtos.DeviceRow;
import com.skylane.ops.OpsDtos.Metrics;
import com.skylane.ops.OpsDtos.OpsOrder;
import com.skylane.ops.OpsDtos.Overview;
import com.skylane.ops.OpsDtos.PatchDevice;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Design 8.1: /api/v1/ops/**, operator or admin (AppConfig returns 403 FORBIDDEN to customers). */
@RestController
@RequestMapping("/api/v1/ops")
@Profile("!memory")
public class OpsController {
    private static final long LIVE_TIMEOUT_MS = 30 * 60_000L;

    private final OpsService ops;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ops-live");
        t.setDaemon(true);
        return t;
    });

    public OpsController(OpsService ops, ObjectMapper mapper) {
        this.ops = ops;
        this.mapper = mapper;
    }

    @PreDestroy
    void stop() {
        ticker.shutdownNow();
    }

    static Actor actor() {
        Actor a = JwtAuthFilter.currentActorOrNull();
        if (a == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Login required");
        }
        return a;
    }

    @GetMapping("/overview")
    public Overview overview() {
        return ops.overview(actor());
    }

    /** D32: one stream, a `devices` event every 2 seconds with every visible device. */
    @GetMapping(path = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter live() {
        Actor actor = actor();
        SseEmitter emitter = new SseEmitter(LIVE_TIMEOUT_MS);
        ScheduledFuture<?>[] task = new ScheduledFuture<?>[1];
        Runnable cancel = () -> {
            if (task[0] != null) task[0].cancel(false);
        };
        task[0] = ticker.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().name("devices")
                        .data(mapper.writeValueAsString(ops.liveDevices(actor)), MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                // Client went away or the query failed: stop this stream only.
                cancel.run();
                emitter.completeWithError(e);
            }
        }, 0, 2, TimeUnit.SECONDS);
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(e -> cancel.run());
        return emitter;
    }

    @GetMapping("/devices")
    public List<DeviceRow> devices() {
        return ops.devices(actor());
    }

    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceRow createDevice(@Valid @RequestBody CreateDevice req) {
        return ops.createDevice(actor(), req);
    }

    @PatchMapping("/devices/{id}")
    public DeviceRow patchDevice(@PathVariable UUID id, @Valid @RequestBody PatchDevice req) {
        return ops.patchDevice(actor(), id, req);
    }

    @GetMapping("/devices/{id}/schedule")
    public List<Booking> schedule(@PathVariable UUID id) {
        return ops.schedule(actor(), id);
    }

    @GetMapping("/orders")
    public List<OpsOrder> orders(@RequestParam(required = false) String status,
                                 @RequestParam(required = false) Instant from,
                                 @RequestParam(required = false) Instant to) {
        return ops.orders(actor(), status, from, to);
    }

    @GetMapping("/metrics")
    public Metrics metrics(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return ops.metrics(actor(), from, to);
    }
}
