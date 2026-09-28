package dev.hoori.micro.demo;

import hoori.micro.Microservice;
import hoori.micro.ServiceCallException;
import hoori.http.Response;
import hoori.http.Headers;

import java.nio.charset.StandardCharsets;

import hoori.rest.RequestException;
import hoori.rest.Responses;

/**
 * Demonstrates an explicit service client, not a gateway or a migrated shopping domain.
 */
public final class ShoppingMain {
    public static void main(String[] args) throws Exception {
        try (Microservice app = Microservice.create("shopping", "recipes")) {

            app.routes().get("/demo/meal/{id}", request -> {
                long id;
                try {
                    id = Long.parseLong(request.pathParam("id"));
                } catch (NumberFormatException invalid) {
                    throw new RequestException(400, "Invalid recipe ID");
                }

                if (id < 1) throw new RequestException(400, "Invalid recipe ID");

                try {
                    Recipe recipe = app.client().getJson(request.raw(), "recipes",
                        "/v1/recipes/" + id, RecipeCodec.INSTANCE);
                    return Responses.json(200, recipe, RecipeCodec.INSTANCE, app.jsonLimits());
                } catch (ServiceCallException failed) {
                    // The application decides whether an upstream status has the same domain meaning.
                    if (failed.upstreamStatus() == 404) {
                        return Response.text(404, "Recipe not found");
                    }
                    
                    throw failed;
                }

            });

            app.routes().get("/demo/context", request -> {
                Response observed = app
                    .client()
                    .exchange(request.raw(), "recipes", "GET", "/demo/context", new Headers(), new byte[0]);

                if (observed.status != 200) {
                    return Response.text(502, "Context probe failed");
                }

                return Response.text(200, new String(observed.body, StandardCharsets.UTF_8));
            });

            app.routes().get("/demo/slow", request -> Responses.json(200,
                app.client().getJson(request.raw(), "recipes", "/demo/slow", RecipeCodec.INSTANCE),
                RecipeCodec.INSTANCE, app.jsonLimits()));

            app.run();
        }
    }
}
