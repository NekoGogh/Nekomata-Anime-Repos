# Akrab Anime Extensions

A small, self-contained [Aniyomi](https://github.com/aniyomiorg/aniyomi) / AniZen
extension repository with Spanish-language anime sources.

| Extension | Website | Language | NSFW | Content |
| --- | --- | --- | --- | --- |
| Jkanime | <https://jkanime.net/> | es | No | Anime (sub / Latino) |
| AnimeAV1 | <https://animeav1.com/> | es | No | Anime (sub) |
| Aniyae | <https://open.aniyae.net/> | es | No | Anime (sub / Latino) |
| HentaiLA | <https://hentaila.com/hub> | es | **Yes** | Adult anime |

The extensions target `extensions-lib` **v16** and the modern hoster/video API
(`seasonListParse`, `hosterListParse`, `videoListParse`). They are built with
Kotlin 2.2.x, because the published v16 library is compiled with Kotlin 2.2
metadata. APKs are built by GitHub Actions and published from the `apk/` folder
together with the generated `index.min.json`.

> The checked-in `index.min.json` is intentionally empty (`[]`). The first
> GitHub Actions run builds the APKs, populates `apk/`, regenerates the index
> and commits both back to `main`. After that, add the repository to Aniyomi.

## Add the repository

1. Open Aniyomi / AniZen.
2. Go to **More → Settings → Browse → Extension repos** (the exact path depends
   on the app version).
3. Add this URL:

```
https://raw.githubusercontent.com/akrab/anime-extensions-repo/main/index.min.json
```

A jsDelivr mirror is also available:

```
https://cdn.jsdelivr.net/gh/akrab/anime-extensions-repo@main/index.min.json
```

4. Open **Browse → Extensions**, refresh, and install the extensions you want.

> The repository is served from GitHub Pages-style raw files, so no web server or
> special configuration is required.

## Building

Requirements:

- JDK 17
- Android SDK with platform 34 and build-tools installed (`ANDROID_HOME` set,
  or a `local.properties` file with `sdk.dir=...`)

Then:

```bash
./gradlew assembleRelease
```

Each module writes its APK to
`src/es/<module>/build/outputs/apk/release/aniyomi-es.<module>-v16.<code>-release.apk`.
The CI workflow normalizes these names to `aniyomi-es.<module>-v16.<code>.apk`
when copying them to `apk/`.

The GitHub Actions workflow (`.github/workflows/build.yml`) builds every module,
copies the APKs to `apk/`, regenerates the index and commits the result. It runs
on every push to `main` and can be triggered manually from the Actions tab.

## Signing

Android requires all versions of an extension to be signed with the same key.
Create a keystore once:

```bash
./scripts/generate-keystore.sh
```

The script prints:

- the SHA-256 fingerprint — put it in `repo.json` under
  `meta.signingKeyFingerprint`;
- a base64 string — add it as the GitHub repository secret `SIGNING_KEY`.

The workflow decodes `SIGNING_KEY` into `signingkey.jks` before building. If the
secret is missing, the workflow generates an **ephemeral** keystore so the build
still succeeds, but the next build will use a different signature. In that case
Aniyomi may refuse to update an installed extension and you will have to
uninstall it first. Configure `SIGNING_KEY` for stable updates.

If `signingkey.jks` already exists, print the values needed for the secret and
`repo.json` with:

```bash
keytool -list -v -keystore signingkey.jks -storepass "${KEY_STORE_PASSWORD:-android}" \
  | awk '/SHA256:/ { print tolower($2); exit }'   # fingerprint
base64 -w0 signingkey.jks                        # SIGNING_KEY secret value
```

`signingkey.jks` is ignored by git. Never commit it.

## Repository layout

```
.github/workflows/build.yml   CI build + index publishing
apk/                          built APKs (committed by CI)
common.gradle                 shared Android/Kotlin configuration
gradle/                       Gradle wrapper
icon/                         repository icons (one per extension)
scripts/generate_index.py     generates index.json/index.min.json/index.html
scripts/generate-keystore.sh  creates the release keystore
src/es/jkanime/               Jkanime extension
src/es/animeav1/              AnimeAV1 extension
src/es/aniyae/                Aniyae extension
src/es/hentaila/              HentaiLA extension (NSFW)
repo.json                     repository metadata
index.min.json                extension index consumed by Aniyomi
```

Each module is self-contained: it has its own `build.gradle`,
`AndroidManifest.xml`, launcher icons and Kotlin source. There is no shared
`core` module, so modules can be copied out independently.

## Implementation notes

### Jkanime

- Popular: `/directorio?filtro=popularidad&p=N`; latest: homepage + `/directorio`.
- Search: `/directorio?q=<query>&p=N` (the page embeds the result list as
  `var animes = {...}`).
- Episodes: the site requires a CSRF token **and** the cookies from the details
  response, so the extension posts to `/ajax/episodes/<id>/<page>` with both.
- Hosters: `var servers` (base64 URLs) and `var video[N]` iframes. The built-in
  `jkplayer` wrapper pages contain a direct `.m3u8` URL, which is extracted
  directly. Mp4upload and Streamtape are also resolved.

### AnimeAV1

- Popular/latest/search: `/catalogo` with `order` / `search` query parameters.
- Episodes: extracted from the SvelteKit `episodes:[{id,number},...]` payload.
- Hosters: `embeds:{SUB:[...],DUB:[...]}` plus Mega downloads.
- Zilla HLS is resolved by replacing `/play/` with `/m3u8/`; Pixeldrain,
  mp4upload, Streamtape and generic `.m3u8`/`.mp4` pages are handled.

### Aniyae

- Uses the site JSON API:
  - `POST /api/content/titles` for popular/latest;
  - `POST /api/content/search` for search;
  - `POST /api/content/titles/<id>/episodes` for episodes;
  - `GET /api/content/episodes/<id>/servers` for hosters.
- The numeric title/episode IDs are read from the SvelteKit `__data.json`
  payloads.
- Server URLs are base64-encoded embed links; mp4upload and generic `.mp4` /
  `.m3u8` links are resolved. Voe and Byse use obfuscated front-ends and may not
  resolve in every region.
- The API currently ignores the `sort` parameter for titles, so "Popular" and
  "Latest" return the same ordering (newest first).

### HentaiLA

- The site is NSFW and is marked as such in the manifest/index.
- Popular/latest/search use the SvelteKit `catalogo/__data.json` endpoint.
- Episodes come from the media page; hoster URLs come from the episode
  `__data.json` (`embeds.SUB`).
- The `VIP` hoster is resolved by replacing `/play/` with `/m3u8/`.

## Disclaimer

This project is unofficial and is not affiliated with Aniyomi, AniZen, or any of
the websites listed above. It only provides scrapers; all content is hosted by
the respective third-party sites. Use it at your own risk and respect the laws
of your country.
