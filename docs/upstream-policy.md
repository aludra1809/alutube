# Upstream Tracking Policy

Alutube is a single unified git repository. This document records how the
vendored upstream projects are tracked.

## Remotes (configured)

| Remote           | URL                                    | Purpose |
|------------------|----------------------------------------|---------|
| `origin`         | (Alutube fork repository)              | canonical Alutube development |
| `upstream`       | https://github.com/teamnewpipe/newpipe | NewPipe app upstream |
| `aether-upstream`| https://github.com/CluvexStudio/Aether | Aether upstream |

## Pinned base

| Component | Source | Pinned |
|-----------|--------|--------|
| Alutube app (NewPipe-derived) | `upstream` | `dev` branch commit `d4eb42edc` (`v0.29.1-52-gd4eb42edc`), baselined in the initial commit |
| NewPipe Extractor | JitPack artifact `com.github.TeamNewPipe:NewPipeExtractor` | commit `13a655fe53e0c3065f88725fc1fb594c3ede0169` (see `gradle/libs.versions.toml`) |
| Aether | `aether-upstream` | tag `v2.0.0` / commit `0e6f6a5`, vendored at `aether/` |

## Branch strategy

- `main` — Alutube development (rebranded + Aether integration), the default branch.
- `upstream/dev` (tracking ref) — fetched from `upstream` to base merges on.
- `aether-upstream/main` (optional tracking ref) — fetched from `aether-upstream`.

Recommended update flow for NewPipe upstream changes:

1. `git fetch upstream dev`
2. `git merge upstream/dev` into `main` (or a short-lived topic branch, then merge).
3. Resolve conflicts locally; NewPipe-derived files live at the repo root,
   and Alutube branding/integration files are additive so conflicts are small.
4. Commit with a message referencing the upstream commit range.

## Aether vendoring (no submodule)

Aether was vendored by deleting its inner `.git` and committing the tree at
`aether/` (the repository already contains its vendored `quiche/` as a plain
directory, so no nested submodule exists). This was chosen per project
requirement: one unified repo where every modification is visible.

Update flow for Aether:

1. `git fetch aether-upstream && git checkout aether-upstream/main`
2. Copy changed paths into `aether/`, also updating `aether/aether/Cargo.lock`.
3. Re-verify the pinned version markers if `aether/Cargo.toml` changed.
4. Commit with a message referencing the Aether upstream commit.
5. Update the pinned SHA in `AETHER_INTEGRATION_PLAN.md` §1 table.

## Licensing and attribution

- NewPipe-derived files keep their `SPDX-FileCopyrightText`/GPL headers.
- Aether keeps `aether/LICENSE` (AGPL-3.0) and its repository headers.
- `NOTICE` at the repository root documents upstream attribution; the in-app
  About → License screen lists NewPipe, Aether and quiche.
- See `AETHER_INTEGRATION_PLAN.md` §9 (Licensing) and §14 for obligations.