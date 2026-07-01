# Publishing v0.1.43 to GitHub

Quick reference for the push. Assumes the repo at
`github.com/builtbyfood/morpheus-argus` has already been created
(empty or with initial files only — this push will set the canonical
state).

## One-time setup if the repo isn't initialized yet

```bash
cd C:\Users\travis\Documents\morpheus-ilo-console\

# Confirm we're on the right working tree
ls

# Initialize, set the canonical branch name, and point at GitHub
git init
git branch -m main
git remote add origin https://github.com/builtbyfood/morpheus-argus.git
```

## Each release

```bash
# Build the JAR locally — this is what gets attached to the release
./gradlew clean shadowJar
# output: build/libs/morpheus-argus-0.1.43-all.jar

# Stage everything except build outputs (already in .gitignore)
git add -A
git status   # sanity-check the diff before committing

# Commit
git commit -m "v0.1.43 — release prep: privilege docs corrected, README freshened, posting artifacts added"

# Tag the release (annotated tags carry the message; lightweight ones don't)
git tag -a v0.1.43 -m "v0.1.43 — release prep"

# Push code + tag
git push origin main
git push origin v0.1.43
```

## Create the GitHub release

1. Go to **https://github.com/builtbyfood/morpheus-argus/releases/new**
2. **Tag**: `v0.1.43` (should auto-suggest from the pushed tag)
3. **Title**: `v0.1.43 — Live iLO panel for Morpheus`
4. **Description**: paste the contents of
   [`docs/RELEASE_NOTES.md`](docs/RELEASE_NOTES.md) verbatim
5. **Attach binaries**: drag-drop
   `build/libs/morpheus-argus-0.1.43-all.jar`
6. Leave "Set as the latest release" checked
7. Publish

The attached JAR is what consumers install — they shouldn't have to
build from source unless they want to.

## Repo topics to set on the GitHub repo page

After the first push, hit the gear icon next to "About" on the repo
page and add these topics for discoverability:

`morpheus`, `hpe`, `ilo`, `redfish`, `plugin`, `groovy`,
`hpe-proliant`, `server-management`, `bmc`, `morpheusdata`

## LinkedIn post

After the GitHub release is live, see
[`docs/LINKEDIN_POST.md`](docs/LINKEDIN_POST.md) for the announcement
text (Variant A is the recommended one).

- Post the announcement
- Add the repo link in the first comment, not in the main post body
  (LinkedIn's algorithm penalizes posts with external links)
- Take the primary screenshot **after scrubbing IPs, hostnames, MACs,
  serials, and asset tags** per
  [`docs/SCREENSHOT_GUIDE.md`](docs/SCREENSHOT_GUIDE.md)
