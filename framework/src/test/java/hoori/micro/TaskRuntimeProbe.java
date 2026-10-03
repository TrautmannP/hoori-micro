package hoori.micro;

import hoori.concurrent.TaskScope;
import hoori.concurrent.Tasks;
import hoori.concurrent.http.HttpTasks;
import hoori.concurrent.http.RequestScopes;
import hoori.http.Headers;
import hoori.http.HttpClient;
import hoori.http.HttpServer;
import hoori.http.Limits;
import hoori.http.Response;
import java.net.URI;
import java.time.Duration;

/** Compiled independently against only the staged SDKs, never the Micro classes. */
public final class TaskRuntimeProbe {
    public static void main(String[] args) throws Exception {
        RequestScopes requests = new RequestScopes(4, Duration.ofSeconds(5));
        Throwable[] failure = new Throwable[1];
        try (HttpClient client = new HttpClient(Limits.DEFAULT, 2, 5000);
                HttpServer server = new HttpServer(
                        "127.0.0.1", 0, Limits.DEFAULT, requests.handler(request -> Response.text(200, "ok")))) {
            Thread serving = new Thread(() -> {
                try {
                    server.run();
                } catch (Throwable error) {
                    failure[0] = error;
                }
            });
            serving.start();
            try {
                URI target = URI.create("http://127.0.0.1:" + server.localPort() + "/probe");
                int status = TaskScope.named("sdk-probe")
                        .within(Duration.ofSeconds(5))
                        .call(scope -> Tasks.parallel(
                                        Tasks.task(() ->
                                                HttpTasks.exchange(client, target, "GET", new Headers(), new byte[0])),
                                        Tasks.task(() ->
                                                HttpTasks.exchange(client, target, "GET", new Headers(), new byte[0])))
                                .map((first, second) -> first.status + second.status));

                if (status != 400 || requests.activeRequests() != 0) throw new AssertionError("Task HTTP completion");
            } finally {
                server.stopAccepting();
                requests.close();
                server.shutdown(1000);
                serving.join();
            }

            if (failure[0] != null || client.poolStats().activeConnections != 0)
                throw new AssertionError("Task HTTP cleanup");
        }
        System.out.println("TaskRuntimeProbe passed");
    }
}
