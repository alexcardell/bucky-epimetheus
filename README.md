# bucky-prometheus

[![release](https://github.com/alexcardell/bucky-prometheus/actions/workflows/ci.yaml/badge.svg)](https://github.com/alexcardell/bucky-prometheus/actions/workflows/ci.yaml)
[![version](https://img.shields.io/maven-central/v/io.cardell/bucky-prometheus_2.13)](https://search.maven.org/artifact/io.cardell/bucky-prometheus_2.13)

## Installing 

```
libraryDependencies += "io.cardell" %% "bucky-prometheus" % "@VERSION@"
```

## Usage

```scala mdoc
val client: AmqpClient[IO] = ???

for {
  reg <- CollectorRegistry.build[IO]
  meteredClient <- EpimetheusAmqpClient[IO](client, reg)
} yield ()
```

## License

This software is licensed under the MIT license. See [LICENSE](./LICENSE)

## Developing

To set up development dependencies use Nix >2.4
with flakes enabled, and use the `nix develop` command.
