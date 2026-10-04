package hoori.micro;

/** Lowercase DNS-label names for services, permission scopes and instance IDs. */
final class ServiceName {
    private ServiceName() {}

    static String require(String value) {
        if (value == null
                || value.isEmpty()
                || value.length() > 63
                || value.charAt(0) < 'a'
                || value.charAt(0) > 'z'
                || value.charAt(value.length() - 1) == '-')
            throw new IllegalArgumentException("Expected a lowercase name (1-63 characters)");

        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);

            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-'))
                throw new IllegalArgumentException("Invalid name");
        }

        return value;
    }

    /** "recipes.get" → {"recipes", "get"}; exactly one dot. */
    static String[] qualified(String value) {
        int dot = value == null ? -1 : value.indexOf('.');

        if (dot < 0 || value.indexOf('.', dot + 1) >= 0) throw new IllegalArgumentException("Expected service.action");

        return new String[] {require(value.substring(0, dot)), require(value.substring(dot + 1))};
    }
}
