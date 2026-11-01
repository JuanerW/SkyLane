package com.skylane.quote;

import com.skylane.common.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/quotes")
public class QuoteController {
    private final QuoteService service;
    private final CurrentUserProvider currentUser;
    private final Optional<QuoteTablePrinter> tablePrinter;

    public QuoteController(QuoteService service, CurrentUserProvider currentUser, Optional<QuoteTablePrinter> tablePrinter) {
        this.service = service;
        this.currentUser = currentUser;
        this.tablePrinter = tablePrinter;
    }

    /** Dev aid: POST /api/v1/quotes?format=table answers with a plain-text table instead of JSON. */
    @PostMapping(params = "format=table", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public String createAsTable(@Valid @RequestBody QuoteRequest request) {
        if (tablePrinter.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Table view is a dev aid: set skylane.dev.print-quote-table=true");
        }
        return QuoteTablePrinter.format(service.createQuote(currentUser.currentUserId(), request));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuoteResponse create(@Valid @RequestBody QuoteRequest request) {
        QuoteResponse quote = service.createQuote(currentUser.currentUserId(), request);
        tablePrinter.ifPresent(p -> p.print(quote));
        return quote;
    }
}
