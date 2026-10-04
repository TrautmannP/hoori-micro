package dev.hoori.micro.facade.service;

import dev.hoori.micro.contracts.dto.Overview;
import hoori.tasks.TaskScoped;

@TaskScoped(timeoutMillis = 800)
public interface OverviewService {
    Overview get(long id) throws Exception;
}
