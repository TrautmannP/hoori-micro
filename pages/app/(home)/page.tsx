import Link from 'next/link';
import { DynamicCodeBlock } from 'fumadocs-ui/components/dynamic-codeblock';

const hello = `@RestController
@RequestMapping("/recipes")
public final class RecipeController {
    private final RecipeService recipes;

    public RecipeController(RecipeService recipes) {
        this.recipes = recipes;
    }

    @GetMapping("/{id}")
    @GatewayRoute(permission = "recipes:read")
    public Recipe get(@PathVariable("id") @Positive long id) {
        return recipes.get(id);
    }
}`;

const features = [
  {
    title: 'Build-time graph',
    body: 'An annotation processor generates the constructor graph. No classpath scan, no runtime DI, no proxies.',
    href: '/docs/guide/components',
  },
  {
    title: 'Original MVC & Jakarta Validation',
    body: '@GetMapping, @RequestBody, @Valid, @Positive – bound and checked before your business method runs.',
    href: '/docs/guide/controllers',
  },
  {
    title: 'Typed service clients',
    body: 'An interface with @ServiceClient is enough. Addresses come from the local discovery snapshot.',
    href: '/docs/guide/service-clients',
  },
  {
    title: 'Gateway with permissions',
    body: 'Only methods with @GatewayRoute(permission = …) are published – everything else stays internal.',
    href: '/docs/guide/gateway',
  },
  {
    title: 'OpenAPI as the contract',
    body: 'A service’s openapi.json is checked against controllers, DTOs and constraints at build time.',
    href: '/docs/guide/openapi',
  },
  {
    title: 'Bounded requests',
    body: 'Admission, budgets and ordered shutdown are built in – with fixed metrics and no request labels.',
    href: '/docs/guide/lifecycle',
  },
];

export default function HomePage() {
  return (
    <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-16 px-4 py-16 md:py-24">
      <section className="grid items-center gap-10 lg:grid-cols-2">
        <div className="flex flex-col gap-6">
          <span className="w-fit rounded-full border px-3 py-1 text-xs text-fd-muted-foreground">
            Experimental · runs on HooriVM
          </span>
          <h1 className="text-4xl font-bold tracking-tight md:text-5xl">
            Annotated microservices,{' '}
            <span className="text-fd-primary">no runtime magic.</span>
          </h1>
          <p className="text-lg text-fd-muted-foreground">
            Hoori Micro wires controllers, services, repositories and typed service clients
            through a graph generated at build time – on top of the original Hoori HTTP and
            MVC SDKs.
          </p>
          <div className="flex flex-wrap gap-3">
            <Link
              href="/docs/guide/quick-start"
              className="rounded-lg bg-fd-primary px-5 py-2.5 text-sm font-medium text-fd-primary-foreground"
            >
              Quick start
            </Link>
            <Link
              href="/docs/guide/tutorial"
              className="rounded-lg border px-5 py-2.5 text-sm font-medium hover:bg-fd-accent"
            >
              Tutorial
            </Link>
            <Link
              href="/docs/reference/annotations"
              className="rounded-lg border px-5 py-2.5 text-sm font-medium hover:bg-fd-accent"
            >
              API reference
            </Link>
          </div>
        </div>
        <div className="min-w-0">
          <DynamicCodeBlock lang="java" code={hello} />
        </div>
      </section>

      <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {features.map((f) => (
          <Link
            key={f.title}
            href={f.href}
            className="rounded-xl border bg-fd-card p-5 transition-colors hover:bg-fd-accent"
          >
            <h2 className="mb-2 font-semibold">{f.title}</h2>
            <p className="text-sm text-fd-muted-foreground">{f.body}</p>
          </Link>
        ))}
      </section>
    </main>
  );
}
