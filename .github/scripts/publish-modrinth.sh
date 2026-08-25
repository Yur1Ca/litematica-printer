#!/usr/bin/env bash

set -Eeuo pipefail

MODRINTH_API="https://api.modrinth.com/v2"
MODRINTH_PROJECT_ID="${MODRINTH_PROJECT_ID:-nriQwbvD}"
MODRINTH_FILE_DIR="${MODRINTH_FILE_DIR:-fabricWrapper/build/libs}"
MODRINTH_GAME_VERSIONS="${MODRINTH_GAME_VERSIONS:-[\"1.18.2\",\"1.19.4\",\"1.20.1\",\"1.20.2\",\"1.20.4\",\"1.20.6\",\"1.21\",\"1.21.1\",\"1.21.2\",\"1.21.3\",\"1.21.4\",\"1.21.5\",\"1.21.6\",\"1.21.7\",\"1.21.8\",\"1.21.9\",\"1.21.10\",\"1.21.11\",\"26.1\",\"26.1.1\",\"26.1.2\",\"26.2\"]}"
MODRINTH_DRY_RUN="${MODRINTH_DRY_RUN:-false}"
MODRINTH_REPOSITORY="${MODRINTH_REPOSITORY:-Yur1Ca/litematica-printer}"

: "${MODRINTH_VERSION_TYPE:?MODRINTH_VERSION_TYPE must be alpha or beta}"

if [[ "$MODRINTH_VERSION_TYPE" != "alpha" && "$MODRINTH_VERSION_TYPE" != "beta" ]]; then
  echo "Unsupported Modrinth version type: $MODRINTH_VERSION_TYPE" >&2
  exit 1
fi

if [[ "$MODRINTH_DRY_RUN" != "true" ]]; then
  : "${MODRINTH_TOKEN:?MODRINTH_TOKEN is required}"
fi

if ! jq -e 'type == "array" and length > 0 and all(.[]; type == "string")' <<< "$MODRINTH_GAME_VERSIONS" >/dev/null; then
  echo "MODRINTH_GAME_VERSIONS must be a non-empty JSON string array" >&2
  exit 1
fi

mapfile -t wrapper_jars < <(
  find "$MODRINTH_FILE_DIR" -maxdepth 1 -type f \
    -name 'litematica-printer-*.jar' \
    ! -name '*-sources.jar' \
    ! -name '*-dev.jar' \
    ! -name '*-shadow.jar' \
    -print
)

if [[ ${#wrapper_jars[@]} -ne 1 ]]; then
  echo "Expected exactly one wrapper JAR in $MODRINTH_FILE_DIR, found ${#wrapper_jars[@]}" >&2
  printf '  %s\n' "${wrapper_jars[@]:-<none>}" >&2
  exit 1
fi

wrapper_jar="${wrapper_jars[0]}"
filename="$(basename "$wrapper_jar")"
artifact_version="${filename#litematica-printer-}"
artifact_version="${artifact_version%.jar}"

if [[ -z "$artifact_version" || "$artifact_version" == "$filename" ]]; then
  echo "Could not derive the version number from $filename" >&2
  exit 1
fi

display_version="${artifact_version}"
case "$display_version" in
  dev*) display_version="Dev${display_version#dev}" ;;
  beta*) display_version="Beta${display_version#beta}" ;;
  local) display_version="Local" ;;
esac

# Keep the downloadable filename short, while giving Modrinth a stable,
# human-readable title that is independent of the Minecraft base version.
version_number="${MODRINTH_VERSION_NUMBER:-Hana-${display_version}}"
version_name="${MODRINTH_VERSION_NAME:-Litematica-Printer}"
if [[ -n "${MODRINTH_CHANGELOG:-}" ]]; then
  changelog="$MODRINTH_CHANGELOG"
elif [[ -n "${MODRINTH_COMMIT_SHA:-}" ]]; then
  if ! git cat-file -e "${MODRINTH_COMMIT_SHA}^{commit}" 2>/dev/null; then
    echo "Could not read commit $MODRINTH_COMMIT_SHA for the Modrinth changelog" >&2
    exit 1
  fi
  commit_message="$(git log -1 --format=%B "$MODRINTH_COMMIT_SHA")"
  short_commit="$(git rev-parse --short=12 "$MODRINTH_COMMIT_SHA")"
  changelog="$(printf '%s\n\nAutomated development build from [`%s`](https://github.com/%s/commit/%s).' \
    "$commit_message" \
    "$short_commit" \
    "$MODRINTH_REPOSITORY" \
    "$MODRINTH_COMMIT_SHA")"
else
  changelog="Automated $MODRINTH_VERSION_TYPE build."
fi
metadata_file="$(mktemp)"
trap 'rm -f "$metadata_file"' EXIT

jq -n \
  --arg project_id "$MODRINTH_PROJECT_ID" \
  --arg name "$version_name" \
  --arg version_number "$version_number" \
  --arg version_type "$MODRINTH_VERSION_TYPE" \
  --arg changelog "$changelog" \
  --argjson game_versions "$MODRINTH_GAME_VERSIONS" \
  '{
    project_id: $project_id,
    name: $name,
    version_number: $version_number,
    changelog: $changelog,
    dependencies: [
      {project_id: "bEpr0Arc", dependency_type: "required"},
      {project_id: "GcWjdA9I", dependency_type: "required"},
      {project_id: "P7dR8mSH", dependency_type: "required"},
      {project_id: "usAyJ0Wy", dependency_type: "optional"},
      {project_id: "t5wuYk45", dependency_type: "optional"}
    ],
    game_versions: $game_versions,
    version_type: $version_type,
    loaders: ["fabric"],
    featured: false,
    status: "listed",
    file_parts: ["file"],
    primary_file: "file"
  }' > "$metadata_file"

if [[ "$MODRINTH_DRY_RUN" == "true" ]]; then
  echo "Validated Modrinth upload: $filename"
  jq '{project_id, name, version_number, version_type, changelog, game_versions, loaders, dependencies}' "$metadata_file"
  exit 0
fi

user_agent="Yur1Ca/litematica-printer GitHub Actions (https://github.com/Yur1Ca/litematica-printer)"
existing_versions="$({
  curl --fail-with-body --silent --show-error \
    --header "Authorization: $MODRINTH_TOKEN" \
    --header "User-Agent: $user_agent" \
    "$MODRINTH_API/project/$MODRINTH_PROJECT_ID/version"
})"

if jq -e --arg version_number "$version_number" \
  'any(.[]; .version_number == $version_number)' <<< "$existing_versions" >/dev/null; then
  echo "Modrinth version $version_number already exists; skipping upload."
  exit 0
fi

response="$({
  curl --fail-with-body --silent --show-error \
    --request POST \
    --header "Authorization: $MODRINTH_TOKEN" \
    --header "User-Agent: $user_agent" \
    --form "data=@$metadata_file;type=application/json" \
    --form "file=@$wrapper_jar;type=application/java-archive" \
    "$MODRINTH_API/version"
})"

version_id="$(jq -r '.id // empty' <<< "$response")"
if [[ -z "$version_id" ]]; then
  echo "Modrinth did not return a version id" >&2
  exit 1
fi

echo "Published $version_number to https://modrinth.com/mod/$MODRINTH_PROJECT_ID/version/$version_id"
