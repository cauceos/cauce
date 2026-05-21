<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset=".github/assets/cauce-lockup-dark.svg">
  <source media="(prefers-color-scheme: light)" srcset=".github/assets/cauce-lockup-light.svg">
  <img alt="Cauce" src=".github/assets/cauce-lockup-light.svg" width="380">
</picture>

### The open-source Agent OS for European businesses.

Multi-channel. Multi-tenant. Sovereign by design.

[![License: BUSL-1.1](https://img.shields.io/badge/License-BUSL_1.1-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/status-early%20development-orange.svg)](https://github.com/cauceos/cauce)
[![CI](https://github.com/cauceos/cauce/actions/workflows/ci.yml/badge.svg)](https://github.com/cauceos/cauce/actions/workflows/ci.yml)

</div>

---

## What is Cauce

Cauce is an open-source platform for building, operating, and governing AI agents in production. Built natively on Java and Spring Boot, with first-class support for voice and chat channels, designed for European businesses and the consultancies, agencies, and integrators that serve them.

Today's agent platforms are either Python-only frameworks (LangGraph, CrewAI), closed-source US products (Vapi, Retell, Sierra), or single-channel gateways (Evolution API). None of them offer a complete, open, sovereign-by-design platform with multi-tenant operations and unified cross-channel memory for European businesses. Cauce fills that gap.

## Status

**Early development.** This repository is part of a multi-year project being built in the open. Architectural and design decisions are documented as Architecture Decision Records (ADRs) and will be published here as they stabilize. Code is not yet ready for production use.

The project is currently building its foundational architecture. Public artifacts will land here progressively over the coming months.

If you are a consultancy, agency, or integrator building agent solutions for European businesses, star this repository to be notified as initial code becomes available.

## Core principles

- **Java native.** Spring Boot 3.x backend, Gradle multi-module project, designed for the technical ecosystems of mid-size European businesses.
- **Multi-tier tenancy.** Three-level hierarchy (operator → partner → end client) for B2B2B business models. White-label your offering, host your clients, with full data isolation between tiers.
- **Unified cross-channel memory.** A single conversation memory across WhatsApp, voice, email, and web. Users don't repeat themselves across channels.
- **Self-improving agents.** Native eval framework with a self-improvement loop that proposes prompt and policy updates based on production conversations.
- **Sovereign by design.** RGPD-native endpoints, immutable audit log, configurable data residency, support for European LLM providers and self-hosted models.
- **Open source under BUSL.** Source-available with Apache 2.0 conversion four years after each release. No rugpull, no openwashing.

## Roadmap

A detailed roadmap will be published as the project matures. In broad strokes:

- **Months 1-6** — Core engine, foundational architecture, first WhatsApp channel adapter.
- **Months 6-12** — Multi-tenancy, voice channel, evals framework, minimal operator dashboard.
- **Months 12-18** — Self-improvement loop, full observability, audit log, first external partners.
- **Months 18-24** — Stabilization, first revenue, skill packs marketplace foundations.

## License

Cauce is released under the [Business Source License 1.1](LICENSE). Production use is permitted for any non-competing purpose, including white-labeled deployment to your own clients on your own infrastructure. Four years after the release of each version, that version automatically converts to the Apache License 2.0.

Enterprise modules (SSO/SAML, advanced multi-tier features, extended compliance helpers) are licensed separately under commercial terms.

## Maintainer

Cauce is built and maintained by [Payoyo Dev](https://github.com/JoseLuisPayoyo).

For questions, partnership inquiries, or to follow project progress, use the [GitHub Discussions](https://github.com/cauceos/cauce/discussions) of this repository.

---

<sub>Built in Andalusia. Designed for Europe.</sub>