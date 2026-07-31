#!/bin/sh
set -eu

module_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "${module_dir}/../.." && pwd)
. "${module_dir}/versions.env"

case "$(uname -m)" in
    aarch64 | arm64)
        "${module_dir}/setup-docker-amd64.sh"
        android_home="${ANDROID_HOME:-/opt/android-sdk}"
        if [ ! -d "${android_home}" ]; then
            printf 'Android SDK not found: %s\n' "${android_home}" >&2
            exit 1
        fi

        mkdir -p "${repo_dir}/.toolchains/docker-home"
        set -- \
            --rm \
            --platform linux/amd64 \
            --network host \
            --user "$(id -u):$(id -g)" \
            --env HOME=/work/.toolchains/docker-home \
            --env ANDROID_HOME=/opt/android-sdk \
            --env HTTP_PROXY \
            --env HTTPS_PROXY \
            --env NO_PROXY \
            --volume "${repo_dir}:/work" \
            --volume "${android_home}:/opt/android-sdk:ro"

        if [ -n "${ANDROID_NDK_HOME:-}" ]; then
            if [ ! -d "${ANDROID_NDK_HOME}" ]; then
                printf 'Android NDK not found: %s\n' "${ANDROID_NDK_HOME}" >&2
                exit 1
            fi
            set -- "$@" \
                --env ANDROID_NDK_HOME=/opt/android-ndk \
                --volume "${ANDROID_NDK_HOME}:/opt/android-ndk:ro"
        fi

        exec docker run "$@" \
            --workdir /work/native/embedded-tailnet \
            "${NATIVE_BUILDER_BASE_IMAGE}" \
            ./build.sh
        ;;
    x86_64)
        ;;
    *)
        printf 'Unsupported native build host architecture: %s\n' "$(uname -m)" >&2
        exit 1
        ;;
esac

toolchains_dir="${repo_dir}/.toolchains"
go_root="${toolchains_dir}/go${GO_VERSION}"
go_archive="${toolchains_dir}/go${GO_VERSION}.linux-amd64.tar.gz"
bin_dir="${toolchains_dir}/bin"
mkdir -p "${toolchains_dir}" "${bin_dir}" "${module_dir}/build"

if [ ! -x "${go_root}/bin/go" ]; then
    if ! printf '%s  %s\n' "${GO_LINUX_AMD64_SHA256}" "${go_archive}" |
        sha256sum -c - >/dev/null 2>&1; then
        curl -fL --retry 3 -C - "https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz" -o "${go_archive}"
    fi
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
CGO_ENABLED=0 go test ./...
gomobile bind \
    -target=android/arm64 \
    -androidapi=31 \
    -javapkg=com.code2hack.tailnet \
    -trimpath \
    -o "${module_dir}/build/embeddedtailnet.aar" \
    .

jar tf "${module_dir}/build/embeddedtailnet.aar" |
    grep -qx 'jni/arm64-v8a/libgojni.so'
