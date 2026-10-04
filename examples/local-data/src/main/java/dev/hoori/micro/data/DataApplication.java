package dev.hoori.micro.data;

import hoori.micro.app.Micro;
import hoori.micro.app.MicroApplication;

@MicroApplication(name = "drafts")
public final class DataApplication {
    public static void main(String[] args) throws Exception {
        Micro.run(DataApplication.class, args);
    }
}
