package hoori.micro.processor;

import static hoori.rest.mvc.processor.HttpContract.literal;
import static hoori.rest.mvc.processor.HttpContract.require;

import hoori.rest.mvc.processor.HttpContract;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

/** Finite source-module constructor graph. HTTP analysis and DTO generation belong to Hoori. */
@SupportedAnnotationTypes("hoori.micro.app.*")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@javax.annotation.processing.SupportedOptions("hoori.openapi.baseline")
public final class ApplicationProcessor extends AbstractProcessor {
    private static final String API = "hoori.micro.app.";
    private static final Set<String> COMPONENTS = Set.of(
            API + "Service",
            API + "Repository",
            API + "Configuration",
            API + "ServiceClient",
            "hoori.rest.mvc.RestController",
            "hoori.rest.mvc.RestControllerAdvice");
    private final Set<String> emitted = new HashSet<>();
    private final Map<String, TypeElement> sources = new LinkedHashMap<>();
    private String application;
    private ClientGenerator clients;
    private final Set<String> generatedClients = new HashSet<>();
    private final Map<String, String> pending = new LinkedHashMap<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver()) {
            for (var entry : pending.entrySet()) error(sources.get(entry.getKey()), entry.getValue());

            return false;
        }

        for (Element root : round.getRootElements()) collect(root);

        if (clients == null) clients = new ClientGenerator(processingEnv);

        for (TypeElement type : sources.values()) {
            for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements()))
                if (annotation(method, API + "GatewayRoute") != null
                        && annotation(type, "hoori.rest.mvc.RestController") == null)
                    error(method, "GatewayRoute requires a RestController HTTP method");
            AnnotationMirror client = annotation(type, API + "ServiceClient");

            if (client == null || !generatedClients.add(type.getQualifiedName().toString())) continue;

            try {
                clients.generate(type, (String) value(client, "name"), (Integer) value(client, "version"));
            } catch (HttpContract.Invalid invalid) {
                error(invalid.element, invalid.getMessage());
            } catch (hoori.rest.processor.JsonCodecs.InvalidMapping invalid) {
                error(invalid.element, invalid.getMessage());
            } catch (IOException failed) {
                error(type, "Cannot generate client: " + failed.getMessage());
            }
        }

        for (TypeElement root : new ArrayList<>(sources.values())) {
            boolean app = annotation(root, API + "MicroApplication") != null;
            boolean module = annotation(root, API + "MicroModule") != null;

            String rootName = root.getQualifiedName().toString();

            if ((!app && !module) || emitted.contains(rootName)) continue;

            try {
                require(!app || !module, root, "Choose MicroApplication or MicroModule, not both");
                require(
                        root.getEnclosingElement().getKind() == ElementKind.PACKAGE && HttpContract.visible(root),
                        root,
                        "Application/module must be a public top-level class in a named package");
                String pkg = processingEnv
                        .getElementUtils()
                        .getPackageOf(root)
                        .getQualifiedName()
                        .toString();
                require(!pkg.isEmpty(), root, "Application/module needs a named base package");
                List<TypeElement> components = new ArrayList<>();
                for (TypeElement type : sources.values()) {
                    String name = type.getQualifiedName().toString();

                    if (name.startsWith(pkg + ".") && component(type)) components.add(type);
                }
                for (TypeElement type : sources.values()) {
                    if (!type.getQualifiedName().toString().startsWith(pkg + ".")
                            || annotation(type, "hoori.tasks.GenerateTasks") == null) continue;

                    String name = type.getQualifiedName() + "Tasks";
                    TypeElement facade = processingEnv.getElementUtils().getTypeElement(name);

                    if (facade == null)
                        throw new Pending("Missing " + name + "; enable the original Hoori TasksProcessor");

                    components.add(facade);
                }

                if (app) {
                    require(
                            application == null || application.equals(rootName),
                            root,
                            "Duplicate MicroApplication in this module: " + application);
                    application = root.getQualifiedName().toString();
                    imports(root, components);
                    generate(root, components);
                } else metadata(root, components);

                emitted.add(rootName);
                pending.remove(rootName);
            } catch (Pending deferred) {
                pending.put(rootName, deferred.getMessage());
            } catch (HttpContract.Invalid invalid) {
                emitted.add(rootName);
                pending.remove(rootName);
                error(invalid.element, invalid.getMessage());
            } catch (IOException failure) {
                error(root, "Cannot generate application: " + failure.getMessage());
            }
        }

        return false;
    }

    private void collect(Element element) {
        if (!(element instanceof TypeElement type)) return;

        sources.put(type.getQualifiedName().toString(), type);
        for (Element child : type.getEnclosedElements()) if (child instanceof TypeElement) collect(child);
    }

    private boolean component(TypeElement type) {
        return type.getAnnotationMirrors().stream()
                .anyMatch(a -> COMPONENTS.contains(a.getAnnotationType().toString()));
    }

    private void imports(TypeElement app, List<TypeElement> components) {
        AnnotationMirror declaration = annotation(app, API + "MicroApplication");
        for (AnnotationValue value : array(declaration, "imports")) {
            TypeElement module = (TypeElement) processingEnv.getTypeUtils().asElement((TypeMirror) value.getValue());
            String name = module.getQualifiedName().toString();
            TypeElement metadata = processingEnv.getElementUtils().getTypeElement(name + "MicroMetadata");

            if (metadata == null && annotation(module, API + "MicroModule") != null)
                throw new Pending("Missing build metadata for imported MicroModule " + name);

            require(metadata != null, app, "Missing build metadata for imported MicroModule " + name);
            AnnotationMirror types = annotation(metadata, API + "ComponentTypes");
            require(types != null, app, "Invalid MicroModule metadata: " + name);
            for (AnnotationValue entry : array(types, "value")) {
                TypeElement type = (TypeElement) processingEnv.getTypeUtils().asElement((TypeMirror) entry.getValue());
                require(!components.contains(type), app, "Duplicate imported component: " + type);
                components.add(type);
            }
        }
    }

    private void metadata(TypeElement root, List<TypeElement> components) throws IOException {
        String qualified = root.getQualifiedName() + "MicroMetadata";
        String pkg = processingEnv
                .getElementUtils()
                .getPackageOf(root)
                .getQualifiedName()
                .toString();
        StringBuilder code = new StringBuilder("package " + pkg + ";\n@hoori.micro.app.ComponentTypes({");
        for (int i = 0; i < components.size(); i++) {
            if (i != 0) code.append(',');

            code.append(components.get(i).getQualifiedName()).append(".class");
        }
        code.append("})\npublic final class ").append(root.getSimpleName()).append("MicroMetadata {}\n");
        write(qualified, root, code.toString());
    }

    private void generate(TypeElement app, List<TypeElement> components) throws IOException {
        AnnotationMirror declaration = annotation(app, API + "MicroApplication");
        String name = (String) value(declaration, "name");
        int version = (Integer) value(declaration, "version");
        require(
                name.matches("[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?"),
                app,
                "Application name must be a lowercase service name");
        require(version > 0 && version <= 9999, app, "Application version must be 1-9999");
        require(components.size() <= 128, app, "Application component limit is 128");
        components.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));
        var nodes = new ArrayList<Node>();
        for (TypeElement type : components) {
            require(
                    HttpContract.visible(type) && type.getTypeParameters().isEmpty(),
                    type,
                    "Component must be public and non-generic");

            if (annotation(type, API + "ServiceClient") != null) {
                nodes.add(new Node(type.asType(), type, null, null, false, nodes.size()));
                continue;
            }

            require(
                    type.getKind() == ElementKind.CLASS && !type.getModifiers().contains(Modifier.ABSTRACT),
                    type,
                    "Component must be a concrete class");
            require(
                    type.getNestingKind().isNested() == false
                            || type.getModifiers().contains(Modifier.STATIC),
                    type,
                    "Nested component must be static");
            List<ExecutableElement> constructors = ElementFilter.constructorsIn(type.getEnclosedElements());
            require(
                    constructors.size() == 1
                            && constructors.get(0).getModifiers().contains(Modifier.PUBLIC),
                    type,
                    "Component needs exactly one public constructor");
            Node owner = new Node(type.asType(), type, constructors.get(0), null, true, nodes.size());
            nodes.add(owner);

            if (annotation(type, API + "Configuration") == null) continue;

            for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements())) {
                AnnotationMirror bean = annotation(method, API + "Bean");

                if (bean == null) continue;

                require(
                        method.getModifiers().contains(Modifier.PUBLIC)
                                && method.getTypeParameters().isEmpty()
                                && !method.isVarArgs()
                                && !method.getReturnType().getKind().isPrimitive()
                                && method.getReturnType().getKind() != javax.lang.model.type.TypeKind.VOID,
                        method,
                        "Bean factory must be public, non-generic and return an object");
                nodes.add(new Node(
                        method.getReturnType(), method, method, owner, (Boolean) value(bean, "owned"), nodes.size()));
            }
        }
        // Decorate only interface injection points using the SDK's real generated delegates.
        // Concrete/self calls remain ordinary Java calls; no runtime interception is introduced.
        var scoped = new HashSet<String>();
        for (Node node : new ArrayList<>(nodes)) {
            if (node.call == null) continue;

            for (var parameter : node.call.getParameters()) {
                Element element = processingEnv.getTypeUtils().asElement(parameter.asType());

                if (!(element instanceof TypeElement type)
                        || type.getKind() != ElementKind.INTERFACE
                        || !policy(type, "hoori.tasks.TaskScoped") && !policy(type, "hoori.tasks.Transactional")
                        || !scoped.add(type.getQualifiedName().toString())) continue;

                require(type.getTypeParameters().isEmpty(), type, "Micro scoped interfaces must be non-generic");
                List<Node> delegates = nodes.stream()
                        .filter(n -> n.delegate == null
                                && processingEnv.getTypeUtils().isAssignable(n.type, type.asType()))
                        .toList();
                require(delegates.size() == 1, parameter, "Scoped interface needs exactly one delegate: " + type);
                String generatedName = type.getQualifiedName() + "Scoped";
                TypeElement generated = processingEnv.getElementUtils().getTypeElement(generatedName);

                if (generated == null)
                    throw new Pending("Missing " + generatedName + "; enable the original Hoori TasksProcessor");

                Node wrapper = new Node(
                        type.asType(),
                        generated,
                        ElementFilter.constructorsIn(generated.getEnclosedElements())
                                .get(0),
                        null,
                        false,
                        nodes.size());
                wrapper.delegate = delegates.get(0);

                if (policy(type, "hoori.tasks.Transactional")) {
                    TypeElement manager =
                            processingEnv.getElementUtils().getTypeElement("hoori.transaction.TransactionManager");
                    require(manager != null, type, "Transactional interface needs hoori-transaction-api");
                    List<Node> managers = nodes.stream()
                            .filter(n -> processingEnv
                                    .getTypeUtils()
                                    .isAssignable(
                                            processingEnv.getTypeUtils().erasure(n.type),
                                            processingEnv.getTypeUtils().erasure(manager.asType())))
                            .toList();
                    require(
                            managers.size() == 1,
                            type,
                            "Transactional interface needs exactly one TransactionManager bean");
                    wrapper.manager = managers.get(0);
                }

                nodes.add(wrapper);
            }
        }
        require(nodes.size() <= 128, app, "Application bean limit is 128");
        var ordered = new ArrayList<Node>();
        for (Node node : nodes) visit(node, nodes, ordered, new ArrayList<>());
        String qualified = app.getQualifiedName() + "MicroModule";
        String pkg = processingEnv
                .getElementUtils()
                .getPackageOf(app)
                .getQualifiedName()
                .toString();
        var code = new StringBuilder("package " + pkg + ";\npublic final class " + app.getSimpleName()
                + "MicroModule implements hoori.micro.app.ApplicationModule {\n"
                + "  public hoori.micro.Microservice create(hoori.micro.Environment environment, String[] args) throws Exception {\n"
                + "    var app = hoori.micro.Microservice.create(" + literal(name)
                + ", " + version + ", environment);\n"
                + "    try {\n");
        for (Node node : ordered) {
            code.append("      ")
                    .append(node.type)
                    .append(" b")
                    .append(node.index)
                    .append(" = ");

            if (node.owned) code.append("app.ownBean(");
            else code.append("java.util.Objects.requireNonNull(");

            if (node.call == null) code.append("new ").append(clients.companion((TypeElement) node.origin));
            else if (node.owner == null) code.append("new ").append(node.call.getEnclosingElement());
            else code.append("b").append(node.owner.index).append('.').append(node.call.getSimpleName());

            code.append('(').append(String.join(", ", node.arguments)).append("));\n");
        }
        code.append("      var errors = hoori.rest.mvc.MvcErrors.builder().classify(app::classifyMvc);\n");
        for (Node node : nodes)
            if (annotation(node.origin, "hoori.rest.mvc.RestControllerAdvice") != null)
                code.append("      errors.advice(new ")
                        .append(companion((TypeElement) node.origin))
                        .append("(b")
                        .append(node.index)
                        .append("));\n");
        code.append("      var errorBoundary = errors.build();\n");
        for (Node node : nodes)
            if (annotation(node.origin, "hoori.rest.mvc.RestController") != null) {
                TypeElement controller = (TypeElement) node.origin;
                var methods = clients.contracts.analyze(controller);
                for (ExecutableElement method : ElementFilter.methodsIn(controller.getEnclosedElements()))
                    if (annotation(method, API + "GatewayRoute") != null)
                        require(
                                methods.stream().anyMatch(m -> m.element().equals(method)),
                                method,
                                "GatewayRoute needs an HTTP mapping");
                code.append("      app.controller(new ")
                        .append(companion(controller))
                        .append("(b")
                        .append(node.index)
                        .append(
                                ", app.jsonLimits(), hoori.validation.ValidationLimits.DEFAULT, errorBoundary), new String[] {");
                for (int i = 0; i < methods.size(); i++) {
                    if (i != 0) code.append(", ");

                    var method = methods.get(i);
                    AnnotationMirror gateway = annotation(method.element(), API + "GatewayRoute");
                    String permission = gateway == null ? null : (String) value(gateway, "permission");

                    if (permission != null) {
                        require(
                                permission.matches(
                                        java.util.regex.Pattern.quote(name) + ":[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?"),
                                method.element(),
                                "Gateway permission must belong to " + name);
                        require(
                                !method.responseKind().equals("RAW"),
                                method.element(),
                                "Published MVC endpoints need a finite JSON or empty response contract");
                        for (var parameter : method.parameters())
                            require(
                                    !parameter.source().equals("HEADER")
                                            || parameter.name().equalsIgnoreCase("Accept")
                                            || parameter.name().equalsIgnoreCase("Content-Type"),
                                    parameter.element(),
                                    "Gateway forwards only Accept and Content-Type request headers; keep other header contracts internal");
                    }

                    code.append(literal(permission));
                }
                code.append("});\n");
            }
        String contract = (String) value(declaration, "openApi");
        require(
                !contract.isEmpty() || ((String) value(declaration, "openApiBaseline")).isEmpty(),
                app,
                "OpenAPI baseline requires openApi");

        if (!contract.isEmpty()) {
            try {
                code.append(new OpenApiBinding(processingEnv, clients, app, contract).bind(components));
            } catch (IllegalArgumentException | hoori.rest.json.JsonException invalid) {
                throw new HttpContract.Invalid(app, "Invalid OpenAPI: " + invalid.getMessage());
            }
        }

        code.append("      app.prepare();\n      return app;\n"
                + "    } catch (Exception | Error failure) {\n"
                + "      try { app.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }\n"
                + "      throw failure;\n    }\n  }\n}\n");
        write(qualified, app, code.toString());
    }

    private void visit(Node node, List<Node> nodes, List<Node> ordered, List<Node> path) {
        if (ordered.contains(node)) return;

        require(!path.contains(node), node.origin, "Dependency cycle: " + chain(path) + " -> " + node.origin);
        path.add(node);

        if (node.owner != null) visit(node.owner, nodes, ordered, path);

        if (node.delegate != null) {
            visit(node.delegate, nodes, ordered, path);
            node.arguments.add("b" + node.delegate.index);

            if (node.manager != null) {
                visit(node.manager, nodes, ordered, path);
                node.arguments.add("b" + node.manager.index);
            }

            path.remove(path.size() - 1);
            ordered.add(node);

            return;
        }

        if (node.call == null) node.arguments.add("app");

        for (var parameter : node.call == null
                ? java.util.List.<javax.lang.model.element.VariableElement>of()
                : node.call.getParameters()) {
            String special = switch (parameter.asType().toString()) {
                case "hoori.micro.Microservice" -> "app";
                case "hoori.micro.Environment" -> "environment";
                case "java.lang.String[]" -> "args";
                default -> null;
            };

            if (special != null) {
                node.arguments.add(special);
                continue;
            }

            List<Node> candidates = nodes.stream()
                    .filter(n -> processingEnv.getTypeUtils().isAssignable(n.type, parameter.asType()))
                    .toList();
            List<Node> wrappers = candidates.stream()
                    .filter(n ->
                            n.delegate != null && processingEnv.getTypeUtils().isSameType(n.type, parameter.asType()))
                    .toList();

            if (!wrappers.isEmpty()) candidates = wrappers;

            require(
                    candidates.size() == 1,
                    parameter,
                    (candidates.isEmpty() ? "Missing" : "Ambiguous") + " dependency " + parameter.asType() + " in "
                            + chain(path));
            Node dependency = candidates.get(0);
            visit(dependency, nodes, ordered, path);
            node.arguments.add("b" + dependency.index);
        }
        path.remove(path.size() - 1);
        ordered.add(node);
    }

    private static String chain(List<Node> path) {
        return String.join(" -> ", path.stream().map(n -> n.origin.toString()).toList());
    }

    private String companion(TypeElement type) {
        String pkg = processingEnv
                .getElementUtils()
                .getPackageOf(type)
                .getQualifiedName()
                .toString();

        return pkg + "."
                + type.getQualifiedName().toString().substring(pkg.length() + 1).replace('.', '_') + "Mvc";
    }

    private static final class Node {
        final TypeMirror type;
        final Element origin;
        final ExecutableElement call;
        final Node owner;
        final boolean owned;
        final int index;
        final List<String> arguments = new ArrayList<>();
        Node delegate, manager;

        Node(TypeMirror type, Element origin, ExecutableElement call, Node owner, boolean owned, int index) {
            this.type = type;
            this.origin = origin;
            this.call = call;
            this.owner = owner;
            this.owned = owned;
            this.index = index;
        }
    }

    private boolean policy(TypeElement type, String name) {
        if (annotation(type, name) != null) return true;

        return ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(type)).stream()
                .anyMatch(method -> annotation(method, name) != null);
    }

    private static final class Pending extends RuntimeException {
        Pending(String message) {
            super(message);
        }
    }

    private AnnotationMirror annotation(Element element, String name) {
        return HttpContract.annotation(element.getAnnotationMirrors(), name);
    }

    private Object value(AnnotationMirror annotation, String key) {
        for (var entry : processingEnv
                .getElementUtils()
                .getElementValuesWithDefaults(annotation)
                .entrySet())
            if (entry.getKey().getSimpleName().contentEquals(key))
                return entry.getValue().getValue();
        throw new IllegalArgumentException(key);
    }

    @SuppressWarnings("unchecked")
    private List<? extends AnnotationValue> array(AnnotationMirror annotation, String key) {
        return (List<? extends AnnotationValue>) value(annotation, key);
    }

    private void write(String name, Element origin, String code) throws IOException {
        try (var writer =
                processingEnv.getFiler().createSourceFile(name, origin).openWriter()) {
            writer.write(code);
        }
    }

    private void error(Element origin, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Hoori Micro: " + message, origin);
    }
}
