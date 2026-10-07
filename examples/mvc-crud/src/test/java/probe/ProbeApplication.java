package probe;

import hoori.micro.app.Micro;
import hoori.micro.app.MicroApplication;

@MicroApplication(name = "probe", openApi = "probe-openapi.json")
public final class ProbeApplication {
    public static void main(String[] args) throws Exception {
        Micro.run(ProbeApplication.class, args);
    }
}
