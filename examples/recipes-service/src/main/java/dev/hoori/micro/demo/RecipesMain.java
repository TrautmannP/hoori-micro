package dev.hoori.micro.demo;

import hoori.micro.Microservice;
import hoori.http.Response;
import hoori.rest.RequestException;
import hoori.rest.Responses;
import java.io.InterruptedIOException;

/** Stateless, read-only fixture, not the migrated Dahemm recipes backend. */
public final class RecipesMain {
    public static void main(String[] args) throws Exception {
        try (Microservice app = Microservice.create("recipes")) {
            app.routes().get("/v1/recipes/{id}", request -> {
                long id;
                try { id = Long.parseLong(request.pathParam("id")); }
                catch (NumberFormatException invalid) { throw new RequestException(400, "Invalid recipe ID"); }
                if (id != 1) return Response.text(404, "Recipe not found");
                return Responses.json(200, new Recipe(1, "Kartoffelsuppe"), RecipeCodec.INSTANCE, app.jsonLimits());
            });
            app.routes().get("/demo/context", request -> {
                if (request.raw().headers.get("Authorization") != null)
                    return Response.text(400, "Unexpected authorization forwarding");
                return Response.text(200, request.id());
            });
            app.routes().get("/demo/slow", request -> {
                System.out.println("demo_slow_started id=" + request.id());
                try { Thread.sleep(3000); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Demo interrupted");
                }
                return Responses.json(200, new Recipe(1, "Kartoffelsuppe"), RecipeCodec.INSTANCE, app.jsonLimits());
            });
            app.run();
        }
    }
}
