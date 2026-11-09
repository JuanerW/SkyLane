package com.skylane.recommendation;

import com.skylane.common.ApiException;
import com.skylane.quote.CurrentUserProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@Profile("!memory")
@RequestMapping("/api/v1/quotes")
public class RecommendationController {
    private final RecommendationService service;
    private final CurrentUserProvider user;
    public RecommendationController(RecommendationService service, CurrentUserProvider user) { this.service = service; this.user = user; }
    @PostMapping("/{id}/recommendation")
    @ResponseStatus(HttpStatus.CREATED)
    public RecommendationService.Response recommend(@PathVariable String id) {
        UUID quoteId;
        try { quoteId = UUID.fromString(id); }
        catch (IllegalArgumentException e) { throw new ApiException(HttpStatus.NOT_FOUND, "QUOTE_NOT_FOUND", "Quote not found"); }
        return service.recommend(user.currentUserId(), quoteId);
    }
}
