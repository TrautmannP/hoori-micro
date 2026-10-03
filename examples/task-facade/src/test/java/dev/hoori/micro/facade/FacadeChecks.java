package dev.hoori.micro.facade;

import dev.hoori.micro.demo.GetRecipe;
import dev.hoori.micro.demo.Overview;
import dev.hoori.micro.demo.Pantry;
import dev.hoori.micro.demo.Recipes;
import hoori.concurrent.TaskScope;
import hoori.concurrent.Tasks;
import hoori.http.Response;
import hoori.micro.Microservice;
import hoori.micro.Service;
import hoori.rest.Responses;
import java.io.IOException;
import java.time.Duration;

/** Only the Micro wiring is tested here; signature/generation matrices belong to the upstream processor. */
public final class FacadeChecks {
    public static void main(String[] args) throws Exception {
        try (Microservice app = Microservice.create(
                Service.named("facade").dependsOn("recipes", 1).dependsOn("pantry", 1))) {
            FacadeMain.routes(app);
            int[] calls = {0};
            var tasks = new RecipesClientTasks(query -> {
                calls[0]++;

                return app.context().call(Recipes.GET, query);
            });
            // No active Micro context exists here: eager broker execution would fail at startup.
            var reused = tasks.get(new GetRecipe(1));

            if (calls[0] != 0) throw new AssertionError("Facade started work while constructing a spec");

            System.out.println("facade_lazy=true");
            app.routes()
                    .get(
                            "/reused",
                            request -> Responses.json(
                                    200,
                                    Tasks.parallel(reused, app.context().task(Pantry.FOR_RECIPE, new GetRecipe(1)))
                                            .map(Overview::new),
                                    Overview.CODEC,
                                    app.jsonLimits()));
            OverviewService overview = FacadeMain.overview(app);
            app.routes().post("/short", request -> {
                GetRecipe query = request.body(GetRecipe.CODEC, app.jsonLimits());

                return TaskScope.named("short-parent")
                        .within(Duration.ofMillis(250))
                        .call(scope -> Responses.json(200, overview.get(query), Overview.CODEC, app.jsonLimits()));
            });
            app.routes().get("/checked", request -> {
                IOException expected = new IOException("private detail");
                int[] cleaned = {0};
                OverviewService failing = new OverviewServiceScoped(query -> {
                    try {
                        throw expected;
                    } finally {
                        cleaned[0]++;
                    }
                });
                try {
                    failing.get(new GetRecipe(1));
                    throw new AssertionError("Checked failure disappeared");
                } catch (IOException actual) {
                    if (actual != expected || cleaned[0] != 1) throw new AssertionError("Failure/cleanup contract");
                }

                return Response.text(200, "checked failure and cleanup preserved");
            });
            app.run();
        }
    }
}
