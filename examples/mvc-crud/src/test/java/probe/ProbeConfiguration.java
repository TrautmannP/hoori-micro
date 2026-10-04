package probe;

import hoori.micro.Environment;
import hoori.micro.app.Bean;
import hoori.micro.app.Configuration;

@Configuration
public final class ProbeConfiguration {
    public interface First {}

    public interface Second {}

    public static final class Resource implements First, Second, AutoCloseable {
        public Resource() {
            System.out.println("probe_resource_created");
        }

        public void close() {
            System.out.println("probe_resource_closed");
        }
    }

    @Bean
    public First first() {
        return new Resource();
    }

    @Bean
    public Second second(First first) {
        return (Second) first;
    }

    @Bean
    public java.time.Duration startup(First first, Second second, Environment env) {
        if (first != second) throw new AssertionError("Alias identity lost");

        if ("true".equals(env.get("PROBE_FAIL_START"))) throw new IllegalStateException("probe_startup_failure");

        System.out.println("probe_identity_ok");

        return java.time.Duration.ZERO;
    }
}
