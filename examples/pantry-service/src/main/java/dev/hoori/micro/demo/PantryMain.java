package dev.hoori.micro.demo;

import hoori.micro.Microservice;
import hoori.micro.Service;
import hoori.rest.RequestException;
import java.util.List;

/** Independent, read-only demonstration. No household identity or persistence. */
public final class PantryMain {
    static Service definition() {
        return Service.named("pantry")
                .action(Pantry.FOR_RECIPE, (ctx, input) -> {
                    if (input.id() == 1) return List.of("Kartoffeln", "Möhren");

                    if (input.id() == 2) return List.of();

                    throw new RequestException(404, "Recipe stock not found");
                })
                .http("items", "GET", "/pantry/{id}")
                .requirePermission("items", "pantry:read");
    }

    public static void main(String[] args) throws Exception {
        try (Microservice app = Microservice.create(definition())) {
            app.run();
        }
    }
}
