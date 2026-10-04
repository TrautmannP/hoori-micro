package hoori.micro.app;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface MicroApplication {
    String name();

    int version() default 1;

    Class<?>[] imports() default {};

    /** Classpath resource containing the authoritative public OpenAPI 3.1 JSON contract. */
    String openApi() default "";

    /** Optional previous release contract; existing operations are checked within the same major. */
    String openApiBaseline() default "";
}
