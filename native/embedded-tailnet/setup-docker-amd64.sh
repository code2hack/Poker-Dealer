#!/bin/sh
set -eu

module_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "${module_dir}/versions.env"

command -v docker >/dev/null 2>&1 || {
    printf 'Docker is required to build the native AAR on ARM64 Linux.\n' >&2
    exit 1
}
docker info >/dev/null 2>&1 || {
    printf 'Docker is installed but its daemon is not available to the current user.\n' >&2
    exit 1
}

if docker run --rm --platform linux/amd64 "${NATIVE_BUILDER_BASE_IMAGE}" true >/dev/null 2>&1; then
    exit 0
fi

printf 'Configuring Docker amd64 emulation for the Spark build host...\n'
docker run --privileged --rm "${NATIVE_BUILDER_BINFMT_IMAGE}" --uninstall amd64
docker run --privileged --rm "${NATIVE_BUILDER_BINFMT_IMAGE}" --install amd64
docker run --rm --platform linux/amd64 "${NATIVE_BUILDER_BASE_IMAGE}" true >/dev/null
