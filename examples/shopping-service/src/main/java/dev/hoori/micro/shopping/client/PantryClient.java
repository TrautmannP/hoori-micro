package dev.hoori.micro.shopping.client;

import hoori.micro.app.ServiceClient;
import hoori.rest.mvc.*;
import java.util.List;

@ServiceClient(name = "pantry", version = 1)
public interface PantryClient {
    @GetMapping("/pantry/{id}")
    List<String> items(@PathVariable("id") long id);
}
