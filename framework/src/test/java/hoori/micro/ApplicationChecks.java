package hoori.micro;

/** Native ownership checks for generated graph registration, including rejected resources. */
public final class ApplicationChecks {
    public static void main(String[] args) throws Exception {
        java.util.List<Integer> closed = new java.util.ArrayList<>();
        try (Microservice app = Microservice.create(Service.named("app-check"))) {
            AutoCloseable shared = () -> closed.add(0);
            app.ownBean(shared);
            app.ownBean(shared);
            for (int i = 1; i < 16; i++) {
                final int slot = i;
                app.ownBean((AutoCloseable) () -> closed.add(slot));
            }
            try {
                app.ownBean((AutoCloseable) () -> closed.add(16));
                throw new AssertionError("Resource capacity was not enforced");
            } catch (IllegalStateException expected) {
                if (closed.size() != 1 || closed.get(0) != 16) throw new AssertionError("Rejected bean leaked");
            }
        }

        if (closed.size() != 17) throw new AssertionError("Resources did not close once: " + closed);

        for (int i = 0; i < 17; i++)
            if (closed.get(i) != 16 - i) throw new AssertionError("Wrong close order: " + closed);

        System.out.println("Application ownership: alias, capacity rollback and reverse close passed");
    }
}
