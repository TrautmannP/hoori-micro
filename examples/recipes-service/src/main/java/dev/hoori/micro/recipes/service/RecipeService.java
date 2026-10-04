package dev.hoori.micro.recipes.service;

import dev.hoori.micro.contracts.dto.*;
import dev.hoori.micro.recipes.repository.RecipeRepository;
import hoori.micro.Microservice;
import hoori.micro.app.Service;
import java.util.*;

@Service
public final class RecipeService {
    private final RecipeRepository repository;
    private final Microservice app;

    public RecipeService(RecipeRepository repository, Microservice app) {
        this.repository = repository;
        this.app = app;
    }

    public Recipe get(long id) {
        return repository.get(id);
    }

    public Recipe create(CreateRecipe input) {
        return repository.create(input.title());
    }

    public List<Recipe> list(String prefix) {
        return repository.list(prefix);
    }

    public void delete(long id) {
        repository.delete(id);
    }

    public List<Recipe> bulk(RecipeIds input) {
        List<Recipe> result = new ArrayList<>();
        for (long id : input.ids()) result.add(get(id));

        return List.copyOf(result);
    }

    public Recipe slow(long id) throws Exception {
        System.out.println("demo_slow_started id=" + app.context().invocation().requestId());
        Thread.sleep(3000);

        return get(id);
    }

    public RequestInfo context() {
        var context = app.context();

        return new RequestInfo(
                context.invocation().requestId(),
                context.ownerRequest().headers.get("Authorization") != null
                        || context.ownerRequest().headers.get("Cookie") != null);
    }
}
