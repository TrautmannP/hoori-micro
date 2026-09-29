package dev.hoori.micro.demo;

import hoori.micro.JsonTree;
import hoori.micro.Microservice;
import hoori.micro.Service;
import hoori.micro.ServiceCallException;
import hoori.rest.RequestException;
import java.util.Map;

/**
 * Calls recipes by action name only; knows no route, host, port or instance. Not a migrated domain.
 */
public final class ShoppingMain {
    public static void main(String[] args) throws Exception {
        Service shopping = Service.named("shopping")
                .version(1)
                .dependsOn("recipes", 1)
                .action("meal", GetRecipe.CODEC, RecipeCodec.INSTANCE, (ctx, input) -> {
                    try {
                        return ctx.call(Recipes.GET, input);
                    } catch (ServiceCallException failed) {
                        // The application decides whether an upstream status has the same domain meaning.
                        if (failed.upstreamStatus() == 404) throw new RequestException(404, "Recipe not found");

                        throw failed;
                    }
                })
                .http("GET", "/meals/{id}")
                .requirePermission("shopping:read")
                // Generic variant without a typed contract; the result is a JsonTree value.
                .action(
                        "context",
                        JsonTree.CODEC,
                        JsonTree.CODEC,
                        (ctx, input) -> ctx.call("recipes.context", Map.of()))
                .http("GET", "/demo/context")
                .requirePermission("shopping:demo")
                .action("slow", GetRecipe.CODEC, RecipeCodec.INSTANCE, (ctx, input) -> ctx.call(Recipes.SLOW, input))
                .http("GET", "/demo/slow/{id}")
                .requirePermission("shopping:demo");

        try (Microservice app = Microservice.create(shopping)) {
            app.run();
        }
    }
}
