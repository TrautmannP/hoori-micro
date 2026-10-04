package dev.hoori.micro.demo;

import dev.hoori.micro.contracts.dto.*;
import dev.hoori.micro.pantry.controller.*;
import dev.hoori.micro.pantry.service.PantryService;
import dev.hoori.micro.recipes.controller.*;
import dev.hoori.micro.recipes.error.*;
import dev.hoori.micro.recipes.repository.RecipeRepository;
import dev.hoori.micro.recipes.service.RecipeService;
import dev.hoori.micro.shopping.client.*;
import dev.hoori.micro.shopping.controller.*;
import dev.hoori.micro.shopping.service.ShoppingService;
import hoori.concurrent.Cancellation;
import hoori.http.Response;
import hoori.micro.JsonTree;
import hoori.micro.Microservice;
import hoori.rest.RequestException;
import hoori.rest.Responses;
import hoori.rest.mvc.*;
import hoori.validation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.Locale;
import java.util.Map;

/** Real demo controllers with test-only gates. Application images contain none of these fixtures. */
public final class CompositionMain {
    private static volatile boolean gate, fail, credentials;
    private static int calls, active, maximum, validationCalls;
    private static String requestId = "none";
    private static long budgetMillis;

    public record Query(@Positive long id) {}

    @RestController
    public static final class ValidationController {
        private final RecipeClient recipes;
        private final ShoppingService shopping;

        public ValidationController(RecipeClient recipes, ShoppingService shopping) {
            this.recipes = recipes;
            this.shopping = shopping;
        }

        @PostMapping("/validation")
        public Query validated(@RequestBody @Valid Query input) {
            validationCalls++;

            return input;
        }

        @PostMapping("/downstream-invalid")
        public Recipe downstreamInvalid() {
            return recipes.get(0);
        }

        @GetMapping("/local")
        public Overview local() throws Exception {
            return shopping.overview(1);
        }
    }

    @RestController
    public static final class BrokenController {
        @PostMapping("/broken-validator")
        public Query broken(@RequestBody @Valid Query input) {
            validationCalls++;

            return input;
        }
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ENGLISH);
        String role = System.getenv("COMPOSITION_ROLE");
        try (Microservice app = Microservice.create(role)) {
            var errors = MvcErrors.builder().classify(app::classifyMvc);
            switch (role) {
                case "recipes" -> {
                    errors.advice(new RecipeAdviceMvc(new RecipeAdvice()));
                    app.controller(
                            new RecipeControllerMvc(
                                    new RecipeController(new RecipeService(new RecipeRepository(), app)),
                                    app.jsonLimits(),
                                    ValidationLimits.DEFAULT,
                                    errors.build()),
                            null,
                            null,
                            "recipes:write",
                            "recipes:write",
                            "recipes:read",
                            "recipes:read",
                            null);
                }
                case "pantry" -> {
                    errors.advice(new PantryAdviceMvc(new PantryAdvice()));
                    app.controller(
                            new PantryControllerMvc(
                                    new PantryController(new PantryService()),
                                    app.jsonLimits(),
                                    ValidationLimits.DEFAULT,
                                    errors.build()),
                            "pantry:read");
                }
                case "shopping" -> {
                    RecipeClient recipes = new RecipeClientHttp(app);
                    var shopping = new ShoppingService(recipes, new PantryClientHttp(app));
                    errors.advice(new ShoppingAdviceMvc(new ShoppingAdvice()));
                    var boundary = errors.build();
                    var routes = new ShoppingControllerMvc(
                            new ShoppingController(shopping), app.jsonLimits(), ValidationLimits.DEFAULT, boundary);
                    String[] permissions = new String[routes.endpoints().size()];
                    for (int i = 0; i < permissions.length; i++) {
                        var endpoint = routes.endpoints().get(i);
                        permissions[i] = endpoint.template().startsWith("/demo/")
                                ? "shopping:demo"
                                : endpoint.template().equals("/meals")
                                                        && endpoint.method().equals("POST")
                                                || endpoint.method().equals("DELETE")
                                        ? "shopping:write"
                                        : "shopping:read";
                    }
                    app.controller(routes, permissions);
                    app.controller(
                            new CompositionMain_ValidationControllerMvc(
                                    new ValidationController(recipes, shopping),
                                    app.jsonLimits(),
                                    ValidationLimits.DEFAULT,
                                    boundary),
                            "shopping:read",
                            null,
                            "shopping:read");
                    app.controller(
                            new CompositionMain_BrokenControllerMvc(
                                    new BrokenController(),
                                    app.jsonLimits(),
                                    ValidationLimits.DEFAULT,
                                    boundary,
                                    new ValidationProvider() {
                                        public <T> DtoValidator<T> forType(Class<T> type, ValidationLimits limits) {
                                            return value -> {
                                                throw new IllegalStateException("PRIVATE validator failure");
                                            };
                                        }
                                    }),
                            new String[] {null});
                }
                default -> throw new IllegalArgumentException("Unknown demo role");
            }
            app.routes().get("/validation-calls", request -> Responses.json(200, validationCalls, JsonTree.CODEC));

            if (!role.equals("shopping"))
                app.routes().use((request, next) -> {
                    if (!request.routeTemplate().startsWith("/" + role)) return next.handle(request);

                    synchronized (CompositionMain.class) {
                        calls++;
                        active++;
                        maximum = Math.max(maximum, active);
                        requestId = app.context().invocation().requestId();
                        String wire = app.context().ownerRequest().headers.get("X-Hoori-Budget-Ms");
                        budgetMillis = wire == null ? 0 : Long.parseLong(wire);
                        credentials = app.context().ownerRequest().headers.get("Authorization") != null
                                || app.context().ownerRequest().headers.get("Cookie") != null;
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
                                    credentials,
                                    "budgetMillis",
                                    budgetMillis),
                            JsonTree.CODEC,
                            app.jsonLimits());
                }
            });
            app.run();
        }
    }
}
