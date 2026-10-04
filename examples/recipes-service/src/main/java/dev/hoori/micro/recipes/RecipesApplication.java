package dev.hoori.micro.recipes;

import hoori.micro.app.Micro;
import hoori.micro.app.MicroApplication;

@MicroApplication(name = "recipes", openApi = "openapi.json", openApiBaseline = "openapi-v1-baseline.json")
public final class RecipesApplication {
    public static void main(String[] args) throws Exception {
        Micro.run(RecipesApplication.class, args);
    }
}
