package hoori.micro.app;

import hoori.micro.Environment;
import hoori.micro.Microservice;
import java.util.Locale;

/** Loads exactly the generated entry for the selected application, never a classpath bean scan. */
public final class Micro {
    private Micro() {}

    public static void run(Class<?> application, String[] args) throws Exception {
        try (Microservice service = create(application, Environment.system(), args)) {
            service.run();
        }
    }

    public static Microservice create(Class<?> application, Environment environment, String[] args) throws Exception {
        if (application == null || environment == null || args == null) throw new NullPointerException();

        Locale.setDefault(Locale.ENGLISH);
        String entry = application.getName() + "MicroModule";
        ApplicationModule module;
        try {
            module = (ApplicationModule) Class.forName(entry).getConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Missing generated Micro application module: " + entry, failure);
        }

        return module.create(environment, args.clone());
    }
}
