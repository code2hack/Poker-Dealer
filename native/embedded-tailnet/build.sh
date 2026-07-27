#!/bin/sh
set -eu

module_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "${module_dir}/../.." && pwd)
. "${module_dir}/versions.env"

toolchains_dir="${repo_dir}/.toolchains"
go_root="${toolchains_dir}/go${GO_VERSION}"
go_archive="${toolchains_dir}/go${GO_VERSION}.linux-amd64.tar.gz"
bin_dir="${toolchains_dir}/bin"
mkdir -p "${toolchains_dir}" "${bin_dir}" "${module_dir}/build"

if [ ! -x "${go_root}/bin/go" ]; then
    curl -fL --retry 3 -C - "https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz" -o "${go_archive}"
    printf '%s  %s\n' "${GO_LINUX_AMD64_SHA256}" "${go_archive}" | sha256sum -c -
    temp_dir=$(mktemp -d "${toolchains_dir}/go.XXXXXX")
    trap 'rm -rf "${temp_dir}"' EXIT INT TERM
    tar -xzf "${go_archive}" -C "${temp_dir}"
    mv "${temp_dir}/go" "${go_root}"
    rmdir "${temp_dir}"
    trap - EXIT INT TERM
fi

export GOROOT="${go_root}"
export PATH="${GOROOT}/bin:${bin_dir}:${PATH}"
export GOBIN="${bin_dir}"
export GOTOOLCHAIN=local
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_HOME}/ndk/${ANDROID_NDK_VERSION}}"

cd "${module_dir}"
go mod download
go mod verify
go install "golang.org/x/mobile/cmd/gomobile@${GOMOBILE_VERSION}"
go install "golang.org/x/mobile/cmd/gobind@${GOMOBILE_VERSION}"
go test ./...
gomobile bind \
    -target=android/arm64 \
    -androidapi=31 \
    -javapkg=com.code2hack.tailnet \
    -trimpath \
    -o "${module_dir}/build/embeddedtailnet.aar" \
    .

unzip -Z1 "${module_dir}/build/embeddedtailnet.aar" |
    grep -qx 'jni/arm64-v8a/libgojni.so'
