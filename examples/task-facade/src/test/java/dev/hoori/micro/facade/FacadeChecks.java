package dev.hoori.micro.facade;

import dev.hoori.micro.contracts.dto.Overview;
import dev.hoori.micro.facade.client.*;
import dev.hoori.micro.facade.controller.*;
import dev.hoori.micro.facade.service.*;
import hoori.concurrent.TaskScope;
import hoori.concurrent.TaskSpec;
import hoori.concurrent.Tasks;
import hoori.micro.Microservice;
import hoori.rest.mvc.*;
import hoori.validation.ValidationLimits;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;

/** SDK delegate probes use the same controller/business classes as the generated application graph. */
public final class FacadeChecks {
    @RestController
    public static final class Checks {
        private final OverviewService overview;
        private final TaskSpec<dev.hoori.micro.contracts.dto.Recipe> reused;
        private final PantryClientTasks pantry;

        public Checks(OverviewService overview, RecipeClientTasks recipes, PantryClientTasks pantry) {
            this.overview = overview;
            this.reused = recipes.get(1);
            this.pantry = pantry;
        }

        @GetMapping("/reused")
        public Overview reused() throws Exception {
            return Tasks.parallel(reused, pantry.items(1)).map(Overview::new);
        }

        @GetMapping("/short/{id}")
        public Overview shorter(@PathVariable("id") @Positive long id) throws Exception {
            return TaskScope.named("short-parent")
                    .within(Duration.ofMillis(250))
                    .call(scope -> overview.get(id));
        }

        @GetMapping("/checked")
        public String checked() throws Exception {
            IOException expected = new IOException("private detail");
            int[] cleaned = {0};
            OverviewService failing = new OverviewServiceScoped(id -> {
                try {
                    throw expected;
                } finally {
                    cleaned[0]++;
                }
            });
            try {
                failing.get(1);
                throw new AssertionError("Checked failure disappeared");
            } catch (IOException actual) {
                if (actual != expected || cleaned[0] != 1) throw new AssertionError("Failure/cleanup contract");
            }

            return "checked failure and cleanup preserved";
        }
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ENGLISH);
        try (Microservice app = Microservice.create("facade")) {
            var client = new RecipeClientHttp(app);
            int[] calls = {0};
            var recipes = new RecipeClientTasks(id -> {
                calls[0]++;

                return client.get(id);
            });
            var pantry = new PantryClientTasks(new PantryClientHttp(app));
            var overview = new OverviewServiceScoped(new OverviewComposition(recipes, pantry));
            var errors = MvcErrors.builder().classify(app::classifyMvc).build();
            app.controller(
                    new OverviewControllerMvc(
                            new OverviewController(overview), app.jsonLimits(), ValidationLimits.DEFAULT, errors),
                    new String[] {null});
            app.controller(
                    new FacadeChecks_ChecksMvc(
                            new Checks(overview, recipes, pantry), app.jsonLimits(), ValidationLimits.DEFAULT, errors),
                    new String[] {null, null, null});

            if (calls[0] != 0) throw new AssertionError("Facade started work while constructing a spec");

            System.out.println("facade_lazy=true");
            app.run();
        }
    }
}
