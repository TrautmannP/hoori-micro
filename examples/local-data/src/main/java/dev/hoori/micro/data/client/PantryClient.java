package dev.hoori.micro.data.client;

import hoori.micro.app.ServiceClient;
import hoori.rest.mvc.GetMapping;
import hoori.rest.mvc.PathVariable;
import java.util.List;

@ServiceClient(name = "pantry")
public interface PantryClient {
    @GetMapping("/pantry/{id}")
    List<String> items(@PathVariable("id") long id);
}
