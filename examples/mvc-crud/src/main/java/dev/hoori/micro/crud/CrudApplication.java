package dev.hoori.micro.crud;

import hoori.micro.app.Micro;
import hoori.micro.app.MicroApplication;

@MicroApplication(name = "crud", openApi = "openapi.json")
public final class CrudApplication {
    public static void main(String[] args) throws Exception {
        Micro.run(CrudApplication.class, args);
    }
}
