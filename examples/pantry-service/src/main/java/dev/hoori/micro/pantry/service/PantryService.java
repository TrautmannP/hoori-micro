package dev.hoori.micro.pantry.service;

import hoori.micro.app.Service;
import java.util.List;

@Service
public final class PantryService {
    public List<String> items(long id) {
        if (id == 1) return List.of("Kartoffeln", "Möhren");

        if (id == 2) return List.of();

        throw new StockNotFound();
    }

    public static final class StockNotFound extends RuntimeException {}
}
