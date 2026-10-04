package probe;

import hoori.rest.mvc.*;

@RestControllerAdvice
public final class ProbeAdvice {
    @ExceptionHandler(Exception.class)
    public Problem catchAll(Exception failure) {
        return new Problem(418, "must_not_mask_framework");
    }
}
