#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Shared isolated build-root contract for BabyDRO entrypoints. Sourced.
# An explicit override is caller-owned: absolute and already existing.
babydro_build_root() {
  babydro_root_package=$1
  if [ -n "${BABYDRO_BUILD_ROOT:-}" ]; then
    case "$BABYDRO_BUILD_ROOT" in
      /*) ;;
      *) echo 'BABYDRO_BUILD_ROOT must be absolute' >&2; return 2 ;;
    esac
    [ -d "$BABYDRO_BUILD_ROOT" ] || { echo 'BABYDRO_BUILD_ROOT must exist' >&2; return 2; }
  else
    BABYDRO_BUILD_ROOT="$babydro_root_package/.build"
    mkdir -p "$BABYDRO_BUILD_ROOT"
  fi
  BABYDRO_BUILD_ROOT=$(CDPATH= cd -- "$BABYDRO_BUILD_ROOT" && pwd -P)
  export BABYDRO_BUILD_ROOT
  unset babydro_root_package
}

# dpm 1.0.22 expects a DIRECTORY for --multi-package-path, not a file.
# Allocate a fresh manifest per call, preserving caller-owned manifests.
babydro_manifest_dir() {
  babydro_manifest_build=$1
  shift
  babydro_manifest_home=$(mktemp -d "$babydro_manifest_build/mp-XXXXXXXX")
  if [ -L "$babydro_manifest_home/multi-package.yaml" ]; then
    echo 'generated manifest path is a symlink; refusing to write' >&2
    return 2
  fi
  {
    echo 'packages:'
    for babydro_manifest_path in "$@"; do
      case "$babydro_manifest_path" in
        *'"'*|*'\'*)
          echo "unsupported character in package path: $babydro_manifest_path" >&2
          return 2 ;;
      esac
      if printf '%s' "$babydro_manifest_path" | LC_ALL=C grep -q '[[:cntrl:]]'; then
        echo "control character in package path: $babydro_manifest_path" >&2
        return 2
      fi
      printf -- '- "%s"\n' "$babydro_manifest_path"
    done
  } > "$babydro_manifest_home/multi-package.yaml"
  printf '%s\n' "$babydro_manifest_home"
  unset babydro_manifest_build babydro_manifest_home babydro_manifest_path
}
