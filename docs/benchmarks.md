# Local comparison benchmarks

The current [framework comparison of 5 October 2026](#framework-optimizations-5-october-2026)
uses the pinned HTTP consumer. Earlier sections remain dated measurements of their respective
states. None of these measurements is a production or general performance approval.

`scripts/benchmark.py` uses the verified headless release distribution and the real HTTP/REST
transport. `BenchmarkMain` lives only in the test classes: static targets and snapshots are no
framework API and do not end up in the shipped service JARs.

| Variant | Path |
|---|---|
| A | Shopping → direct REST echo route of Recipes |
| B | Shopping → real broker with a fixed test snapshot → HTTP endpoint |
| C | Shopping → production broker/discovery → HTTP endpoint |
| D | dynamic gateway → Shopping endpoint → Recipes endpoint |

All use the same echo business logic, JSON codecs, payloads, runtime, engine and transport
limits. A/B isolate transport and broker respectively; C/D use the actual microservice
lifecycle. A/B/C keep the registry and the unused gateway idle; their consumption still counts
toward the reported total. The four roles get 0.5 CPU, 256 MiB container memory and a 32 MiB
logical guest heap limit each: 2 CPUs and 1 GiB in total. This creates no CPU parallelism within
a single guest. Pools: four connections total/per origin, server: 32, client timeout: 2 s, request
timeout: 10 s, managed work budget: 2 s. Security and container limits stay active; additional
benchmark ports bind only to loopback. No credentials, redirects or retries.

After `scripts/build.sh`:

```bash
python3 scripts/benchmark.py --output .cache/baseline-mixed.json
python3 scripts/benchmark.py --engine interpreter --output .cache/baseline-interpreter.json
# Larger catalog: additional, never-called registrations, no additional CPUs.
python3 scripts/benchmark.py --catalog-instances 32 --output .cache/catalog-32.json
# Changing public routes; construction and conflict checking in the real gateway:
python3 scripts/benchmark.py --variants D --public-routes 64 --repeats 1 --output .cache/routes-64.json
# Separate instrumentation with hoori stats; not a comparable timing measurement.
python3 scripts/benchmark.py --profile --repeats 1 --output .cache/profile.json
# Quiet control traffic before overload, with a proven identical instance set:
python3 scripts/benchmark.py --variants C --stable-catalog --idle 12 --output .cache/control-idle.json
```

The script builds the prepared images before measuring. Each variant/repetition starts fresh
processes in its own Compose project; the order is shuffled reproducibly with `--seed`.
Acceptance limits stored up front: p99 ≤ 1000 ms, at most 1 % errors including arrivals that
never started. The overload phase is allowed to exceed this limit: fast failures are not a win.
Result files are never overwritten; completed phases are kept on errors; only the script's own
Compose project is removed.

After warmup follow closed small load, open large payloads (8192 characters), sustained load, a
250 ms dependency, a short overload, idle and normal recovery. Open load has a given arrival rate
and no generator queue: busy workers count as `not_started`. `offered`, `started`, `successful`,
`failed` and status codes stay separate. A 200 response with a wrong echo counts as an error.
Open latencies start at the scheduled arrival time; generator lag and generator CPU are reported.
Throughput uses the actual run time including responses still in flight. Check the generator for
saturation using these values before using higher rates.

Per role and in total, CPU time, guest allocations, GC, tasks, open HTTP connections and service
handles are recorded. Runtime snapshots are taken outside the timing; `--profile` additionally
takes snapshots during load and exports the VM method call counters to stdout on exit. Their cost
and background work are included in the counter differences. They are not an isolated allocation
of a single handler. The connection probe counts its own connection/request. The old SDK
baseline has no pool statistics: `http_pool_pending_acquires: null` means not measurable, not zero
waiters. Since the #4 control the fixture records both pools and their sum; early raw data keep
the `null` values of that time.

Guest heap is logical occupancy; committed heap is its physical backing storage. RSS also
includes JIT, native providers and VM metadata. Cgroup `memory.current` covers processes, kernel
and file cache; `memory.stat` reports, among others, `anon`, `file`, `kernel`, `sock` and `slab`.
File cache is not subtracted. The regularly read peak is an **observed**, not a guaranteed peak.
Image size is Docker's uncompressed image size, not an RSS measure. Startup measures Compose start
until local healthchecks; discovery convergence is additionally required before load.
Container/image IDs, actual limits, runtime receipt, commit, working tree status and artifact
hashes are in the result file. RSS sums may count shared pages several times.

Compare only identical settings and measurement modes; report median and range of the
repetitions. A claimed HTTP capacity gain must exceed the spread and meet the limits set up front.
Isolated cost optimizations do not lift existing latency or error limits. Measure SDK upgrades
first without framework optimization. These local runs are no production/security approval. Real
parallel replicas and repeated rolling/recovery cycles are recorded separately in
[validation.md](validation.md) and at the end of this document. The following sections are dated
measurement states, each with its own pins and settings.

## Framework optimizations, 5 October 2026

The starting point is `07dcf7d`, unchanged with Hoori release `83d2b8f`, guest base 0.4.0 and SDKs
0.1.0. Three small changes remain: `Context.effectiveBudget` reads the request ownership once;
immediate admission needs no cancellation callback; `ClientRequest` creates query builders only
when needed, shares the empty body and returns an unchanged path directly. The broker computes the
endpoint key once per call. Budget/cancellation checks, queue limits and the actual drain are
preserved.

The fixture already uses static endpoint descriptors like the generated clients **before** the
baseline measurement. This removes an overhead of the old fixture and does not count as a
framework gain. The load runs through `BenchmarkMain` and the real SDKs/broker/request boundaries,
not through the generated MVC controllers; their functional acceptance is in
[validation.md](validation.md).

### Isolated native costs

`scripts/benchmark_costs.py` measures three fresh processes per engine, 10,000 calls per probe
after one warmup round and five measurement rounds. The table shows wall time per call: median of
the process medians [min–max], microseconds. No HTTP throughput or CPU measurement. The allocation
counter includes a snapshot overhead of about 0.04 bytes/call that is identical in both states.

| Probe | Interpreter before → after, µs | Mixed before → after, µs | Guest bytes before → after |
|---|---:|---:|---:|
| effective budget | 78.72 [75.15–79.17] → 40.95 [40.35–41.15] | 134.05 [133.66–135.18] → 75.32 [73.54–82.28] | 80.04 → 64.04 |
| immediate admission/close | 38.11 [38.04–38.72] → 17.78 [17.43–19.50] | 62.20 [61.98–67.91] → 33.52 [33.40–33.65] | 40.04 → 20.04 |
| client request without query | 7.92 [7.79–8.01] → 6.47 [6.46–6.47] | 13.70 [13.51–13.83] → 6.15 [6.14–6.21] | 590.04 → 564.04 |

The individual rounds, source diffs and final probes are in the
[cost raw data](benchmarks/framework-costs-2026-10-05.json.gz).

### HTTP comparison and limits

Three fresh processes per state/engine/C or D path; before/after with unchanged fixture bytecode
and alternating order. Warmup 10 s with closed load, then 10 s each of closed load and open load
at 16/s. Eight workers, pool four and the CPU/RAM/timeout limits above. No other own builds or
tests during timing. The desktop host is not a dedicated benchmark machine; the observed spread
remains reported.

[HTTP raw data](benchmarks/framework-http-2026-10-05.json.gz) contain all 24 experiments,
artifact hashes, process/container identities and measurement order. Framework SHA256: before
`c804b7b9…`, after three cost optimizations `ce628f2c…`. The table shows median [min–max] of the
closed phases. CPU and allocation sum all four roles including background work, probes and
errors; the denominator counts successful responses only.

| Engine/path | Successes/s before → after | p99 of all attempts, ms | CPU ms/success | Guest KiB/success |
|---|---:|---:|---:|---:|
| Interpreter C | 78.04 [41.04–84.12] → 74.18 [74.09–81.47] | 774 [707–1328] → 805 [726–908] | 10.74 [9.94–20.23] → 11.32 [10.32–11.35] | 23.62 [23.53–24.59] → 23.49 [23.36–23.52] |
| Interpreter D | 73.45 [68.78–74.21] → 74.32 [61.09–74.74] | 2004 [2004–2005] → 2006 [2003–2008] | 16.41 [16.24–17.78] → 16.49 [16.09–19.76] | 36.03 [36.01–36.12] → 35.76 [35.74–36.04] |
| Mixed C | 63.61 [61.87–63.79] → 62.52 [61.59–64.26] | 882 [697–900] → 813 [797–875] | 13.45 [13.43–13.79] → 13.77 [13.24–13.99] | 23.89 [23.85–23.92] → 23.72 [23.66–23.77] |
| Mixed D | 59.21 [51.78–60.52] → 52.93 [39.41–59.21] | 2010 [2006–2012] → 2014 [2007–2014] | 21.16 [20.53–23.76] → 23.68 [21.19–31.44] | 36.36 [36.29–36.68] → 36.28 [36.05–37.02] |

**No reliable HTTP throughput or CPU gain.** The medians move in both directions and the ranges
overlap. Fewer logical guest allocations do not mean a proven RSS saving either. Closed errors
before/after the three changes: Interpreter C 7/7, D 36/33; Mixed C 7/9, D 31/33. All gateway D
runs still miss the p99 target of 1000 ms.

Open load: Interpreter delivers 960/960 correct responses per state across C/D, p99 after at most
32.77 ms. Mixed before 838/960 successes, 12 errors and 110 arrivals not started; after 809/960,
17 errors and 134 not started. There the highest after-p99 is 2332 ms. Arrivals not started are
bounded generator rejections and count toward the error rate. The fixed warmup does not rule out
later runtime work; these short phases do not prove a permanently warm production operation. The
three cost changes do not resolve the HTTP latency limit.

A fifth attempt yielded CPU time via `Thread.yield()` outside the lock after a permit release
with waiters. The native admission/cancellation/capacity checks passed in both engines. Two
interpreter D screenings, however, still delivered p99 of 2004/1942 ms and 5/3 errors. The higher
throughput there is not reliable evidence given the observed spread; the target was not reached.
This change also stays out of production code. Patch, JAR hash and raw values are in the cost
archive.

### Discarded attempt and Hoori follow-up

A fourth round prevented waiting calls from being overtaken by using FIFO. Two short interpreter
D runs lowered p99 to 172/181 ms but raised 503 errors to 23/26 versus 4/6 deadline errors before.
The attempt was discarded; fast rejections are no successful speed-up. Native queue/cancellation
checks alone had not ruled out this load shift.

The SDK-only reproducer `DeadlineCosts` shows, per 6000 separate operations with a finite budget,
6000 newly created and drained timer tasks and 12000 context switches. Without a finite budget no
additional tasks are created. 136 additional guest bytes/operation and the measured time costs
are recorded in [Hoori #231](https://github.com/TrautmannP/hoori/issues/231) with a reproducer and
acceptance criteria. This timer optimization belongs in the original SDK or VM; the Micro consumer
keeps its deadline and completion guarantees. The raw data contain the separate profiles of both
engines. The deadline-free scope path has a mixed median of 338.70 µs versus 189.99 µs in the
interpreter; [Hoori #232](https://github.com/TrautmannP/hoori/issues/232) tracks the frequent
runtime/interpreter transitions of this separate case. The counters prove calls, not an isolated
time share of the individual transitions.

```bash
# After build/stage; use the archived JARs for before and after separately:
python3 scripts/benchmark_costs.py --framework /path/framework.jar --output .cache/costs.json
python3 scripts/benchmark_costs.py --probe deadlines --output .cache/deadlines.json
python3 scripts/benchmark.py --framework-jar /path/framework.jar --variants CD --repeats 3 --engine interpreter --seconds 10 --warmup 10 --warmup-rate 0 --idle 2 --workers 8 --pool 4 --rate 16 --burst-rate 32 --phases closed-small sustained --output .cache/http.json
# HTTP separately also with --engine mixed; no own builds/tests during load.
```

## Baseline, 29 September 2026

[Mixed raw data](benchmarks/baseline-550d608f-mixed.json.gz): three fresh runs per variant, warmup
10 s, six load phases of 6 s each and idle 3 s; otherwise the defaults above. Runtime `550d608f`,
existing framework state `abb40cf` plus test fixture; the exact artifact hashes are in the
compressed JSON.

| Variant | successful/s, closed load: median [min–max] | p99 median, ms | Shopping RSS after idle, median MiB |
|---|---:|---:|---:|
| A | 116.19 [114.63–118.66] | 294.69 | 181.22 |
| B | 110.27 [104.66–112.54] | 413.80 | 182.89 |
| C | 108.92 [108.71–111.42] | 512.44 | 187.67 |
| D | 94.53 [92.74–95.23] | 1724.55 | 186.74 |

The gateway misses the p99 limit set up front. Individual outliers also occur under open
normal/slow load; the short overload phase contains timeouts and arrivals not started. All
subsequent recovery phases deliver 24 correct responses each without errors. This implies no
general recovery or performance approval; the raw data contain all phases and rejections.

[Interpreter raw data](benchmarks/baseline-550d608f-interpreter.json.gz), with the same settings
and also three fresh runs per variant:

| Variant | successful/s: median [min–max] | p99 median, ms | Shopping RSS after idle, median MiB |
|---|---:|---:|---:|
| A | 96.63 [95.13–99.63] | 300.19 | 24.06 |
| B | 90.33 [85.58–91.55] | 773.28 | 24.92 |
| C | 88.85 [87.78–90.81] | 711.10 | 25.86 |
| D | 82.48 [77.87–85.15] | 1983.64 | 26.08 |

The clearly different RSS footprint is an engine observation on this runtime pin, not a measured
gain of a framework change. Here too all normal recovery phases deliver 24 correct responses
without errors. [32 additional registrations](benchmarks/catalog-32-550d608f-mixed.json.gz) were
checked as a separate mixed control for C/D (one fresh run, warmup 6 s, load phases 4 s, idle
2 s); both recovery phases deliver 16 correct responses. This short run proves the executable
larger catalog control, not a statistically reliable improvement over the small baseline.

[Separate instrumentation](benchmarks/profile-550d608f-mixed.json.gz) for C with 32 additional
registrations (warmup 5 s, phases 3 s, idle 2 s) contains live snapshots and complete
`hoori stats` output. Shopping shows up to 13 active guest tasks in the load samples and up to
six in normal recovery; the profiling latencies are no timing comparison because of the
additional probes.

## Discovery with leases and catalog revisions, 30 September 2026

[Measured C/D candidate](benchmarks/issue-2-550d608f-mixed.json.gz): unchanged runtime/SDK pin,
same mixed settings as the baseline, three fresh runs per variant. Successful/s under closed load:
C **103.22 [92.67–104.20]**, D **85.17 [69.84–95.72]**; p99 median **599.62/1901.18 ms**. The
observed total throughput is lower; the gateway still misses the limit. The six recovery phases
deliver 24 correct responses each without errors. The changing foreign registration prevents many
unchanged responses; additional catalog metadata then increase response bytes. This is **no
performance approval**.

For pure renewals there is additionally one fresh C run each with
`--stable-catalog --seconds 2 --warmup 5 --idle 12 --repeats 1`, once with
`--catalog-instances 32`. The quiet section lies after warmup and before overload; before/after it
exactly the same 3/35 instances must be fully registered. That way the known registrar timeout
from #4 cannot fake a saving. Raw data: [old, small](benchmarks/issue-2-control-old-small.json.gz),
[new, small](benchmarks/issue-2-control-new-small.json.gz),
[old, large](benchmarks/issue-2-control-old-large.json.gz),
[new, large](benchmarks/issue-2-control-new-large.json.gz).

| Registry, quiet section | small: old → new | 32 additional instances: old → new |
|---|---:|---:|
| HTTP requests in about 12 s, including probes/healthchecks | 22 → 22 | 153 → 214 |
| CPU ms / HTTP request | 10.88 → 9.43 | 16.05 → 3.04 |
| Guest allocation KiB / HTTP request | 15.41 → 12.42 | 38.88 → 14.93 |
| received / sent service bytes | 5782/22996 → 4648/19832 | 39188/611071 → 54016/845593 |
| observed guest heap after section, MiB | 0.68 → 0.58 | 0.66 → 1.36 |
| Registry RSS after section, MiB | 54.32 → 53.99 | 56.70 → 57.89 |

The external generator still registers its additional providers serially via legacy PUT and only
waits 2 s afterwards. The faster registry therefore processes more of them in the large catalog;
absolute bytes are no comparison at equal arrival rate there. CPU/allocations are additionally
related to actually counted HTTP requests. The two real framework providers use small leases.
Single runs are descriptive controls, not a statistical gain approval. The heap also contains
not-yet-collected garbage; this is no live retention measurement. An RSS gain is not proven.
Exactly one serialized registry snapshot is kept; the JUnit check proves its object/byte reuse for
leases.

## Dependency views, 30 September 2026

One fresh C run each before/after #3 with the same stable settings as above (`seconds=2`,
`warmup=5`, `idle=12`), small and with 32 additional services. Starting point `24c2a8b`;
runtime/SDK still `550d608f`. Both states use the same extended test fixture: `/bench/runtime`
additionally counts actually held broker instances/actions. Fixture and generator hashes match in
all four files; registry instance sets stay exactly 3/35 before/after the quiet section. Raw data:
[old, small](benchmarks/issue-3-control-old-small.json.gz),
[new, small](benchmarks/issue-3-control-new-small.json.gz),
[old, large](benchmarks/issue-3-control-old-large.json.gz),
[new, large](benchmarks/issue-3-control-new-large.json.gz).

| After quiet section | small: old → new | 32 additional services: old → new |
|---|---:|---:|
| Shopping: held instances / actions | 3/3 → 1/1 | 35/35 → 1/1 |
| Recipes without dependencies: instances / actions | 3/3 → 0/0 | 35/35 → 0/0 |
| Shopping: observed guest heap, MiB | 0.571 → 0.562 | 0.622 → 0.561 |
| Shopping: RSS, MiB | 59.734 → 56.438 | 60.586 → 56.711 |
| Shopping: received / sent service bytes | 1618/12604 → 1870/13143 | 1630/12611 → 1882/13147 |
| Registry: CPU ms / HTTP request | 9.363 → 9.889 | 2.868 → 3.054 |

The catalog bound is proven directly; single RSS/heap observations do not prove a general RAM
gain, and the heap contains garbage. Stable catalogs mostly return 204 since #2 already; the
additional view headers increase bytes and registry costs here. Successful/s under closed load
small 108.35 → 112.45, large 105.54 → 109.71; the new runs contain one error each (below 1 %). All
four recovery phases deliver eight correct responses each. These short controls are no
statistical throughput/performance approval.

[Isolated native lookup control](benchmarks/issue-3-lookup-550d608f.json.gz): one process per
engine, one warmup pair and five pairs of 100000 selections each, alternating `echo`/an action not
offered. Median [min–max] per selection: mixed at 1/35 instances
**1.75 [1.70–1.78] / 2.22 [2.20–2.24] µs**, interpreter **4.51 [4.38–4.51] / 39.95 [39.55–40.39] µs**.
This CPU control runs outside Docker and measures no transport latency. With the now actually held
single entry, an additional search index is not justified; the linear scan stays.

```bash
runtime=$PWD/.docker-context/runtime
cp=$PWD/framework/target/classes:$PWD/framework/target/test-classes
for jar in "$runtime"/lib/*.jar; do cp="$cp:$jar"; done
"$runtime/bin/hoori" run --engine mixed --allow-environment-read \
  --class-path "$cp" --arg lookup hoori/micro/BenchmarkMain
# The same with --engine interpreter.
```

## Qualified SDK pin, 30 September 2026

The complete clean VM/guest base/SDK set changes from `550d608f` to `3254301`, without framework
optimization. The [small](benchmarks/issue-8-sdk-3254301-small.json.gz) and
[large](benchmarks/issue-8-sdk-3254301-large.json.gz) C control runs use the same compiled fixture
and all load/resource settings of the #3 controls above. The generator still sets missing pool
samples to `null`; the change does not alter these measurements. All artifact/runtime identities
are recorded.

| Observation, one fresh mixed run each | small: old → new SDK | 32 additional services: old → new SDK |
|---|---:|---:|
| successful/s, closed load | 112.45 → 119.76 | 109.71 → 116.38 |
| p99, ms | 550.29 → 571.05 | 507.90 → 189.64 |
| Shopping heap after quiet section, MiB | 0.562 → 0.562 | 0.561 → 0.563 |
| Shopping RSS after quiet section, MiB | 56.438 → 56.930 | 56.711 → 57.559 |

The closed phase still contains one error each (<1 %); both new recovery phases deliver eight
correct responses each without errors. Shopping holds one catalog entry, Recipes none; complete
registry sets stay 3/35. Single runs are a separate SDK control, not a statistical gain approval.
Pending counters are **not yet recorded** in this fixture although the new SDK offers them. Pool
isolation, admission and budget propagation follow separately in #4–#6; base image digest and
further image acceptance from #8 remain open.

## Control isolation, 30 September 2026

Framework `43c0b6e` before #4 and the new state use the same clean SDK set `3254301` as well as
the same compiled fixture (`78692ef128e2…`) and the same generator. The old state received only
measurement access for its existing pool; its C transport, lifecycle and SDK pending policy stay
unchanged. The patches are in the raw data. One fresh mixed C run each with the same limits and
2 s load/5 s warmup/12 s quiet as above, no own tests running in parallel during measurement. Raw
data: [old small](benchmarks/issue-4-control-old-small.json.gz),
[new small](benchmarks/issue-4-control-new-small.json.gz),
[old with 32 additional services](benchmarks/issue-4-control-old-large.json.gz),
[new with 32 additional services](benchmarks/issue-4-control-new-large.json.gz).

| Observation | small: old → new | 32 additional services: old → new |
|---|---:|---:|
| Control quiet: total CPU, ms | 537.13 → 552.50 | 956.68 → 940.75 |
| RSS of all four roles after quiet, MiB | 218.238 → 218.961 | 223.945 → 224.754 |
| Task snapshots of all roles after quiet | 20 → 19 | 20 → 19 |
| active + idle outgoing pool connections after quiet | 2 → 2 | 2 → 2 |
| successful/s, closed load | 117.33 → 118.57 | 117.65 → 113.92 |
| p99, ms | 410.25 → 293.10 | 552.05 → 607.12 |
| Recovery p99, ms | 10.78 → 10.64 | 15.19 → 10.12 |

After the quiet phase, Shopping/Recipes each hold one registry connection: before in the shared
pool, now in the separate control pool. Data connections have expired after 12 s of quiet; control
pending is always zero. Shopping has one additional reaper task; registry and the gateway (inactive
in C) one registrar less each. Task counts are snapshots during metric probes, not measured peaks.
The connection budget per discovery service rises from four shared to four data plus one control
connection; the total CPU/RAM limits stay the same. Old pending policy: SDK default 64; new:
explicitly four for data, zero for control. Raw samples now record these counters, including
rejections, instead of `null` for unmeasured values.

All four recovery phases deliver eight correct responses without errors. The small closed phase
contains one error each old/new, the large one none. The higher RSS values and small
CPU/throughput differences are single-run observations, **no performance gain or retention/load
approval**.

[Native fault raw data](benchmarks/issue-4-control-faults.json.gz) contain both engines,
runtime/fixture/framework identities and the controlled peer sequence. On the same SDK, the old
framework state ends the registrar after a 300 ms timeout and keeps reporting ready. The new state
recovers from a single and from two consecutive timeouts; after an unknown lease a full
registration follows, about 700/677 ms (mixed/interpreter) after the start of the last delayed
lease. SIGTERM during a slow re-registration waits for a DELETE held back for 150 ms, exits with
exit code 0/`drained=true` and empty pools. The #4 Docker smoke of that time additionally checked
real registry leases under data saturation over one TTL and the drain of active plus waiting data
calls. With #5, waiters are rejected before encoding on stop. CPU-uncooperative handlers remain
Hoori's single-carrier limit; pool separation creates no preemption.

## Admission, 30 September 2026

Before: `3667c144`, after: the same state with the admission change; both on the clean SDK set
`3254301`. Three fresh mixed C/D runs each, same generator, 8 workers, per role 0.5 CPU/256 MiB and
32 MiB guest heap, pool 4, 5 s warmup, 2 s per phase and 3 s quiet. No own tests/builds during the
load phases. Before, up to four calls waited in the SDK; after, they wait before encoding in the
framework queue (4 active/4 waiting calls, SDK pending 0). The new fixture adds admission counters;
business paths and generator stay the same, fixture hashes differ. Raw data with receipt, hashes
and source patch: [before](benchmarks/issue-5-before-3254301-mixed.json.gz),
[after](benchmarks/issue-5-after-3254301-mixed.json.gz).

| Median, before → after | C: direct broker | D: additional gateway |
|---|---:|---:|
| successful/s, closed load | 121.34 → 122.70 | 109.51 → 102.68 |
| closed p99, ms | 502.08 → 591.19 | 1628.68 → 903.44 |
| system CPU per success, ms | 7.191 → 7.183 | 11.833 → 13.261 |
| system allocation per success, KiB | 21.472 → 21.599 | 33.735 → 34.162 |
| open normal load (4/s): p99, ms | 33.20 → 14.89 | 21.90 → 22.26 |
| Recovery p99, ms | 10.34 → 10.74 | 16.07 → 16.43 |
| Guest heap of all roles after quiet, MiB | 4.548 → 4.852 | 2.827 → 2.734 |
| RSS of all roles after quiet, MiB (range) | 247.469–367.441 → 250.859–372.633 | 381.070–381.336 → 388.051–389.488 |
| cgroup memory after quiet, MiB (range) | 205.535–325.742 → 209.297–331.832 | 338.828–339.781 → 346.910–352.496 |

The closed C phase has one error in total before/after; D two/five. Per variant/state, normal
load, slow calls and recovery deliver 24 correct responses each without errors. The burst phases
each have 102 successes, six timeout errors and 276 arrivals not started by the bounded generator;
these are neither successful work nor server rejections. After, the snapshot counters show zero
framework/SDK waiters after quiet; before, there were no framework counters. Task snapshots are
identical before/after.

Under closed gateway load, admission costs about 6.2 % throughput and 12.1 % more system CPU per
success here. The frequent permit check takes no additional queue lock; acquire/release keep their
necessary synchronization. Open normal load stays at 4/s without errors. This is a cost control,
not a performance gain or general approval. Registry RSS varies between about 63 and 185 MiB in
both states; heap/RSS/cgroup are snapshots without forced GC, not a measurement of live retention.
The short quiet phase does not prove a long-term plateau.

[Native admission raw data](benchmarks/issue-5-admission-native.json.gz) prove separately in both
engines: bounded calls/waiters, rejection before DTO read or encoding/HTTP exchange, two load peaks
without restart, budget consumption during encoding/waiting and stop with rejection of waiters,
drain of admitted work and empty gates/pools. Health and discovery stay reachable. The SDK has
already read the bounded raw body before incoming admission; wire remaining budgets across service
hops were still missing at that time (current state below).

## Budgets, gateway and image, 2 October 2026

The new clean runtime/guest/SDK set `d8906e6` was first measured with an unchanged framework JAR
(`1da7aa9f…`), identical fixture and identical generator:
[before, `3254301`](benchmarks/issue-11-sdk-before-3254301.json.gz),
[after, `d8906e6`](benchmarks/issue-11-sdk-after-d8906e6.json.gz). One fresh mixed C/D run each,
5 s warmup, 2 s per phase and 3 s quiet, otherwise the limits above. This controls the whole
runtime/SDK change, not a single SDK method.

| Single run, before → after | C | D |
|---|---:|---:|
| successful/s | 119.31 → 92.37 | 102.00 → 74.33 |
| closed p99, ms | 704.82 → 865.90 | 1423.40 → 2059.70 |
| system CPU ms/success | 7.80 → 10.10 | 13.21 → 18.05 |
| system allocation KiB/success | 21.69 → 22.45 | 34.04 → 35.63 |
| system RSS after quiet, MiB | 256.26 → 259.15 | 274.29 → 274.83 |
| cgroup memory after quiet, MiB | 220.07 → 216.33 | 230.95 → 232.36 |

The change costs throughput and CPU in these controls; single runs prove no general regression or
improvement. Startup is about 11.5 s in each case. The uncompressed Shopping image size rises from
112066111 to 112115254 bytes; image bytes are no RAM measure.

The framework candidate is based on `99590f2` plus the
[executable source patch](benchmarks/issue-11-source.patch.gz). JAR (`6d8fcd9d…`), fixture
(`85b5615c…`), generator, receipt and container identities are in the raw data:
[mixed](benchmarks/issue-11-final-mixed.json.gz),
[interpreter](benchmarks/issue-11-final-interpreter.json.gz). Three fresh runs each of A–D with
the same short settings as the SDK control, alternating order, no own builds/tests during load. CPU
and allocation include all four roles, background work, probes and errors; the denominator counts
successes only.

| Engine / variant | successful/s: median [min–max] | p99: median [min–max], ms | CPU ms/success | Allocation KiB/success |
|---|---:|---:|---:|---:|
| Mixed A | 103.14 [102.84–114.39] | 300.71 [289.74–444.48] | 9.451 | 21.327 |
| Mixed B | 83.40 [78.11–101.49] | 647.41 [620.38–781.05] | 11.361 | 22.596 |
| Mixed C | 97.70 [96.62–99.33] | 801.06 [393.74–1128.20] | 9.917 | 22.814 |
| Mixed D | 78.22 [65.52–83.59] | 2031.00 [1696.24–2061.77] | 17.475 | 35.812 |
| Interpreter A | 108.43 [104.45–120.37] | 188.43 [158.64–203.12] | 7.584 | 21.047 |
| Interpreter B | 96.85 [93.23–103.76] | 295.59 [202.06–486.53] | 8.676 | 22.057 |
| Interpreter C | 99.17 [97.96–107.57] | 530.26 [255.61–817.84] | 8.908 | 22.718 |
| Interpreter D | 90.32 [84.74–90.83] | 2021.59 [2016.38–2050.97] | 14.025 | 35.497 |

D misses the p99 limit in all six runs; individual runs also miss the 1 % error limit. Mixed C
exceeds 1000 ms once. Open large/small load, slow calls and recovery, on the other hand, deliver
**384/384 correct responses** in total per engine, p99 at most **315.59/334.19 ms**
(mixed/interpreter). The twelve burst phases per engine count 1536 arrivals: **409/408 successes**,
**23/24 errors**, **1104 arrivals not started** each. The latter are bounded generator rejections,
not server successes. Generator CPU stays below 69 ms per phase, p99 start lag below 3.24 ms. The
short phase and mixed spread justify no performance gain against the single SDK control; B/C are
no reliable evidence of a positive discovery effect. Successful/min as well as p50/p95 and per-role
CPU/GC/tasks/connections are fully in the JSON.

| After last quiet, all roles | Guest heap / committed: median MiB | RSS range MiB | cgroup range MiB |
|---|---:|---:|---:|
| Mixed A | 4.15 / 7.96 | 238.91–240.50 | 196.71–198.24 |
| Mixed B | 3.57 / 7.21 | 245.82–247.40 | 203.43–204.83 |
| Mixed C | 3.37 / 7.51 | 260.37–262.66 | 218.53–220.75 |
| Mixed D | 2.96 / 7.49 | 280.61–400.43 | 238.39–358.59 |
| Interpreter A | 4.35 / 6.94 | 96.13–96.44 | 72.32–77.55 |
| Interpreter B | 4.17 / 6.77 | 100.09–100.19 | 76.04–80.83 |
| Interpreter C | 3.45 / 5.90 | 103.95–104.46 | 79.23–81.19 |
| Interpreter D | 3.59 / 6.39 | 108.49–108.98 | 83.95–84.12 |

SDK waiters are zero everywhere after quiet; existing framework gates as well. Startup is
11.49–11.71 s, with one mixed A outlier of 23.99 s. Registry RSS explains a large part of the
mixed D spread; without forced GC or a separate measurement of native memory, this is no live
retention measurement.

### Changing public routes and native limits

[D with 64 additional public routes](benchmarks/issue-11-routes64-mixed.json.gz) changes their
paths every 2 s: one fresh uninstrumented mixed run with the same settings. Closed
**81.95 successes/s**, p99 **1311.43 ms**, system CPU **16.157 ms/success**, allocation
**36.747 KiB/success**; normal load and recovery 8/8 correct each, no errors. After quiet the
gateway holds three instances/66 actions, 0.70 MiB observed guest heap and zero waiters. This is a
size control, not a gain over the three small D runs.

[Separate `--profile`](benchmarks/issue-11-routes64-profile.json.gz), same D64 setup: live samples
show at most 14 gateway tasks and 1.96 MiB of its logical heap. The complete `hoori stats` end per
role with zero open handles, `tasks_created == tasks_completed`, one carrier and zero OOM; GC is
triggered naturally. The instrumented latencies are no timing comparison. The profile does not
isolate a dominating parameter copy or lookup cost; raw body copy and additional indexes stay
deferred.

[Native budget/gateway check of both engines](benchmarks/issue-11-budgets-native.json.gz) proves
the late SDK sampling after pool waiting, new/reused connections, serial budgets, header/policy
limits, cancellation/recovery and atomic count/metadata overflows. Semantics and transport cases
not re-checked are in [validation.md](validation.md). The small contract overload and named
publication still use the same codecs; an optional generator stays deferred for lack of proven
benefit.

### Image and joint load/recovery acceptance

[Image inventory](benchmarks/issue-11-image-inventory.json.gz): fixed amd64 digest, signed Debian
package sources from 30 September 2026, checked ELF dependencies, certificates, guest license and
checksums. No host JDK/Maven/Rust in the runtime image. Runtime set 17146444 bytes, prepared
context 17441998 bytes; Docker images uncompressed 112109785–112120366 bytes
(**106.92–106.93 MiB**). No measured size/RAM gain and no promise of bit-identical Docker layers.
The complete verified distribution and `curl` are kept. 50 health probes each need, per probe,
**10.33/6.78 ms cgroup CPU** and **20.33/13.30 ms wall time** (mixed/interpreter); this includes
server, `curl` and background work, not an isolated `curl` cost comparison.

[Mixed smoke](benchmarks/issue-11-smoke-mixed.json.gz) and
[interpreter smoke](benchmarks/issue-11-smoke-interpreter.json.gz) use the real service images with
the same CPU/RAM limits. Old/new providers share 0.5 CPU together during rolling; consumers and
gateway keep image/container/process identity. Three load peaks across registry TTL, registry
timeout/expiry, provider SIGKILL/TTL and two route changes converge without consumer restart.
SIGTERM with active/waiting work and a paused registry delivers 200/503, `drained=true`, exit 0 and
empty gates/pools.

| After rolling recovery / 5.5 s quiet | Mixed RSS MiB | Interpreter RSS MiB |
|---|---:|---:|
| registry | 180.20 | 23.27 |
| recipes | 58.62 | 24.76 |
| shopping | 194.82 | 38.12 |
| gateway | 192.19 | 36.12 |

All five resource snapshots per engine stay within the fixed limits; after idle, SDK/framework
waiters are zero and tasks/handles converge. Continuing existing calls count **32/97 successes and
41/31 502** during rolling, **38/57 successes and 152/141 502** during the provider crash
(mixed/interpreter). After catalog expiry, 404 are counted additionally. This acceptance proves
bounded recovery, not uninterrupted availability or long-term live retention.

```bash
# Each without further own load/builds; repeat --engine interpreter separately:
python3 scripts/benchmark.py --variants ABCD --repeats 3 --seconds 2 --warmup 5 --idle 3 --output .cache/current-mixed.json
python3 scripts/benchmark.py --variants D --public-routes 64 --repeats 1 --seconds 2 --warmup 5 --idle 3 --output .cache/routes64.json
python3 scripts/benchmark.py --variants D --public-routes 64 --profile --repeats 1 --seconds 2 --warmup 5 --idle 3 --output .cache/routes64-profile.json
```

## Tasks v2, 3 October 2026

### Comparison on the same runtime set

Before `c10ccbe` (already the new SDK pin, still the unmanaged Micro path), after `e500bda` with the
test fixture added here. Both use exactly the same clean Hoori release `7d7245a`, guest base 0.4.0
and original SDKs 0.1.0. Framework JAR SHA256: before `04f881d1d8282c66…`, after
`6d0a4b06b558497c…`. Full hashes, git status, fixture/load generator, containers and settings:
[before mixed](benchmarks/tasks-v2-before-mixed.json.gz),
[after mixed](benchmarks/tasks-v2-after-mixed.json.gz),
[before interpreter](benchmarks/tasks-v2-before-interpreter.json.gz),
[after interpreter](benchmarks/tasks-v2-after-interpreter.json.gz). Source edits started during the
baseline measurement did not change the built/measured JARs; their stored hashes define the
comparison identity.

Two fresh processes per C/D/engine, warmup 3 s, six phases of 4 s each and idle 3 s. Eight
generator workers, pool four, open load 4/s, burst 32/s, 64/8192 characters, slow call 250 ms. Four
roles with the limits above; the new Pantry demo container is explicitly not started in these
controls. Host: i5-11600KF, Linux 7.0.0-34-generic. No other own load tests/builds during
measurement. Two short repetitions deliver observed spread, not a statistically sound capacity
forecast.

Closed load; CPU and allocation are the sum of all four roles per success, including
background/probe/error costs. Pairs of numbers mean before → after.

| Engine/path | Successes/s, min–max | p99 of all attempts, ms, min–max | CPU ms/success, median | Allocation KiB/success, median |
|---|---:|---:|---:|---:|
| Mixed C | 112.08–112.20 → 68.95–70.65 | 512–716 → 687–1068 | 7.92 → 13.25 | 21.44 → 23.75 |
| Mixed D | 89.93–95.96 → 57.70–58.07 | 1969–2006 → 1862–2008 | 14.20 → 22.81 | 34.06 → 37.23 |
| Interpreter C | 114.70–116.81 → 88.68–89.21 | 398–572 → 706–828 | 7.34 → 9.84 | 21.46 → 23.44 |
| Interpreter D | 99.21–100.91 → 73.51–76.73 | 1039–1913 → 1942–1992 | 12.47 → 17.13 | 34.06 → 36.76 |

Median throughput thus drops by **23–38 %**, CPU/success rises by **34–67 %**, allocation/success
by **8–11 %**. Closed errors before/after: mixed C 1/2, D 7/5; interpreter C 2/2, D 8/6. Errors are
not successes. All four new D runs still miss the p99 limit, as does one of the mixed C runs.

Open normal load reaches 16/16 successes in every run (4/s); here all percentiles are successful
responses. Median of the two runs, milliseconds:

| Engine/path | p50 before → after | p95/p99 before → after |
|---|---:|---:|
| Mixed C | 8.09 → 13.48 | 34.04 → 33.73 |
| Mixed D | 12.70 → 20.57 | 58.57 → 67.97 |
| Interpreter C | 7.98 → 10.31 | 10.35 → 12.11 |
| Interpreter D | 12.19 → 16.05 | 15.06 → 19.06 |

With only 16 arrivals, p95 and p99 fall on the same maximum value. Large payload/normal load/slow
calls/recovery together: after, mixed 255/256 and interpreter 256/256 successful. The single mixed
D recovery timeout also occurred before; maximum recovery p99 2306 ms, so no latency target met.
After, 512 burst arrivals per engine: **254 successes, 16 errors, 242 not started**. This is bounded
overload, not additional usable throughput.

| All four roles after last quiet | RSS MiB, before → after (min–max) | cgroup MiB after | Guest heap / committed MiB after |
|---|---:|---:|---:|
| Mixed C | 632.78–633.56 → 660.18–661.82 | 618.14–619.36 | 3.93–4.09 / 9.08–9.23 |
| Mixed D | 775.90–776.12 → 808.69–810.36 | 766.80–768.30 | 2.11–2.26 / 7.92–8.10 |
| Interpreter C | 108.51–108.79 → 118.24–118.42 | 93.89–97.68 | 5.29–5.43 / 8.34–8.55 |
| Interpreter D | 113.40–113.41 → 125.74–125.89 | 101.02–102.61 | 3.98–4.28 / 7.38–7.73 |

RSS rises; a smaller observed heap after GC is no memory saving. Mixed in particular contains
JIT/native costs these snapshots do not isolate. Timestamps, per-role CPU/allocation/GC,
p50/p95/p99, successful/min, successful p99, generator lag and cgroup peaks remain verifiable in
the JSON.

```bash
# Each separately also with --engine interpreter, without parallel own load:
python3 scripts/benchmark.py --variants CD --repeats 2 --seconds 4 --warmup 3 --idle 3 --workers 8 --pool 4 --rate 4 --burst-rate 32 --output .cache/tasks-current-mixed.json
```

### Same two reads: serial, parallel and fail-fast

Test consumer C calls the same discovered echo action twice and checks identical results. Both
reads wait 250 ms independently. No additional CPUs or providers: the real two-provider
application is functionally qualified separately; this cost control isolates the composition. Two
repetitions per engine/policy, warmup 5 s, measurement 5 s at 2/s, idle 3 s; otherwise the same
limits. Each success phase delivers 10/10 correct results, 120/120 in total.

| Engine/policy | p50 ms, median | p95/p99 ms, min–max | CPU ms/success, median | Allocation KiB/success, median |
|---|---:|---:|---:|---:|
| Mixed serial | 534.00 | 561.66–566.30 | 77.85 | 100.05 |
| Mixed parallel | 280.44 | 306.28–308.30 | 76.65 | 100.17 |
| Mixed fail-fast | 279.65 | 308.36–310.55 | 78.93 | 100.77 |
| Interpreter serial | 519.60 | 521.71–524.91 | 39.03 | 100.77 |
| Interpreter parallel | 264.20 | 264.92–265.40 | 38.65 | 100.36 |
| Interpreter fail-fast | 264.48 | 265.68–266.58 | 38.56 | 98.96 |

The success rate stays at about 2/s (120/min) because of the offered load; waiting time drops
through I/O overlap, without deriving a CPU multicore or capacity gain from it. With ten arrivals,
p95 and p99 are each the maximum. The CPU range of the mixed serial runs is 77.44–78.25 ms/success,
parallel 76.48–76.82; interpreter 39.02–39.04 versus 38.34–38.97. This does not justify a general
CPU saving. All individual values and memory samples:
[mixed serial](benchmarks/tasks-v2-serial-mixed.json.gz),
[parallel](benchmarks/tasks-v2-parallel-mixed.json.gz),
[fail-fast](benchmarks/tasks-v2-fail-fast-mixed.json.gz),
[interpreter serial](benchmarks/tasks-v2-serial-interpreter.json.gz),
[parallel](benchmarks/tasks-v2-parallel-interpreter.json.gz),
[fail-fast](benchmarks/tasks-v2-fail-fast-interpreter.json.gz).

In the failure path the first read fails after 50 ms with 409; the other keeps waiting 250 ms.
After a separate failure warmup, all 80 measured calls return 502 as expected, no generator
rejection. **Usable throughput: zero.** CPU/allocation per failed attempt are reported here:

| Engine/policy | p50 ms, median | p95/p99 ms, min–max | CPU ms/attempt, median | Allocation KiB/attempt, median |
|---|---:|---:|---:|---:|
| Mixed all-complete | 269.68 | 281.00–289.73 | 47.71 | 100.34 |
| Mixed fail-fast | 65.24 | 79.71–81.82 | 47.57 | 106.10 |
| Interpreter all-complete | 265.39 | 265.92–268.03 | 38.74 | 99.61 |
| Interpreter fail-fast | 61.89 | 62.76–64.20 | 38.01 | 105.04 |

Fail-fast ends the local exchange and its drain earlier. The remote work may continue
independently; accordingly the total CPU work hardly drops. After idle, active/waiting framework
calls and SDK pending are zero in all twelve composition runs; 3–7 tasks and 2–5 handles per role
are observed, including running server/control connections. This does not replace the targeted
GC/WeakReference/recovery evidence in [validation.md](validation.md).

```bash
# For each engine run serial, parallel and fail-fast one after another; new output paths:
python3 scripts/benchmark.py --composition parallel --variants C --repeats 2 --seconds 5 --warmup 5 --idle 3 --workers 8 --pool 4 --rate 2 --delay-ms 250 --output .cache/tasks-parallel-mixed.json
```

### Cause of the overhead and decision

The [separate interpreter profile](benchmarks/tasks-v2-profile-methods.json.gz) uses C with one
worker, warmup 3 s and phases of 2 s, open load 2/s, burst 8/s. Its instrumented latencies are no
comparison values. For **199 business requests** each in Recipes and Shopping it shows **199** each
of `Executions.request`, `Operation.call`, `TaskContext.enter`, timer registrations, timer worker
constructions and completed timer registrations. Shopping additionally executes 199
cancellation-bound `HttpTasks.invoke`. Recipes/Shopping check the current Micro ownership
1990/995 times.

The concrete new cost path is thus visible: every root owns scope, bindings and deadline; the SDK
implementation starts the timer worker on the first registration and drains it on the last close.
In this serial control that happens per request. On top come ownership checks and cancellation
registrations around admission/HTTP. The original Micro path had none of this work. Under closed
mixed C load, per-role CPU/success accordingly rises in Recipes from 2.98–3.03 to 5.32–5.45 ms, in
Shopping from 4.51–4.56 to 7.20–7.38 ms. The profile proves calls, not an isolated time share of
individual methods; the additional mixed/JIT difference stays unattributed.

On profile exit: all **66/254/262/51** created tasks completed (registry/recipes/shopping/gateway),
zero open service handles everywhere. The decision for this state: the required ownership,
cancellation and real drain remain the regular model. The normal-path overhead is accepted and
documented as a regression; this state gets no performance approval. A later timer/context
optimization needs the same cost comparison and must preserve the checked completion guarantees.
No additional unmanaged product mode and no VM/SDK copy in the consumer.

```bash
python3 scripts/benchmark.py --variants C --profile --engine interpreter --repeats 1 --seconds 2 --warmup 3 --idle 3 --workers 1 --pool 4 --rate 2 --burst-rate 8 --output .cache/tasks-profile.json
```
