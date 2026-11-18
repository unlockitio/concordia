#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Shared build-root contract for every BabyCDF entrypoint. Sourced, not executed.
#
# Contract, identical in run-demo.sh and prepare-dependencies.sh:
#   BABYCDF_BUILD_ROOT unset -> default to <babycdf>/.build and create it.
#   BABYCDF_BUILD_ROOT set   -> must be absolute AND already exist. A relative or
#                               non-existent value is rejected and NOTHING is
#                               created, so an invalid override never leaves a
#                               directory behind.
# The value is canonicalized with `pwd -P` in both cases, so callers always see
# the same resolved path.
#
# babycdf_build_root <babycdf-package-dir>
babycdf_build_root() {
  babycdf_root_package=$1
  if [ -n "${BABYCDF_BUILD_ROOT:-}" ]; then
    case "$BABYCDF_BUILD_ROOT" in
      /*) ;;
      *) echo 'BABYCDF_BUILD_ROOT must be absolute' >&2; return 2 ;;
    esac
    # Validate before creating anything: an explicit override is caller-owned.
    [ -d "$BABYCDF_BUILD_ROOT" ] || { echo 'BABYCDF_BUILD_ROOT must exist' >&2; return 2; }
  else
    BABYCDF_BUILD_ROOT="$babycdf_root_package/.build"
    mkdir -p "$BABYCDF_BUILD_ROOT"
  fi
  BABYCDF_BUILD_ROOT=$(CDPATH= cd -- "$BABYCDF_BUILD_ROOT" && pwd -P)
  export BABYCDF_BUILD_ROOT
  unset babycdf_root_package
}

# Writes an isolated, script-owned multi-package manifest and echoes its
# DIRECTORY, which is what dpm 1.0.22 expects for --multi-package-path: it
# appends "/multi-package.yaml" to the value (the --help label FILE is
# inaccurate for this build; passing the file itself fails).
#
# Each call allocates a fresh mktemp -d subdirectory inside the build root, so a
# caller-owned $BUILD/multi-package.yaml is never read or overwritten and two
# concurrent preparations cannot disturb each other's manifest.
#
# babycdf_manifest_dir <build-root> <package-path>...
babycdf_manifest_dir() {
  babycdf_manifest_build=$1
  shift
  babycdf_manifest_home=$(mktemp -d "$babycdf_manifest_build/mp-XXXXXXXX")
  # Refuse to follow a pre-existing symlink at the manifest location.
  if [ -L "$babycdf_manifest_home/multi-package.yaml" ]; then
    echo 'generated manifest path is a symlink; refusing to write' >&2
    return 2
  fi
  # Quote each path as a YAML double-quoted scalar so spaces and most special
  # characters are safe. Paths containing a double quote, a backslash or a
  # control character are rejected rather than emitted unescaped.
  {
    echo 'packages:'
    for babycdf_manifest_path in "$@"; do
      case "$babycdf_manifest_path" in
        *'"'*|*'\'*)
          echo "unsupported character in package path: $babycdf_manifest_path" >&2
          return 2 ;;
      esac
      if printf '%s' "$babycdf_manifest_path" | LC_ALL=C grep -q '[[:cntrl:]]'; then
        echo "control character in package path: $babycdf_manifest_path" >&2
        return 2
      fi
      printf -- '- "%s"\n' "$babycdf_manifest_path"
    done
  } > "$babycdf_manifest_home/multi-package.yaml"
  printf '%s\n' "$babycdf_manifest_home"
  unset babycdf_manifest_build babycdf_manifest_home babycdf_manifest_path
}
