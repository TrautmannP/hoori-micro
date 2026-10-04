package dev.hoori.micro.pantry;

import hoori.micro.app.Micro;
import hoori.micro.app.MicroApplication;

@MicroApplication(name = "pantry")
public final class PantryApplication {
    public static void main(String[] args) throws Exception {
        Micro.run(PantryApplication.class, args);
    }
}
