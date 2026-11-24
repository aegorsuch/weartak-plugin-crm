#!/bin/sh
set -eu

: "${CI_PROJECT_DIR:?CI_PROJECT_DIR is required}"
: "${CI_PIPELINE_IID:?CI_PIPELINE_IID is required}"
: "${CI_COMMIT_SHA:?CI_COMMIT_SHA is required}"
DIR="$CI_PROJECT_DIR/app/build/outputs/apk/release"
METADATA="$DIR/output-metadata.json"
VERSION="$(sed -n 's/^versionName=//p' "$CI_PROJECT_DIR/.gradle/wear-version.properties")"
BASE="$(sed -n 's/^baseVersionCode=//p' "$CI_PROJECT_DIR/.gradle/wear-version.properties")"
printf '%s\n' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "ERROR: Invalid canonical version" >&2; exit 1; }
printf '%s\n' "$BASE" | grep -Eq '^[1-9][0-9]{0,5}$' || { echo "ERROR: Invalid base version code" >&2; exit 1; }
printf '%s\n' "$CI_PIPELINE_IID" | grep -Eq '^[1-9][0-9]{0,3}$' || { echo "ERROR: Invalid pipeline IID" >&2; exit 1; }
printf '%s\n' "$CI_COMMIT_SHA" | grep -Eq '^[0-9a-f]{40}$' || { echo "ERROR: Invalid commit SHA" >&2; exit 1; }
CODE=$((BASE * 10000 + CI_PIPELINE_IID))
[ "$CODE" -le 2100000000 ] || { echo "ERROR: Invalid version code" >&2; exit 1; }
SHA8="$(printf '%.8s' "$CI_COMMIT_SHA")"
NAME="WearTAK-Plugin-CRM-${VERSION}-p${CI_PIPELINE_IID}.apk"
[ -f "$DIR/app-release.apk" ] && [ -f "$METADATA" ] || {
    echo "ERROR: Release APK or metadata missing" >&2
    exit 1
}
grep -F "\"versionCode\": $CODE," "$METADATA" >/dev/null || { echo "ERROR: APK version code mismatch" >&2; exit 1; }
grep -F "\"versionName\": \"$VERSION-p$CI_PIPELINE_IID+$SHA8\"" "$METADATA" >/dev/null || { echo "ERROR: APK version name mismatch" >&2; exit 1; }
grep -F '"outputFile": "app-release.apk"' "$METADATA" >/dev/null || { echo "ERROR: Unexpected release metadata" >&2; exit 1; }
sed -i "s/\"outputFile\": \"app-release.apk\"/\"outputFile\": \"$NAME\"/" "$METADATA"
mv "$DIR/app-release.apk" "$DIR/$NAME"
(cd "$CI_PROJECT_DIR" && sha256sum "app/build/outputs/apk/release/$NAME") > "$DIR/verified-apk.sha256"
