package dev.hoori.micro.facade;

import hoori.micro.app.Micro;
import hoori.micro.app.MicroApplication;

@MicroApplication(name = "facade", openApi = "openapi.json")
public final class FacadeApplication {
    public static void main(String[] args) throws Exception {
        Micro.run(FacadeApplication.class, args);
    }
}
