# Reproducible modpack source

This directory is a Packwiz source pack for Minecraft 1.21.1 and NeoForge
21.1.244. It contains hashes and official download metadata, not third-party
JAR files.

## Profiles

- `client`: single-player, a world opened to LAN, and joining a dedicated
  server;
- `server`: a dedicated server;
- all gameplay mods use `both`; purely visual/recipe-viewer mods may use
  `client`.

## Development install

Use the repository-level installers:

```bash
./scripts/install-dev-server.sh ./run/dev-server
./scripts/install-dev-client.sh ./run/dev-client
```

The installers consume this Packwiz source with the correct `server` or
`client` side, build and copy the local `lasttrain` core, and install the
pinned Create Simurail alpha overlay. Simurail is intentionally not represented
as a Packwiz download because the selected upstream commit has no formal
release asset.

Every dependency is deliberately version-locked. Upgrades should happen in a
dedicated compatibility change, followed by both client and dedicated-server
smoke tests.
