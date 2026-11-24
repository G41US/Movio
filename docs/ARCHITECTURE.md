# Movio architecture

## Application targets

Movio is one Android application with adaptive experiences for:
- Android phones and touch input
- Android TV / Google TV and D-pad or remote input

TV support is part of the initial architecture, not a later port.

## Planned layers

- **app shell:** startup, navigation, device-class detection, settings
- **catalogue:** movie and series metadata, search, details, watchlists
- **playback:** AndroidX Media3 player, playback state, subtitles, quality selection
- **providers:** isolated adapters that resolve authorized catalogue/source APIs into normalized playback candidates
- **updates:** GitHub Releases check, release notes, APK download and Android package-installer handoff

## Provider contract

Provider adapters must return normalized candidates with:
- media URL
- media type (HLS, DASH, or progressive)
- required request headers, when legitimately required
- subtitle tracks, when available
- quality and language labels
- source attribution and diagnostic status

The player must not contain provider-specific extraction logic. A provider failure must not crash the app or block other providers.

## Update design

The updater will:
1. Query the GitHub Releases API for the latest published release.
2. Compare its version code with the installed version.
3. Display release notes and update size when available.
4. Download the APK over HTTPS and verify its published checksum.
5. Hand installation to Android's package installer.

Updates must be signed with the same app-signing identity as the installed build. Installation confirmation remains under Android's control. No signing keys or tokens belong in this public repository.

## Scope and safeguards

Only integrate sources and APIs that the project is authorized to use. Do not assume that a URL found in another app is a stable, public, or reusable playback API. Validate each adapter independently on phone and TV.
