package probe;

import hoori.concurrent.TaskScope;
import hoori.micro.Microservice;
import hoori.micro.app.Service;
import java.time.Duration;

@Service
public final class ProbeWork {
    private final Microservice app;

    public ProbeWork(Microservice app, Duration startup) {
        this.app = app;
        System.out.println("probe_work_created");
    }

    public String waitForChild(int millis, int deadline) throws Exception {
        System.out.println("probe_handler_started");
        var operation = TaskScope.named("mvc-child");

        if (deadline > 0) operation.within(Duration.ofMillis(deadline));

        return operation.call(scope -> {
            String id = app.context().invocation().requestId();
            scope.fork(() -> {
                if (!id.equals(app.context().invocation().requestId()))
                    throw new AssertionError("Child lost correlation");

                try {
                    app.context().ownerRequest();
                    throw new AssertionError("Child owns raw request");
                } catch (IllegalStateException expected) {
                }
                try {
                    Thread.sleep(millis);
                } finally {
                    System.out.println("probe_child_finished");
                }

                return null;
            });
            System.out.println("probe_body_returned");

            return id;
        });
    }

    public String cleanup() throws Exception {
        return TaskScope.named("mvc-cleanup").call(scope -> {
            scope.own(() -> {
                throw new IllegalStateException("secret cleanup");
            });

            return "must-not-return";
        });
    }
}
