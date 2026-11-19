#!/bin/sh
# Publish the APK as a GitHub release, which is what Obtainium picks up.
#
#   ./release.sh "notes"           testing: builds origin/main's HEAD as a pre-release
#   ./release.sh --stable          stable: promotes the pre-release of origin/stable's HEAD to a
#                                  full release (builds it first if it was never released)
#
# Most people only follow full releases; turn on "include pre-releases" in Obtainium to get
# testing builds too. The version is <nullspaceVersion>.<commit count>, so a promoted build keeps
# its version and nobody downloads the same APK twice. Commit and push first; a dirty tree is
# refused. See AGENTS.md, "Branches and releases".
set -e
cd "$(dirname "$0")"
channel=testing
branch=main
if [ "$1" = "--stable" ]; then channel=stable; branch=stable; shift; fi
[ -z "$(git status --porcelain)" ] || { echo "Commit your changes first: the version comes from the commit count." >&2; exit 1; }
git fetch -q origin
[ "$(git rev-parse HEAD)" = "$(git rev-parse "origin/$branch")" ] || {
    echo "Check out origin/$branch first: the release has to be the commit GitHub has on $branch." >&2; exit 1; }
version="$(sed -n 's/^nullspaceVersion=//p' gradle.properties).$(git rev-list --count HEAD)"

if [ "$channel" = stable ] && gh release view "v$version" >/dev/null 2>&1; then
    gh release edit "v$version" --prerelease=false --latest --title "Nullspace $version"
    echo "Promoted v$version to stable"
    exit 0
fi

./build.sh
out="build/nullspace-$version.apk"
mkdir -p build
cp app/build/outputs/apk/release/app-release.apk "$out"
if [ "$channel" = testing ]; then
    gh release create "v$version" "$out" --target "$(git rev-parse HEAD)" --title "Nullspace $version (testing)" \
        --prerelease --notes "${1:-Testing build $version.}"
else
    gh release create "v$version" "$out" --target "$(git rev-parse HEAD)" --title "Nullspace $version" \
        --latest --notes "${1:-Android app $version.}"
fi
echo "Released v$version ($channel)"
