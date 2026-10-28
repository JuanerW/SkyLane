package com.skylane.quote;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** No-database stand-in (profile 'memory'): quotes live until the app restarts. */
@Repository
@Profile("memory")
public class InMemoryQuoteRepository implements QuoteRepository {
    private final Map<UUID, NewQuote> quotes = new ConcurrentHashMap<>();

    @Override
    public void save(NewQuote quote) {
        quotes.put(quote.id(), quote);
    }

    public Optional<NewQuote> find(UUID id) {
        return Optional.ofNullable(quotes.get(id));
    }
}
