package hoori.micro;

/** A lowercase DNS label; this also makes environment-variable names unambiguous. */
final class ServiceName {
    private ServiceName() { }

    static String require(String value) {
        if (value == null || value.isEmpty() || value.length() > 63
                || value.charAt(0) < 'a' || value.charAt(0) > 'z'
                || value.charAt(value.length() - 1) == '-')
            throw new IllegalArgumentException("Expected a lowercase service name (1-63 characters)");
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-'))
                throw new IllegalArgumentException("Invalid service name");
        }
        return value;
    }

    static String urlKey(String name) {
        require(name);
        StringBuilder key = new StringBuilder("HOORI_SERVICE_");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            key.append(c == '-' ? '_' : c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : c);
        }
        return key.append("_URL").toString();
    }
}
