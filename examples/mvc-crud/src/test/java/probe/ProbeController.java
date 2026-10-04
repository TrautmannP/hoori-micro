package probe;

import hoori.rest.mvc.*;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/probe")
public final class ProbeController {
    private final ProbeWork work;

    public ProbeController(ProbeWork work) {
        this.work = work;
        System.out.println("probe_controller_created");
    }

    @GetMapping("/hold")
    public String hold(@RequestParam("ms") int ms) throws Exception {
        return work.waitForChild(ms, 0);
    }

    @GetMapping("/deadline")
    public String deadline() throws Exception {
        return work.waitForChild(1000, 50);
    }

    @GetMapping("/cleanup")
    public String cleanup() throws Exception {
        return work.cleanup();
    }

    @GetMapping("/broken")
    public String broken() {
        throw new IllegalStateException("secret detail");
    }

    @PostMapping("/input")
    public dev.hoori.micro.crud.dto.CreateRecipe input(
            @RequestBody @Valid dev.hoori.micro.crud.dto.CreateRecipe input) {
        System.out.println("probe_input_called");

        return input;
    }
}
