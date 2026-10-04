package dev.hoori.micro.shopping;

import hoori.micro.app.Micro;
import hoori.micro.app.MicroApplication;

@MicroApplication(name = "shopping", openApi = "openapi.json")
public final class ShoppingApplication {
    public static void main(String[] args) throws Exception {
        Micro.run(ShoppingApplication.class, args);
    }
}
