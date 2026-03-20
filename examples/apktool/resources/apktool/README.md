# Apktool Runtime Resource

- Runtime jar: `apktool-runtime-android.jar`
- Load mode: `ToolPkg.readResource(...)` + `Java.loadJar(..., { childFirstPrefixes: [...] })`
- Bridge mode: direct `Java.type("brut.androlib.*")` with child-first package isolation, no `runJar`, no host-side custom bridge
- `Java.loadJar(...)` requires `classes.dex`, so this runtime jar is a dex-jar rather than a plain JVM class jar
- Source artifact used to build the runtime jar: `org.apktool:apktool-cli:3.0.1`
- This package only keeps decode- and framework-list-related direct bridge calls
- Although the source artifact is the fat CLI jar, the JS side does **not** call the CLI entrypoint; it directly calls original classes such as `brut.androlib.Config`, `brut.androlib.ApkDecoder`, and `brut.androlib.res.Framework`
