package probe;

import hoori.micro.app.GatewayRoute;
import hoori.rest.codegen.JsonField;
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

    @GetMapping("/openapi/defaults")
    @GatewayRoute(permission = "probe:read")
    public String defaults(
            @RequestParam(value = "limit", defaultValue = "20") Integer limit,
            @RequestParam(value = "text", defaultValue = "") String text) {
        return limit + ":" + text;
    }

    @GetMapping("/openapi/range")
    @GatewayRoute(permission = "probe:read")
    public int range(@RequestParam("byte") byte level, @RequestParam("short") Short amount) {
        return level + amount;
    }

    @GetMapping("/openapi/literal")
    @GatewayRoute(permission = "probe:read")
    public Literal literal() {
        return new Literal("literal application data");
    }

    public record Literal(@JsonField(name = "$ref") String reference) {}
}
