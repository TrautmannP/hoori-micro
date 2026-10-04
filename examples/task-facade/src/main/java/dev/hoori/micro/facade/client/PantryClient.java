package dev.hoori.micro.facade.client;

import hoori.micro.app.ServiceClient;
import hoori.rest.mvc.GetMapping;
import hoori.rest.mvc.PathVariable;
import hoori.tasks.GenerateTasks;
import java.util.List;

@GenerateTasks
@ServiceClient(name = "pantry")
public interface PantryClient {
    @GetMapping("/pantry/{id}")
    List<String> items(@PathVariable("id") long id);
}
