package dev.hoori.micro.facade;

import dev.hoori.micro.demo.GetRecipe;
import dev.hoori.micro.demo.Overview;
import hoori.tasks.TaskScoped;

@TaskScoped(timeoutMillis = 800)
public interface OverviewService {
    Overview get(GetRecipe query) throws Exception;
}
