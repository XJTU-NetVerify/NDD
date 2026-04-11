# Installation

## Prerequisites

- Java 8 or newer
- Maven 3.x

## Build

Build the shaded jar from the repository root:

```bash
mvn -DskipTests package
```

The assembly plugin produces a `jar-with-dependencies` artifact under `target/`.

The default Maven build intentionally excludes several experimental paths that have not yet been migrated to the SoA/int-node API:

- `org.ants.jndd.diagram.AtomizedNDD`
- `org.ants.jndd.nodetable.AtomizedNodeTable`
- `application.nqueen.FiniteDomainZddNDDSolution`
- `application.wan.bdd.*`
- `application.wan.ndd.*`

## Repository Contents

- `lib/jdd-111.jar`: bundled modified JDD jar
- `lib/javabdd_1.0b2.tar.gz`: original JavaBDD distribution used for comparison
- `doc/javadoc/`: regenerated core API documentation for the maintained SoA branch

## Using the Jar in Another Maven Project

If you want to consume the packaged jar directly as a local system dependency, you can keep the same pattern as the upstream project:

```xml
<dependency>
    <groupId>org.ants</groupId>
    <artifactId>ndd</artifactId>
    <version>1.0.1</version>
    <scope>system</scope>
    <systemPath>${project.basedir}/lib/ndd-1.0.1-jar-with-dependencies.jar</systemPath>
</dependency>
```

If you publish the jar to a local or remote Maven repository instead, use the normal Maven coordinates instead of `scope=system`.

## Source Layout

- `org.ants.jndd`: low-level JNDD API
- `org.ants.javandd`: `BDDFactory`-style JavaNDD API
- `application`: examples and benchmark drivers

## More

- JNDD example usage: [Usage](Usage.md)
- JavaNDD migration notes: [`src/main/java/org/ants/javandd/README.md`](../src/main/java/org/ants/javandd/README.md)
