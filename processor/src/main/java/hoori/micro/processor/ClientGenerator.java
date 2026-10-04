package hoori.micro.processor;

import static hoori.rest.mvc.processor.HttpContract.literal;
import static hoori.rest.mvc.processor.HttpContract.require;

import hoori.micro.ClientRequest;
import hoori.rest.Router;
import hoori.rest.mvc.processor.HttpContract;
import hoori.rest.processor.JsonCodecs;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;

/** Client source emission from Hoori's shared HTTP/DTO analysis, without a second parser. */
final class ClientGenerator {
    private final ProcessingEnvironment env;
    final JsonCodecs codecs;
    final HttpContract contracts;

    ClientGenerator(ProcessingEnvironment env) {
        this.env = env;
        int limit = Integer.parseInt(env.getOptions().getOrDefault("hoori.mvc.listLimit", "256"));
        codecs = new JsonCodecs(env, "hoori.micro.generated.json", limit);
        contracts = new HttpContract(env, codecs, limit);
    }

    String companion(TypeElement type) {
        String pkg = env.getElementUtils().getPackageOf(type).getQualifiedName().toString();

        return pkg + "."
                + type.getQualifiedName().toString().substring(pkg.length() + 1).replace('.', '_') + "Http";
    }

    void generate(TypeElement type, String service, int version) throws IOException {
        require(
                type.getKind() == ElementKind.INTERFACE && type.getInterfaces().isEmpty(),
                type,
                "ServiceClient requires a public interface without inherited methods");
        require(
                service.matches("[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?") && version > 0 && version <= 9999,
                type,
                "ServiceClient needs a service name and version 1-9999");
        List<HttpContract.Method> methods = contracts.analyze(type);
        for (var method : ElementFilter.methodsIn(type.getEnclosedElements()))
            require(
                    !method.getModifiers().contains(Modifier.ABSTRACT)
                            || methods.stream().anyMatch(m -> m.element().equals(method)),
                    method,
                    "Every client method needs an HTTP mapping");
        String qualified = companion(type);
        String pkg = env.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        StringBuilder code = new StringBuilder("package " + pkg + ";\npublic final class "
                + qualified.substring(pkg.length() + 1) + " implements " + type.getQualifiedName() + " {\n"
                + " private final hoori.micro.RemoteClient client;\n public " + qualified.substring(pkg.length() + 1)
                + "(hoori.micro.Microservice app) { client = new hoori.micro.RemoteClient(app, " + literal(service)
                + ", " + version + "); }\n");
        for (int i = 0; i < methods.size(); i++) {
            var method = methods.get(i);
            require(
                    !method.responseKind().equals("RAW"),
                    method.element(),
                    "ServiceClient returns a DTO, List, void or typed HttpResult");
            code.append(" private static final hoori.micro.HttpEndpoint E")
                    .append(i)
                    .append(" = new hoori.micro.HttpEndpoint(")
                    .append(literal(method.verb()))
                    .append(", ")
                    .append(literal(method.path()))
                    .append(", ")
                    .append(literal(method.body() == null ? "" : "application/json"))
                    .append(", ")
                    .append(literal(method.jsonResponse() ? "application/json" : ""))
                    .append(");\n");
            code.append(" @Override public ")
                    .append(
                            method.responseKind().equals("VOID")
                                    ? "void"
                                    : codecs.typeName(method.element().getReturnType(), method.element()))
                    .append(' ')
                    .append(method.element().getSimpleName())
                    .append('(');
            for (int j = 0; j < method.parameters().size(); j++) {
                if (j != 0) code.append(", ");

                var parameter = method.parameters().get(j);
                code.append(codecs.typeName(parameter.type(), parameter.element()))
                        .append(" p")
                        .append(j);
            }
            code.append(')');

            if (!method.element().getThrownTypes().isEmpty()) {
                code.append(" throws ");
                for (int j = 0; j < method.element().getThrownTypes().size(); j++) {
                    if (j != 0) code.append(", ");

                    code.append(method.element().getThrownTypes().get(j));
                }
            }

            code.append(" {\n ")
                    .append(method.responseKind().equals("VOID") ? "" : "return ")
                    .append("client.call(E")
                    .append(i)
                    .append(", limits -> {\n  var request = new hoori.micro.ClientRequest(")
                    .append(literal(method.path()))
                    .append(");\n");
            var paths = new HashSet<String>();
            for (int j = 0; j < method.parameters().size(); j++) {
                var parameter = method.parameters().get(j);

                if (parameter.source().equals("BODY")) {
                    code.append("  request.body(p")
                            .append(j)
                            .append(", ")
                            .append(codecs.codec(parameter.type(), parameter.element()))
                            .append(".INSTANCE, limits);\n");
                    continue;
                }

                if (parameter.source().equals("PATH")) {
                    require(
                            paths.add(parameter.name()),
                            parameter.element(),
                            "Client path variable must be bound exactly once");
                    code.append("  request.path(")
                            .append(literal(parameter.name()))
                            .append(", p")
                            .append(j)
                            .append(");\n");
                    continue;
                }

                boolean header = parameter.source().equals("HEADER");

                if (header)
                    require(
                            ClientRequest.allowedHeader(parameter.name()),
                            parameter.element(),
                            "Client header is reserved or contains credentials");

                String call = "request." + (header ? "header" : "query") + "(" + literal(parameter.name()) + ", ";

                if (parameter.list()) {
                    code.append("  if (p")
                            .append(j)
                            .append(" == null || p")
                            .append(j)
                            .append(".size() > ")
                            .append(parameter.maxElements());

                    if (parameter.required()) code.append(" || p").append(j).append(".isEmpty()");

                    code.append(") throw new IllegalArgumentException(\"Client parameter bounds\");\n")
                            .append("  for (var value : p")
                            .append(j)
                            .append(") ")
                            .append(call)
                            .append("value, true);\n");
                } else
                    code.append("  ")
                            .append(call)
                            .append("p")
                            .append(j)
                            .append(", ")
                            .append(parameter.required() && parameter.defaultValue() == null)
                            .append(");\n");
            }
            var expectedPaths = new HashSet<String>();
            for (String name : Router.pathParameters(method.verb(), method.path()))
                if (name != null) expectedPaths.add(name);
            require(
                    paths.equals(expectedPaths),
                    method.element(),
                    "Every client template variable needs exactly one PathVariable binding");
            code.append("  return request;\n }, (response, limits) -> ");

            if (method.responseKind().equals("VOID")) code.append("hoori.micro.RemoteClient.empty(response)");
            else if (method.responseKind().equals("RESULT"))
                code.append("hoori.micro.RemoteClient.result(response, ")
                        .append(
                                method.result() == null
                                        ? "null"
                                        : codecs.codec(method.result(), method.element()) + ".INSTANCE")
                        .append(", limits)");
            else
                code.append("hoori.micro.RemoteClient.json(response, ")
                        .append(codecs.codec(method.result(), method.element()))
                        .append(".INSTANCE, limits)");

            code.append(");\n }\n");
        }
        code.append("}\n");
        try (var writer = env.getFiler().createSourceFile(qualified, type).openWriter()) {
            writer.write(code.toString());
        }
    }
}
