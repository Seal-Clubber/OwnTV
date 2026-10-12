# Android TV v1 snapshot

Saved on 12 October 2026 for the Seal-Clubber forks. The `v1` branch preserves this baseline. Continue development on `main` and leave `v1` at this commit.

## Included work

- Live TV category management: reuse the existing bulk/range selection to add whole provider groups or individual channels to a custom category. Preview combined membership, exclude channels, edit contributing sources and retain original provider groups. Group rules follow playlist refreshes and integrate with category search, ordering and Core backups.
- Sports navigation with My teams as the default tab and a separate Upcoming games tab. The compact team panel sits on the left; selecting a team highlights it and filters the fixtures on the right. Pressing the selected team again shows all followed teams. There is no All teams button.
- Inline team ordering in the existing vertical sidebar. Arrange teams enters edit mode; OK picks/drops a team, Up/Down moves it, and Right reaches Save. Cancel or Back outside a move discards the draft. Favourite teams, ordering and channel-list scope are stored per profile.
- FotMob fixtures, match IDs, competition names, badges, live scores and Netherlands/Türkiye broadcaster feeds. Favourite defaults include Fenerbahçe, Real Madrid, France, Türkiye and the Netherlands. Existing saved favourites are respected. Fixtures are chronological and deduplicated by match ID; kickoff times use Europe/Amsterdam.
- Confirmed Dutch and Turkish broadcasters shown separately; missing coverage remains Not confirmed. Official TRT 1 schedule fallback, caching, periodic refresh, offline/stale handling and HTTP restriction backoff are included. Streaming services remain labelled and related channel searches do not invent TV coverage.
- Match windows with Where to watch and Search channel name tabs, separate broadcaster/results panels, and match logos around the score. Main match cards use matching rounded grey panels and 160-pixel Fit logos alongside their central details.
- Search lists is inside the match window. Any selected lists are combined, channel duplicates remain visible with their list names, and empty/unavailable scopes stay restricted. Broadcaster selection fills/searches the channel name within that scope, including numbered variants such as NPO1/NPO2. Matching runs in the background to avoid the previous match-opening freeze.
- Foldable Türkiye/Netherlands quick-search sections with all supplied broadcaster shortcuts, including tabii Spor 1–6. Left returns from a shortcut to its folder; Up/Down between folder headers skips the expanded list; Right on an open folder returns to its first shortcut.
- English, Dutch and Turkish interface resources, literal inventories, existing regression tests and minimized public FotMob/TRT fixtures.

OwnTV_Mobile was not modified. The app retains the normal `tv.own.owntv` package and existing category features.

## Matching Core source

Build this snapshot with [Seal-Clubber/OwnTV_Core commit 65240923110b392093824e5aa0a4d253f4168a27](https://github.com/Seal-Clubber/OwnTV_Core/commit/65240923110b392093824e5aa0a4d253f4168a27), also saved on that repository's `v1` branch. The upstream published Core dependency does not include this fork's category/query changes; use the existing composite-build option below.

## Restore and build

Clone the two forks into sibling folders, choosing the saved snapshot:

```sh
git clone --branch v1 https://github.com/Seal-Clubber/OwnTV.git
git clone --branch v1 https://github.com/Seal-Clubber/OwnTV_Core.git
cd OwnTV
./gradlew -Powntv.corePath=../OwnTV_Core :app:assembleStandardDebug
```

On Windows, use `gradlew.bat`. Use JDK 21 and the Android SDK required by the Gradle files; configure `sdk.dir` in the ignored `local.properties`, or ANDROID_HOME. To inspect the saved snapshot in existing clean checkouts, use `git switch --detach v1` in both repositories. To resume development there, create a new branch from v1.

This restores source code, not the TV's playlist accounts or saved runtime data. Keep the existing signing key locally when building an in-place update for the installed app. Signing keys, credentials, local SDK/Gradle caches, APKs, device connection details and diagnostic dumps are not part of this public source commit.

## Build record

The final standard debug APK was built successfully against the paired Core source, verified with the existing signing certificate and installed as an in-place update on Sony KD-55XH9005 internal storage. It supports Android 8+ and includes ARMv7 and ARM64.

- APK name: `OwnTV-KD-55XH9005-sports-refined.apk`
- APK SHA-256: `fe4e85c40a4f029bd45a2e1f58e584cf09bc82ea10b6226b5c10d6812005f166`
- Signing certificate SHA-256: `ef0d8142f711114d6b1b0472bb1506bc3a30e81c9836420d3ef0e58ded3a6d97`

The latest UI build passed the compiler and mandatory build gates. No new unit tests or remote TV testing were run for the latest refinements or this snapshot commit, as requested by the user. Existing tests from earlier work are included in the source.
