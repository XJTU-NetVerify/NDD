# Installation

## Prerequisites

- Java 8 or newer
- Maven 3.x

## Build The Repository

From the repository root:

```bash
mvn -DskipTests package
```

This produces:

- `target/classes/` for direct classpath-based runs during development
- `target/ndd-1.0.1.jar`
- `target/ndd-1.0.1-jar-with-dependencies.jar` for a self-contained runtime artifact

The jar-with-dependencies artifact is the simplest way to run the examples documented here.

## Maintained Versus Experimental Code Paths

The default Maven build intentionally compiles only the maintained library and example paths.

Excluded from the default compile:

- `org.ants.jndd.diagram.AtomizedNDD`
- `org.ants.jndd.nodetable.AtomizedNodeTable`
- `application.wan.bdd.*`
- `application.wan.ndd.*`

Those WAN and Batfish-adjacent paths are kept as research artifacts and integration references, but they still depend on older APIs, external datasets, or upstream patches that are not packaged in the default build.

## Repository Layout

- `src/main/java/org/ants/jndd`: low-level `JNDD` API
- `src/main/java/org/ants/javandd`: `BDDFactory`-style compatibility API
- `src/main/java/application`: example programs and research drivers
- `results/`: benchmark CSV, plots, and WAN/SRE result tables
- `content/`: documentation (this directory)
- `doc/javadoc/`: regenerated API documentation
- `lib/`: bundled third-party artifacts used by the project

## Using NDD In Another Project

The simplest way is to install NDD into your local Maven repository (`~/.m2`). From the
repository root:

```bash
mvn -DskipTests install
```

Then add the normal dependency to your project's `pom.xml`:

```xml
<dependency>
    <groupId>org.ants</groupId>
    <artifactId>ndd</artifactId>
    <version>1.0.1</version>
</dependency>
```

Alternatively, without installing, copy `target/ndd-1.0.1-jar-with-dependencies.jar` into your
project's `lib/` directory and depend on it as a local file:

```xml
<dependency>
    <groupId>org.ants</groupId>
    <artifactId>ndd</artifactId>
    <version>1.0.1</version>
    <scope>system</scope>
    <systemPath>${project.basedir}/lib/ndd-1.0.1-jar-with-dependencies.jar</systemPath>
</dependency>
```

## First Things To Read After Building

- [Usage](Usage.md) for the initialization sequence and example commands
- [Parameters](Parameters.md) for table sizing and field-layout guidance
- [Network Verification Applications](Network-Verification-Applications.md) for the research/application context
