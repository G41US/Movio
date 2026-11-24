# Movio

Movio is an Android streaming application designed for phones and Android TV.

## Project goals

- One Android app with layouts and navigation adapted for phones and TV.
- A modular catalogue and playback architecture.
- AndroidX Media3 for media playback.
- Provider adapters kept separate from the player so sources can be added, tested, enabled, or disabled independently.
- An in-app update screen that checks the project's GitHub Releases and guides the user through installing signed APK updates.

## Development status

**Stage 0 — repository and architecture setup**

The repository is being initialized. Playback integrations are not yet implemented, and adding a provider does not guarantee that its streams are playable. Each integration must be independently tested with sources the project is authorized to access.

## Build

The Android project will use Gradle and Kotlin. GitHub Actions will build a debug APK on pushes and pull requests, then publish release APKs when a version tag is pushed.

## Updates

The app's updater will read release metadata from this repository. Release APKs must be signed consistently with the installed app. Android will still require the user to approve installation.

## Repository

https://github.com/G41US/Movio
