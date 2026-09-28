package hoori.micro;

/** Injectable one-key lookup. Hoori's map-returning getenv() overload is deliberately not used. */
@FunctionalInterface
public interface Environment {
    String get(String name);

    static Environment system() { return System::getenv; }
}
