package com.skylane.tracking;

import com.skylane.quote.CurrentUserProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@Profile("!memory")
public class TrackingController {
    private final TrackingService tracking;
    private final CurrentUserProvider currentUser;

    public TrackingController(TrackingService tracking, CurrentUserProvider currentUser) {
        this.tracking = tracking;
        this.currentUser = currentUser;
    }

    @GetMapping(path = "/api/v1/orders/{id}/track", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter track(@PathVariable String id,
                            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        UUID orderId;
        try {
            orderId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new com.skylane.common.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "ORDER_NOT_FOUND", "Order not found");
        }
        return tracking.open(currentUser.currentUserId(), orderId, lastEventId);
    }
}
