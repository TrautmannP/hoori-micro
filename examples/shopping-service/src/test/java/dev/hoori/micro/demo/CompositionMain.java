package dev.hoori.micro.demo;

import hoori.concurrent.Cancellation;
import hoori.http.Response;
import hoori.micro.JsonTree;
import hoori.micro.Microservice;
import hoori.micro.Service;
import hoori.rest.RequestException;
import hoori.rest.Responses;
import java.util.Map;

/** Actual demo definitions with test-only provider gates; never staged in application images. */
public final class CompositionMain {
    private static volatile boolean gate, fail, credentials;
    private static int calls, active, maximum;
    private static String requestId = "none";

    public static void main(String[] args) throws Exception {
        String role = System.getenv("COMPOSITION_ROLE");
        Service definition = switch (role) {
            case "recipes" -> RecipesMain.definition();
            case "pantry" -> PantryMain.definition();
            case "shopping" -> ShoppingMain.definition();
            default -> throw new IllegalArgumentException("Unknown demo role");
        };
        try (Microservice app = Microservice.create(definition)) {
            if (!role.equals("shopping"))
                app.routes().use((request, next) -> {
                    if (!request.routeTemplate().equals("/_hoori/invoke")) return next.handle(request);

                    synchronized (CompositionMain.class) {
                        calls++;
                        active++;
                        maximum = Math.max(maximum, active);
                        requestId = app.context().invocation().requestId();
                        credentials = app.context().ownerRequest().headers.get("Authorization") != null;
                    }
                    try {
                        while (gate) {
                            Cancellation.checkpoint();
                            Thread.sleep(1);
                        }

                        if (fail) throw new RequestException(409, "Controlled provider failure");

                        return next.handle(request);
                    } finally {
                        synchronized (CompositionMain.class) {
                            active--;
                        }
                    }
                });

            app.routes().post("/gate/{mode}", request -> {
                String mode = request.pathParam("mode");

                if (!mode.equals("open") && !mode.equals("closed") && !mode.equals("fail"))
                    throw new RequestException(400, "Invalid gate mode");

                fail = mode.equals("fail");
                gate = mode.equals("closed");

                return Response.text(200, "configured");
            });
            app.routes().get("/probe", request -> {
                synchronized (CompositionMain.class) {
                    return Responses.json(
                            200,
                            Map.of(
                                    "calls",
                                    calls,
                                    "active",
                                    active,
                                    "maximum",
                                    maximum,
                                    "requestId",
                                    requestId,
                                    "credentials",
                                    credentials),
                            JsonTree.CODEC,
                            app.jsonLimits());
                }
            });

            if (role.equals("shopping"))
                app.routes()
                        .get(
                                "/local",
                                request -> Responses.json(
                                        200,
                                        ShoppingMain.overview(app.context(), new GetRecipe(1)),
                                        Overview.CODEC,
                                        app.jsonLimits()));

            app.run();
        }
    }
}
