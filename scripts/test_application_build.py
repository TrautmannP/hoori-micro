#!/usr/bin/env python3
"""Build the starter and an imported component module outside the repository reactor."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from runtime_check import ROOT, maven_repository, runtime_classpath, verify
from stage import jar


def main():
    runtime = ROOT / ".docker-context/runtime"
    receipt = verify(runtime)
    repo = maven_repository(runtime)
    mvn = ["mvn", "-B", "-ntp", f"-Dmaven.repo.local={repo}"]
    subprocess.run(mvn + ["-pl", "framework,processor,starter", "-am", "install", "-DskipTests", "-Dspotless.skip=true"], cwd=ROOT, check=True)
    work = Path(tempfile.mkdtemp(prefix="hoori-micro-app-"))
    # Only artifacts in the isolated Maven repository connect this consumer to Micro/Hoori.
    shared = work / "shared"
    source = shared / "src/main/java/shared"
    source.mkdir(parents=True)
    (shared / "pom.xml").write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<parent><groupId>dev.hoori</groupId><artifactId>hoori-micro-starter</artifactId><version>0.1.0-SNAPSHOT</version><relativePath/></parent>
<groupId>independent</groupId><artifactId>shared-components</artifactId><version>1</version></project>''')
    (source / "Shared.java").write_text('''package shared;
@hoori.micro.app.MicroModule public final class Shared {}
''')
    (source / "SharedResource.java").write_text('''package shared;
@hoori.micro.app.Service public final class SharedResource implements AutoCloseable {
 public SharedResource() { System.out.println("import_constructed"); }
 public void close() { System.out.println("import_closed"); }
}
''')
    subprocess.run(mvn + ["install"], cwd=shared, check=True)
    shutil.rmtree(source)
    app = work / "app"
    shutil.copytree(ROOT / "examples/mvc-crud/src/main", app / "src/main")
    pom = (ROOT / "examples/mvc-crud/pom.xml").read_text().replace("../../starter/pom.xml", "")
    pom = pom.replace("<properties><spotless.skip>false</spotless.skip></properties>", "")
    pom = pom.replace("</project>", """<dependencies><dependency><groupId>independent</groupId><artifactId>shared-components</artifactId><version>1</version></dependency></dependencies></project>""")
    (app / "pom.xml").write_text(pom)
    main_source = app / "src/main/java/dev/hoori/micro/crud/CrudApplication.java"
    code = main_source.read_text().replace('name = "crud"', 'name = "crud", imports = {shared.Shared.class}')
    # The independent native consumer exercises bootstrap/resource close without opening a listener.
    code = code.replace('Micro.run(CrudApplication.class, args);', 'try (var app = Micro.create(CrudApplication.class, hoori.micro.Environment.system(), args)) { System.out.println("independent_app_ready"); }')
    main_source.write_text(code)
    subprocess.run(mvn + ["verify"], cwd=app, check=True)
    jar_path = app / "target/mvc-crud-0.1.0-SNAPSHOT.jar"
    classpath = [jar_path, jar("framework", "hoori-micro"), shared / "target/shared-components-1.jar", *runtime_classpath(runtime, receipt)]
    shutil.rmtree(app / "src")
    for engine in ("interpreter", "mixed"):
        command = [str(runtime / "bin/hoori"), "run", "--engine", engine, "--allow-environment-read", "--allow-resource-read",
                   "--class-path", ":".join(map(str, classpath)), "dev/hoori/micro/crud/CrudApplication"]
        result = subprocess.run(command, cwd=work, env=os.environ | {"PATH": "/nonexistent", "JAVA_HOME": "/nonexistent"},
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
        assert result.returncode == 0, result.stdout
        for marker in ("independent_app_ready", "import_constructed", "import_closed"):
            assert result.stdout.count(marker) == 1, result.stdout
        print(engine, "independent app + imported source-free component module passed")
    (ROOT / ".cache/mvc-independent-build.json").write_text(json.dumps({"directory": str(work), "runtime": receipt["source"],
        "engines": ["interpreter", "mixed"], "source_removed_before_run": True}, indent=2) + "\n")


if __name__ == "__main__":
    main()
