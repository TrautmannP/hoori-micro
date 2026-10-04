package hoori.micro.app;

import hoori.micro.Environment;
import hoori.micro.Microservice;

/** One generated entry per application; used only during bootstrap. */
public interface ApplicationModule {
    Microservice create(Environment environment, String[] args) throws Exception;
}
